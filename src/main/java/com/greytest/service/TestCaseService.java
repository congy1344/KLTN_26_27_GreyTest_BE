package com.greytest.service;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.greytest.dto.CreateTestCaseRequest;
import com.greytest.dto.CoverageGapDto;
import com.greytest.dto.TestCaseDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto;
import com.greytest.entity.Project;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.TestType;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationContextBuilder;
import com.greytest.service.agent.LlmResponseException;

/** Quan ly test case sinh tu test plan va review HITL. */
@Service
public class TestCaseService {
    private final TestCaseRepository cases; private final TestPlanRepository plans; private final ProjectRepository projects; private final AIAgentService ai; private final BusinessRuleRepository rules; private final TransactionTemplate transactions; private final GenerationProgressService generationProgress;
    private ServiceScopeResolver scopeResolver;
    public TestCaseService(TestCaseRepository cases, TestPlanRepository plans, ProjectRepository projects, AIAgentService ai, BusinessRuleRepository rules, PlatformTransactionManager transactionManager, GenerationProgressService generationProgress) { this.cases=cases; this.plans=plans; this.projects=projects; this.ai=ai; this.rules=rules; this.transactions=new TransactionTemplate(transactionManager); this.generationProgress=generationProgress; }
    @org.springframework.beans.factory.annotation.Autowired
    public TestCaseService(TestCaseRepository cases, TestPlanRepository plans, ProjectRepository projects,
            AIAgentService ai, BusinessRuleRepository rules, PlatformTransactionManager transactionManager,
            GenerationProgressService generationProgress, ServiceScopeResolver scopeResolver) {
        this(cases, plans, projects, ai, rules, transactionManager, generationProgress);
        this.scopeResolver=scopeResolver;
    }

    @Transactional(readOnly=true) public List<TestCaseDto> list(Long projectId) { ensureProject(projectId); return plans.findByProjectId(projectId).stream().flatMap(p->cases.findByTestPlanId(p.getId()).stream()).sorted(Comparator.comparing(TestCase::getCaseCode, Comparator.nullsLast(String::compareTo))).map(this::dto).toList(); }
    @Transactional(readOnly=true) public List<TestCaseDto> list(Long projectId,String servicePath) { ensureProject(projectId); return scopedPlans(projectId,scopeResolver.resolve(projectId,servicePath)).stream().flatMap(p->cases.findByTestPlanId(p.getId()).stream()).sorted(Comparator.comparing(TestCase::getCaseCode,Comparator.nullsLast(String::compareTo))).map(this::dto).toList(); }

    // Regenerate được ở mọi pha từ PLAN_APPROVED trở đi: case cũ (kể cả case thủ công) bị thay
    // sạch, unit test cascade theo, status rollback về CASE_PENDING_REVIEW
    public List<TestCaseDto> generate(Long projectId) { return generate(projectId,(ServiceScopeResolver.ServiceScope)null); }
    public List<TestCaseDto> generate(Long projectId,String servicePath) { return generate(projectId,scopeResolver.resolve(projectId,servicePath)); }
    private List<TestCaseDto> generate(Long projectId,ServiceScopeResolver.ServiceScope scope) {
        Project project = ensureProject(projectId);
        if(scope==null) ensureCanGenerate(project);
        List<TestPlan> projectPlans = scope==null?plans.findByProjectId(projectId):scopedPlans(projectId,scope);
        if (projectPlans.stream().anyMatch(plan -> !cases.findByTestPlanId(plan.getId()).isEmpty())) {
            throw new InvalidProjectStatusException(
                    "Project da co Test Case. Hay chon mot Test Plan cu the de sinh lai.");
        }
        List<TestPlan> approvedPlans = projectPlans.stream()
                .filter(plan -> plan.getStatus() == ReviewStatus.APPROVED)
                .sorted(Comparator.comparing(TestPlan::getId))
                .toList();
        if (approvedPlans.isEmpty()) {
            throw new LlmResponseException("Khong co Test Plan da approve de sinh Test Case.");
        }

        List<List<TestPlan>> batches = planBatches(approvedPlans);
        generationProgress.start(projectId, GenerationProgressStage.TEST_CASE, batches.size() + 1,
                "Đã nhóm " + approvedPlans.size() + " Test Plan thành " + batches.size() + " batch.");
        List<GeneratedTestCaseDto> generatedCases = new ArrayList<>();
        int batchNumber = 0;
        try {
        for (List<TestPlan> batch : batches) {
            Set<Long> batchPlanIds = batch.stream()
                    .map(TestPlan::getId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var response = ai.generateTestCases(projectId, batchPlanIds);
            List<GeneratedTestCaseDto> validBatch = response.cases().stream()
                    .filter(testCase -> batchPlanIds.contains(testCase.planId()))
                    .filter(testCase -> isValid(testCase, projectId))
                    .toList();
            Set<Long> generatedPlanIds = validBatch.stream()
                    .map(GeneratedTestCaseDto::planId)
                    .collect(java.util.stream.Collectors.toSet());
            if (validBatch.isEmpty() || !generatedPlanIds.equals(batchPlanIds)) {
                throw new LlmResponseException("AI chua sinh du Test Case cho moi Test Plan da approve.");
            }
            generatedCases.addAll(validBatch);
            batchNumber++;
            generationProgress.advance(projectId, GenerationProgressStage.TEST_CASE,
                    "Batch " + batchNumber + "/" + batches.size() + ": đã kiểm tra "
                            + validBatch.size() + " Test Case hợp lệ.");
        }

        Set<Long> expectedPlanIds = approvedPlans.stream()
                .map(TestPlan::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<TestCaseDto> saved = transactions.execute(status -> persistGenerated(
                projectId, scope, expectedPlanIds, deduplicate(generatedCases, approvedPlans)));
        generationProgress.complete(projectId, GenerationProgressStage.TEST_CASE,
                "Hoàn tất: đã lưu " + (saved == null ? 0 : saved.size()) + " Test Case.");
        return saved;
        } catch (RuntimeException exception) {
            String failureLocation = batchNumber < batches.size()
                    ? "Dừng ở batch " + (batchNumber + 1) + "."
                    : "Dừng ở bước kiểm tra và lưu Test Case.";
            generationProgress.fail(projectId, GenerationProgressStage.TEST_CASE,
                    failureLocation + " Sinh Test Case thất bại; xem thông báo lỗi để biết chi tiết.");
            throw exception;
        }
    }

    /**
     * Sinh lai Test Case cua mot Test Plan ma khong anh huong cac plan khac.
     */
    public List<TestCaseDto> regenerate(Long projectId,String servicePath,Long planId) {
        var scope=scopeResolver.resolve(projectId,servicePath);
        if(scopedPlans(projectId,scope).stream().noneMatch(plan->planId.equals(plan.getId()))) {
            throw new IllegalArgumentException("Test Plan khong thuoc servicePath da chon.");
        }
        return regenerate(projectId,planId);
    }

    public List<TestCaseDto> regenerate(Long projectId, Long planId) {
        Project project = ensureProject(projectId);
        ensureCanGenerate(project);
        TestPlan plan = requireApprovedPlan(projectId, planId);
        PlanSnapshot expectedPlan = snapshot(plan);
        generationProgress.start(projectId, GenerationProgressStage.TEST_CASE, 2,
                "Bắt đầu sinh lại Test Case cho " + plan.getPlanCode() + ".");
        try {
        var response = ai.generateTestCases(projectId, Set.of(planId));
        List<GeneratedTestCaseDto> generatedCases = response.cases();
        if (generatedCases.isEmpty()
                || generatedCases.stream().anyMatch(testCase ->
                        testCase == null
                                || !planId.equals(testCase.planId())
                                || !isValid(testCase, projectId))) {
            throw new LlmResponseException(
                    "AI phai sinh Test Case chi cho Test Plan " + plan.getPlanCode() + ".");
        }
        generationProgress.advance(projectId, GenerationProgressStage.TEST_CASE,
                "Đã nhận và kiểm tra " + generatedCases.size() + " Test Case từ AI.");
        List<TestCaseDto> saved = transactions.execute(status -> persistRegeneratedPlan(
                projectId, expectedPlan, deduplicate(generatedCases, List.of(plan))));
        generationProgress.complete(projectId, GenerationProgressStage.TEST_CASE,
                "Hoàn tất sinh lại " + (saved == null ? 0 : saved.size()) + " Test Case.");
        return saved;
        } catch (RuntimeException exception) {
            generationProgress.fail(projectId, GenerationProgressStage.TEST_CASE,
                    "Sinh lại " + plan.getPlanCode() + " thất bại; xem thông báo lỗi để biết chi tiết.");
            throw exception;
        }
    }

    private List<TestCaseDto> persistGenerated(
            Long projectId,
            ServiceScopeResolver.ServiceScope scope,
            Set<Long> expectedPlanIds,
            List<GeneratedTestCaseDto> generatedCases) {
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        if(scope==null) ensureCanGenerate(project);
        List<TestPlan> currentPlans = (scope==null?plans.findByProjectId(projectId):scopedPlans(projectId,scope)).stream()
                .filter(plan -> plan.getStatus() == ReviewStatus.APPROVED)
                .toList();
        Set<Long> currentPlanIds = currentPlans.stream()
                .map(TestPlan::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!currentPlanIds.equals(expectedPlanIds)) {
            throw new InvalidProjectStatusException("Test Plan da thay doi trong luc sinh Test Case. Hay thu lai.");
        }
        cases.deleteAll(currentPlans.stream()
                .flatMap(plan -> cases.findByTestPlanId(plan.getId()).stream())
                .toList());
        int[] number = {nextCaseNumber()};
        var saved = cases.saveAll(generatedCases.stream()
                .map(testCase -> from(testCase, number[0]++))
                .toList());
        currentPlans.forEach(plan -> {
            plan.setIsModified(false);
            plans.save(plan);
        });
        project.setStatus(ProjectStatus.CASE_PENDING_REVIEW);
        projects.save(project);
        return saved.stream().map(this::dto).toList();
    }

    private List<TestCaseDto> persistRegeneratedPlan(
            Long projectId,
            PlanSnapshot expectedPlan,
            List<GeneratedTestCaseDto> generatedCases) {
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        ensureCanGenerate(project);
        TestPlan currentPlan = requireApprovedPlan(projectId, expectedPlan.id());
        if (!expectedPlan.equals(snapshot(currentPlan))) {
            throw new InvalidProjectStatusException(
                    "Test Plan da thay doi trong luc sinh Test Case. Hay thu lai.");
        }

        cases.deleteAll(cases.findByTestPlanId(currentPlan.getId()));
        int[] number = {nextCaseNumber()};
        var saved = cases.saveAll(generatedCases.stream()
                .map(testCase -> from(testCase, number[0]++))
                .toList());
        currentPlan.setIsModified(false);
        plans.save(currentPlan);
        project.setStatus(ProjectStatus.CASE_PENDING_REVIEW);
        projects.save(project);
        return saved.stream().map(this::dto).toList();
    }

    private void ensureCanGenerate(Project project) {
        if (!Set.of(ProjectStatus.PLAN_APPROVED, ProjectStatus.CASE_PENDING_REVIEW,
                ProjectStatus.CASE_APPROVED, ProjectStatus.TEST_GENERATED,
                ProjectStatus.COVERAGE_ANALYZED, ProjectStatus.COMPLETED).contains(project.getStatus())) {
            throw new InvalidProjectStatusException("Chi sinh Test Case sau khi Test Plan da approve.");
        }
    }

    private TestPlan requireApprovedPlan(Long projectId, Long planId) {
        TestPlan plan = plans.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Test Plan " + planId));
        if (!projectId.equals(plan.getProjectId()) || plan.getStatus() != ReviewStatus.APPROVED) {
            throw new InvalidProjectStatusException(
                    "Test Plan phai thuoc project hien tai va da approve.");
        }
        return plan;
    }

    private PlanSnapshot snapshot(TestPlan plan) {
        return new PlanSnapshot(
                plan.getId(),
                plan.getProjectId(),
                plan.getBusinessRuleId(),
                plan.getTitle(),
                plan.getDescription(),
                plan.getTestType(),
                plan.getStatus(),
                plan.getIsModified());
    }

    private List<GeneratedTestCaseDto> deduplicate(
            List<GeneratedTestCaseDto> generatedCases,
            List<TestPlan> sourcePlans) {
        var methodByPlan = new java.util.HashMap<Long, Long>();
        var remainingByPlan = new java.util.HashMap<Long, Integer>();
        for (TestPlan plan : sourcePlans) {
            Long methodId = rules.findById(plan.getBusinessRuleId())
                    .map(BusinessRule::getMethodId)
                    .orElse(plan.getId());
            methodByPlan.put(plan.getId(), methodId);
        }
        for (GeneratedTestCaseDto testCase : generatedCases) {
            remainingByPlan.merge(testCase.planId(), 1, Integer::sum);
        }

        Set<SemanticScenarioKey> seen = new java.util.HashSet<>();
        List<GeneratedTestCaseDto> unique = new ArrayList<>();
        for (GeneratedTestCaseDto testCase : generatedCases) {
            SemanticScenarioKey key = scenarioKey(
                    methodByPlan.getOrDefault(testCase.planId(), testCase.planId()), testCase);
            int remaining = remainingByPlan.getOrDefault(testCase.planId(), 1);
            if (seen.add(key) || remaining <= 1) {
                unique.add(testCase);
            } else {
                remainingByPlan.put(testCase.planId(), remaining - 1);
            }
        }
        return unique;
    }

    private List<List<TestPlan>> planBatches(List<TestPlan> approvedPlans) {
        List<List<TestPlan>> batches = new ArrayList<>();
        for (int start = 0; start < approvedPlans.size(); start += GenerationContextBuilder.MAX_TEST_CASE_PLANS) {
            batches.add(approvedPlans.subList(
                    start,
                    Math.min(start + GenerationContextBuilder.MAX_TEST_CASE_PLANS, approvedPlans.size())));
        }
        return batches;
    }
    /** Bổ sung case theo gap; nút bắt đầu vòng mới là xác nhận HITL nên case được duyệt ngay. */
    public List<TestCaseDto> generateSupplemental(Long projectId,String servicePath,List<CoverageGapDto> gaps,int round) {
        var scope=scopeResolver.resolve(projectId,servicePath);
        if(gaps.stream().map(CoverageGapDto::methodId).anyMatch(id->!scope.methodIds().contains(id))) {
            throw new IllegalArgumentException("Coverage gap khong thuoc servicePath da chon.");
        }
        return generateSupplemental(projectId,gaps,round);
    }
    @Transactional public List<TestCaseDto> generateSupplemental(Long projectId,List<CoverageGapDto> gaps,int round) {
        Project p=ensureProject(projectId);
        if(!Set.of(ProjectStatus.COVERAGE_ANALYZED,ProjectStatus.COMPLETED).contains(p.getStatus()))
            throw new InvalidProjectStatusException("Chỉ bắt đầu vòng mới sau khi upload JaCoCo XML.");
        if(gaps==null||gaps.isEmpty()) throw new InvalidProjectStatusException("Không có coverage gap để bổ sung.");
        var methodIds=gaps.stream().map(CoverageGapDto::methodId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        var planMethodIds=plans.findByProjectId(projectId).stream()
                .filter(plan->plan.getStatus()==ReviewStatus.APPROVED)
                .collect(java.util.stream.Collectors.toMap(TestPlan::getId,plan->rules.findById(plan.getBusinessRuleId()).map(BusinessRule::getMethodId).orElse(-1L)));
        var refinableMethodIds=planMethodIds.values().stream().filter(methodIds::contains).collect(java.util.stream.Collectors.toSet());
        var refinableGaps=gaps.stream().filter(gap->refinableMethodIds.contains(gap.methodId())).toList();
        if(refinableGaps.isEmpty()) throw new InvalidProjectStatusException("Coverage gap chưa liên kết với Business Rule và Test Plan đã duyệt.");
        var response=ai.generateCoverageRefinement(projectId,round,refinableGaps);
        var valid=response.cases().stream().filter(c->isValid(c,projectId)&&refinableMethodIds.contains(planMethodIds.get(c.planId()))).toList();
        var generatedMethodIds=valid.stream().map(c->planMethodIds.get(c.planId())).collect(java.util.stream.Collectors.toSet());
        if(valid.isEmpty()||valid.size()!=response.cases().size()||!generatedMethodIds.containsAll(refinableMethodIds))
            throw new LlmResponseException("AI sinh Test Case không liên kết đúng Test Plan đã duyệt.");
        var scenarioKeys=planMethodIds.entrySet().stream()
                .filter(entry->refinableMethodIds.contains(entry.getValue()))
                .flatMap(entry->cases.findByTestPlanId(entry.getKey()).stream()
                        .map(testCase->scenarioKey(entry.getValue(),testCase)))
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        if(valid.stream().map(testCase->scenarioKey(planMethodIds.get(testCase.planId()),testCase))
                .anyMatch(key->!scenarioKeys.add(key)))
            throw new LlmResponseException("AI sinh Test Case trùng với scenario đã có.");
        int[] n={nextCaseNumber()};
        var saved=cases.saveAll(valid.stream().map(x->supplemental(x,n[0]++,round)).toList());
        p.setStatus(ProjectStatus.CASE_APPROVED);
        projects.save(p);
        return saved.stream().map(this::dto).toList();
    }
    @Transactional public TestCaseDto create(Long projectId, CreateTestCaseRequest r) { Project p=ensureProject(projectId); TestPlan plan=plans.findById(r.testPlanId()).orElseThrow(); if(!projectId.equals(plan.getProjectId())) throw new IllegalArgumentException("Test Plan khong thuoc project"); ensureEditable(p); TestCase c=new TestCase(); c.setTestPlanId(plan.getId()); c.setCaseCode(nextCode(projectId)); c.setTestType(r.testType()); c.setDescription(r.description().trim()); c.setPreconditions(r.preconditions().trim()); c.setTestData(r.testData()); c.setExpectedResult(r.expectedResult().trim()); c.setPriority(r.priority()); c.setTraceSource(r.traceSource().trim()); c.setStatus(ReviewStatus.PENDING_REVIEW); c.setIsModified(false); p.setStatus(ProjectStatus.CASE_PENDING_REVIEW); projects.save(p); return dto(cases.save(c)); }
    public TestCaseDto create(Long projectId,String servicePath,CreateTestCaseRequest request) {
        var scope=scopeResolver.resolve(projectId,servicePath);
        if(scopedPlans(projectId,scope).stream().noneMatch(plan->request.testPlanId().equals(plan.getId())))
            throw new IllegalArgumentException("Test Plan khong thuoc servicePath da chon.");
        return create(projectId,request);
    }

    @Transactional(readOnly=true) public Long projectIdForCase(Long caseId) { TestCase c=cases.findById(caseId).orElseThrow(()->new IllegalArgumentException("Khong tim thay Test Case "+caseId)); return plans.findById(c.getTestPlanId()).orElseThrow(()->new IllegalArgumentException("Test Case khong co Test Plan")).getProjectId(); }
    // Sửa case (HITL): đánh dấu isModified và đưa project về trạng thái chờ review lại
    @Transactional public TestCaseDto update(Long caseId, com.greytest.dto.UpdateTestCaseRequest r) { TestCase c=cases.findById(caseId).orElseThrow(()->new IllegalArgumentException("Khong tim thay Test Case "+caseId)); Project p=ensureProject(projectIdForCase(caseId)); ensureEditable(p); c.setTestType(r.testType()); c.setDescription(r.description().trim()); c.setPreconditions(r.preconditions().trim()); c.setTestData(r.testData()); c.setExpectedResult(r.expectedResult().trim()); c.setPriority(r.priority()); c.setTraceSource(r.traceSource().trim()); c.setStatus(ReviewStatus.PENDING_REVIEW); c.setIsModified(true); p.setStatus(ProjectStatus.CASE_PENDING_REVIEW); projects.save(p); return dto(cases.save(c)); }
    @Transactional public void delete(Long caseId) { TestCase c=cases.findById(caseId).orElseThrow(()->new IllegalArgumentException("Khong tim thay Test Case "+caseId)); Project p=ensureProject(projectIdForCase(caseId)); ensureEditable(p); cases.delete(c); p.setStatus(ProjectStatus.CASE_PENDING_REVIEW); projects.save(p); }
    @Transactional public List<TestCaseDto> approve(Long projectId) { Project p=ensureProject(projectId); if(p.getStatus()!=ProjectStatus.CASE_PENDING_REVIEW) throw new InvalidProjectStatusException("Chi approve Test Case dang cho review."); var all=list(projectId); if(all.isEmpty()) throw new InvalidProjectStatusException("Can co it nhat mot Test Case."); var entities=plans.findByProjectId(projectId).stream().flatMap(x->cases.findByTestPlanId(x.getId()).stream()).toList(); entities.forEach(c->{c.setStatus(ReviewStatus.APPROVED); cases.save(c);}); p.setStatus(ProjectStatus.CASE_APPROVED); projects.save(p); return entities.stream().map(this::dto).toList(); }
    @Transactional public List<TestCaseDto> approve(Long projectId,String servicePath) {
        Project p=ensureProject(projectId);
        var entities=scopedPlans(projectId,scopeResolver.resolve(projectId,servicePath)).stream().flatMap(plan->cases.findByTestPlanId(plan.getId()).stream()).toList();
        if(entities.isEmpty()) throw new InvalidProjectStatusException("Can co it nhat mot Test Case.");
        entities.forEach(c->{c.setStatus(ReviewStatus.APPROVED);cases.save(c);});
        p.setStatus(ProjectStatus.CASE_APPROVED);projects.save(p);
        return entities.stream().map(this::dto).toList();
    }
    private boolean isValid(GeneratedTestCaseDto c, Long projectId){ if(c==null||!plans.existsById(c.planId())||parseTypeOrNull(c.testType())==null) return false; var p=plans.findById(c.planId()).orElse(null); return p!=null&&projectId.equals(p.getProjectId())&&p.getStatus()==ReviewStatus.APPROVED; }
    private TestCase from(GeneratedTestCaseDto x,int n){ TestCase c=new TestCase(); c.setTestPlanId(x.planId()); c.setCaseCode("TC-"+String.format("%03d", n)); c.setTestType(parseType(x.testType())); c.setDescription(x.description()); c.setPreconditions(x.preconditions()); c.setTestData(x.testData()); c.setExpectedResult(x.expectedResult()); c.setPriority(Priority.valueOf(x.priority())); c.setTraceSource(x.traceSource()); c.setStatus(ReviewStatus.PENDING_REVIEW); c.setIsModified(false); return c; }
    private TestCase supplemental(GeneratedTestCaseDto x,int n,int round){ TestCase c=from(x,n); c.setStatus(ReviewStatus.APPROVED); if(!x.traceSource().contains("JaCoCo")) c.setTraceSource(x.traceSource()+" -> JaCoCo round "+round); return c; }
    private int nextCaseNumber(){ return cases.findAll().stream().map(TestCase::getCaseCode).filter(java.util.Objects::nonNull).filter(code->code.matches("TC-\\d+")).mapToInt(code->Integer.parseInt(code.substring(3))).max().orElse(0)+1; }
    private SemanticScenarioKey scenarioKey(Long methodId,TestCase c){
        return new SemanticScenarioKey(methodId,normalize(c.getPreconditions()),c.getTestData(),normalize(c.getExpectedResult()));
    }
    private SemanticScenarioKey scenarioKey(Long methodId,GeneratedTestCaseDto c){
        return new SemanticScenarioKey(methodId,normalize(c.preconditions()),c.testData(),normalize(c.expectedResult()));
    }
    private String normalize(String value){return value==null?"":value.trim().replaceAll("\\s+"," ").toLowerCase(java.util.Locale.ROOT);}
    private record SemanticScenarioKey(Long methodId,String preconditions,java.util.Map<String,Object> testData,String expectedResult){}
    private record PlanSnapshot(Long id,Long projectId,Long businessRuleId,String title,String description,TestType testType,ReviewStatus status,Boolean isModified){}
    private TestType parseType(String s){ return TestType.valueOf(s); }
    private TestType parseTypeOrNull(String s){ try{return TestType.valueOf(s);}catch(Exception e){return null;} }
    private String nextCode(Long id){ return "TC-"+String.format("%03d", cases.count()+1); }
    // Cho phép thao tác cả sau khi đã có coverage — vòng lặp gap: bổ sung case → approve lại
    // → sinh lại unit test → upload jacoco vòng mới. Mọi thay đổi đều kéo status về CASE_PENDING_REVIEW.
    private void ensureEditable(Project p){ if(!Set.of(ProjectStatus.PLAN_APPROVED,ProjectStatus.CASE_PENDING_REVIEW,ProjectStatus.CASE_APPROVED,ProjectStatus.TEST_GENERATED,ProjectStatus.COVERAGE_ANALYZED,ProjectStatus.COMPLETED).contains(p.getStatus())) throw new InvalidProjectStatusException("Chi thao tac Test Case sau khi Test Plan da approve."); }
    private List<TestPlan> scopedPlans(Long projectId,ServiceScopeResolver.ServiceScope scope) {
        var ruleIds=rules.findByProjectId(projectId).stream()
                .filter(rule->scope.methodIds().contains(rule.getMethodId()))
                .map(BusinessRule::getId).collect(java.util.stream.Collectors.toSet());
        return plans.findByProjectId(projectId).stream()
                .filter(plan->ruleIds.contains(plan.getBusinessRuleId()))
                .toList();
    }
    private Project ensureProject(Long id){ return projects.findById(id).orElseThrow(()->new ProjectNotFoundException(id)); }
    private TestCaseDto dto(TestCase c){ return new TestCaseDto(c.getId(),c.getTestPlanId(),c.getCaseCode(),c.getTestType(),c.getDescription(),c.getPreconditions(),c.getTestData(),c.getExpectedResult(),c.getPriority(),c.getTraceSource(),c.getStatus(),c.getIsModified(),c.getCreatedAt()); }
}
