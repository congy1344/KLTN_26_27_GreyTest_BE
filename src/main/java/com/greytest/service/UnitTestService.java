package com.greytest.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.greytest.dto.UnitTestDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.BlockedUnitTestDto;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.UsageQuotaExceededException;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationContextBuilder;
import com.greytest.service.agent.LlmResponseException;

/** Sinh va luu unit test JUnit/Mockito tu test case da duyet. */
@Service
public class UnitTestService {
    private static final int MAX_UNIT_TEST_RECOVERY_CALLS = 4;
    private static final String UNFINISHED_CASE = "Chưa sinh được: ";
    private final UnitTestRepository units; private final TestCaseRepository cases; private final TestPlanRepository plans; private final ProjectRepository projects; private final AIAgentService ai; private final UnitTestFileService files; private final TransactionTemplate transactions; private final GenerationProgressService generationProgress;
    private ServiceScopeResolver scopeResolver; private ServicePipelineStatusService scopedArtifacts; private LlmBatchExecutor batchExecutor;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.greytest.service.generation.UnitTestExportProvenanceService exportProvenance;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.greytest.repository.BusinessRuleRepository rules;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.greytest.repository.JavaMethodRepository methods;
    @org.springframework.beans.factory.annotation.Value("${greytest.llm-batch.unit-test-per-service:false}")
    private boolean unitTestPerService;
    /** Bộ test đã kiểm tra cho export; khóa gồm mọi đầu vào nên đổi test, case, plan, rule hoặc source là tự tính lại. Mất khi khởi động lại. */
    private record ValidatedExport(String fingerprint, List<UnitTest> tests) { }
    private final Map<String, ValidatedExport> validatedExports = new java.util.concurrent.ConcurrentHashMap<>();
    // Test đã qua validator với cùng ngữ cảnh (TC/TP/BR/source) và cùng nội dung: sandbox export lại sau vòng AI sửa
    // chỉ kiểm tra test vừa đổi. Chỉ lưu kết quả hợp lệ; test lỗi luôn được kiểm tra lại.
    private final Map<String, Boolean> validatedTests = new java.util.concurrent.ConcurrentHashMap<>();
    // Lỗi output AI (LLM_OUTPUT_INVALID) theo case: lượt đang chạy, lượt trước, và các case đã hỏng y hệt 2 lượt liên tiếp.
    // ponytail: giữ trong bộ nhớ (mất khi restart backend); "Sinh lại" từ đầu xoá cho project.
    private final Map<Long, Map<Long, String>> roundOutputFailures = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Map<Long, String>> lastOutputFailures = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Map<Long, String>> repeatedOutputFailures = new java.util.concurrent.ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Value("${greytest.llm-batch.unit-test-per-service-max-cases:15}")
    private int unitTestPerServiceMaxCases;
    /** Số vòng hệ thống tự "Tiếp tục sinh" khi còn case chưa sinh được; 0 là tắt. */
    @org.springframework.beans.factory.annotation.Value("${greytest.llm-batch.auto-resume-rounds:0}")
    private int autoResumeRounds;
    /** Bật khi chạy thật: lỗi output AI của một batch không làm dừng cả lượt sinh. Test đơn vị thuần để mặc định false. */
    @org.springframework.beans.factory.annotation.Value("${greytest.llm-batch.isolate-batch-failures:true}")
    private boolean isolateBatchFailures;
    public UnitTestService(UnitTestRepository units, TestCaseRepository cases, TestPlanRepository plans, ProjectRepository projects, AIAgentService ai, UnitTestFileService files, PlatformTransactionManager transactionManager, GenerationProgressService generationProgress){this.units=units;this.cases=cases;this.plans=plans;this.projects=projects;this.ai=ai;this.files=files;this.transactions=new TransactionTemplate(transactionManager);this.generationProgress=generationProgress;}
    @org.springframework.beans.factory.annotation.Autowired
    public UnitTestService(UnitTestRepository units,TestCaseRepository cases,TestPlanRepository plans,ProjectRepository projects,AIAgentService ai,UnitTestFileService files,PlatformTransactionManager transactionManager,GenerationProgressService generationProgress,ServiceScopeResolver scopeResolver,ServicePipelineStatusService scopedArtifacts,LlmBatchExecutor batchExecutor){
        this(units,cases,plans,projects,ai,files,transactionManager,generationProgress);
        this.scopeResolver=scopeResolver;
        this.scopedArtifacts=scopedArtifacts;
        this.batchExecutor=batchExecutor;
    }
    public UnitTestService(UnitTestRepository units,TestCaseRepository cases,TestPlanRepository plans,ProjectRepository projects,AIAgentService ai,UnitTestFileService files,PlatformTransactionManager transactionManager,GenerationProgressService generationProgress,ServiceScopeResolver scopeResolver,ServicePipelineStatusService scopedArtifacts){
        this(units,cases,plans,projects,ai,files,transactionManager,generationProgress,scopeResolver,scopedArtifacts,null);
    }

    @Transactional(readOnly=true) public List<UnitTestDto> list(Long projectId){ensure(projectId); return cases.findByProjectId(projectId).stream().filter(c->approvedCase(c,projectId)).map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).map(this::dto).toList();}
    @Transactional(readOnly=true) public List<UnitTestDto> list(Long projectId,String servicePath){
        ensure(projectId);
        if(servicePath==null||scopeResolver==null) return list(projectId);
        return approvedCases(projectId,scopeResolver.resolve(projectId,servicePath)).stream().map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).map(this::dto).toList();
    }
    public List<UnitTestDto> generate(Long projectId){return generate(projectId,(ServiceScopeResolver.ServiceScope)null,false);}
    public List<UnitTestDto> generate(Long projectId,String servicePath){return generate(projectId, (servicePath == null || scopeResolver == null) ? null : scopeResolver.resolve(projectId, servicePath), false);}
    public List<UnitTestDto> generate(Long projectId,String servicePath,boolean resume){return generate(projectId, (servicePath == null || scopeResolver == null) ? null : scopeResolver.resolve(projectId, servicePath), resume);}
    private List<UnitTestDto> generate(Long projectId,ServiceScopeResolver.ServiceScope scope,boolean resume){return generate(projectId,scope,resume,autoResumeRounds);}
    private List<UnitTestDto> generate(Long projectId,ServiceScopeResolver.ServiceScope scope,boolean resume,int resumeRoundsLeft){
        Project project=ensure(projectId);
        if(scope==null)ensureCanGenerate(project);
        var approved=scope==null?approvedCases(projectId):approvedCases(projectId,scope);
        if(approved.isEmpty()) throw new LlmResponseException("Không có Test Case đã approve để sinh Unit Test.");
        ai.validateUnitTestPrerequisites(projectId, approved.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet()));
        List<List<TestCase>> allBatches = caseBatches(approved);
        Set<Long> validatedIds = new java.util.HashSet<>();

        List<TestCase> target;
        List<List<TestCase>> batchesToRun;
        List<Integer> batchIndexes;
        Map<Long,BlockedUnitTestDto> skippedCases = new LinkedHashMap<>();
        if(resume){
            // Nhận vòng bổ sung trước bước rà soát dài; pause không được thay worker còn đang kiểm tra.
            generationProgress.resume(projectId, GenerationProgressStage.UNIT_TEST, progressLabels(allBatches),
                    Set.of(), "Đang kiểm tra Unit Test đã lưu trước khi bổ sung phần thiếu.");
            // Upstream đã khóa trong job: đọc một snapshot cho cả vòng, tránh tải lại toàn bộ TP/TC cho từng UT.
            String resumeContext = validationContext(projectId, approved);
            GenerationContextBuilder.withInputSnapshot(() -> {
            for (var testCase : approved) {
                var existing = units.findByTestCaseId(testCase.getId());
                if (existing == null) continue;
                // Cùng ngữ cảnh và cùng nội dung test đã qua kiểm tra ở lượt resume trước: không kiểm tra lại (~0,3 s/test).
                String key = resumeContext == null ? null : "resume:" + resumeContext + ":" + existing.getId() + ":" + testCase.getId() + ":"
                        + com.greytest.service.agent.GenerationSourceFingerprint.hash(existing.getPackageName() + "|"
                        + existing.getTestClassName() + "|" + existing.getTestMethodName() + "|" + existing.getSourceCode());
                if (key != null && validatedTests.containsKey(key)) { validatedIds.add(testCase.getId()); continue; }
                try {
                    ai.validateExistingUnitTest(projectId, existing, null);
                    validatedIds.add(testCase.getId());
                    if (key != null) validatedTests.put(key, Boolean.TRUE);
                } catch (LlmResponseException invalid) {
                    generationProgress.log(projectId, GenerationProgressStage.UNIT_TEST,
                            "UT của " + testCase.getCaseCode() + " không còn hợp lệ; cần phục hồi: " + invalid.getMessage());
                }
            }
            return null;
            });
            if (generationProgress.isPaused(projectId, GenerationProgressStage.UNIT_TEST))
                return list(projectId, scope != null ? scope.servicePath() : null);
            generationProgress.assertCurrentMeasurement(projectId, GenerationProgressStage.UNIT_TEST);
            target = approved.stream().filter(c -> !validatedIds.contains(c.getId())).toList();
            // Case đã hỏng y hệt nhau ở 2 lượt liên tiếp: gọi lại AI gần như chắc chắn ra cùng lỗi, chỉ tốn thời gian/token.
            Map<Long,String> repeated = repeatedOutputFailures.getOrDefault(projectId, Map.of());
            target.stream().filter(c -> repeated.containsKey(c.getId())).forEach(c -> skippedCases.put(c.getId(),
                    new BlockedUnitTestDto(c.getId(), c.getCaseCode(), "AI trả cùng một lỗi ở 2 lượt liên tiếp nên không tự sinh lại; "
                            + "hãy xem lại Test Case/Business Rule hoặc sửa tay rồi chạy Sinh lại. Lỗi: " + repeated.get(c.getId()))));
            target = target.stream().filter(c -> !skippedCases.containsKey(c.getId())).toList();
            if (target.isEmpty() && !skippedCases.isEmpty()) {
                var current = list(projectId, scope != null ? scope.servicePath() : null);
                finishProgress(projectId, current == null ? 0 : current.size(), skippedCases, false);
                return current;
            }
            if(target.isEmpty()){
                transactions.executeWithoutResult(status -> {
                    Project currentProject = lockedProject(projectId);
                    var currentApproved = scope == null ? approvedCases(projectId) : approvedCases(projectId, scope);
                    if (!currentApproved.stream().allMatch(c -> validatedIds.contains(c.getId())))
                        throw new InvalidProjectStatusException("Test Case đã thay đổi trong lúc kiểm tra UT; hãy tiếp tục sinh.");
                    currentProject.setStatus(ProjectStatus.TEST_GENERATED);
                    projects.save(currentProject);
                });
                generationProgress.completeAfterCommit(projectId, GenerationProgressStage.UNIT_TEST,
                        "Tất cả " + approved.size() + " Test Case đã có Unit Test đầy đủ.");
                return list(projectId, scope != null ? scope.servicePath() : null);
            }
            if (project.getStatus() == ProjectStatus.TEST_GENERATED) transactions.executeWithoutResult(status -> {
                var currentProject = lockedProject(projectId);
                currentProject.setStatus(ProjectStatus.CASE_APPROVED);
                projects.save(currentProject);
            });
            Set<Long> targetIds = target.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet());
            batchIndexes = java.util.stream.IntStream.range(0, allBatches.size())
                    .filter(index -> allBatches.get(index).stream().anyMatch(testCase -> targetIds.contains(testCase.getId())))
                    .boxed()
                    .toList();
            batchesToRun = batchIndexes.stream()
                    .map(index -> allBatches.get(index).stream().filter(testCase -> targetIds.contains(testCase.getId())).toList())
                    .toList();

            // Xác định chính xác các batch đã có sẵn trong CSDL
            Set<Integer> completedBatchIndices = new LinkedHashSet<>();
            for (int i = 0; i < allBatches.size(); i++) {
                List<TestCase> batch = allBatches.get(i);
                boolean allInDb = batch.stream().allMatch(tc -> validatedIds.contains(tc.getId()));
                if (allInDb) {
                    completedBatchIndices.add(i);
                }
            }

            List<String> stepLabels = progressLabels(allBatches);
            String batchDetailList = batchIndexes.stream()
                    .map(idx -> "batch " + (idx + 1) + "/" + allBatches.size())
                    .collect(java.util.stream.Collectors.joining(", "));
            String message = "Tiếp tục sinh Unit Test cho " + batchesToRun.size() + " batch thiếu hoặc cần phục hồi (" + batchDetailList + ").";

            // Giữ snapshot cũ cả khi chưa có batch nào hoàn tất trọn vẹn; batch hiện tại có thể mới lưu một phần.
            generationProgress.resume(projectId, GenerationProgressStage.UNIT_TEST, stepLabels, completedBatchIndices, message);
            for (int compIdx : completedBatchIndices) {
                generationProgress.log(projectId, GenerationProgressStage.UNIT_TEST,
                        "Batch " + (compIdx + 1) + "/" + allBatches.size() + ": Đã có sẵn trong CSDL ("
                                + formatTestCaseBatchSummary(allBatches.get(compIdx)) + ").");
            }
        } else {
            // Không xóa UT cũ ở đây: persistBatch thay từng case khi đã có kết quả mới hợp lệ.
            forgetOutputFailures(projectId);
            target = approved;
            batchesToRun = allBatches;
            batchIndexes = java.util.stream.IntStream.range(0, allBatches.size()).boxed().toList();
            startProgress(projectId, target, "Bắt đầu sinh Unit Test từ Test Case đã approve.");
        }

        try {
            Map<Long,BlockedUnitTestDto> blockedCases = new java.util.concurrent.ConcurrentHashMap<>(skippedCases);
            var generated=generateBatches(projectId,batchesToRun,batchIndexes,allBatches.size(),blockedCases);
            closeOutputFailureRound(projectId);
            generated.forEach(unit -> validatedIds.add(unit.caseId()));
            boolean hasRemaining = !target.stream().allMatch(tc -> validatedIds.contains(tc.getId()) || blockedCases.containsKey(tc.getId()));
            if (hasRemaining && generationProgress.isPaused(projectId, GenerationProgressStage.UNIT_TEST)) {
                generationProgress.log(projectId, GenerationProgressStage.UNIT_TEST,
                        "Tác vụ đã tạm dừng. Các Unit Test đã sinh được lưu an toàn vào CSDL. Nhấn 'Tiếp tục sinh' khi bạn sẵn sàng.");
                return list(projectId, scope != null ? scope.servicePath() : null);
            }
            var expectedIds=target.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet());
            transactions.execute(status -> {
                Project p = lockedProject(projectId);
                if (scope == null) ensureCanGenerate(p);
                var approvedCurrent = scope == null ? approvedCases(projectId) : approvedCases(projectId, scope);
                if (!resume) {
                    var currentIds = approvedCurrent.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet());
                    if (!currentIds.equals(expectedIds)) {
                        throw new InvalidProjectStatusException("Test Case đã thay đổi trong lúc sinh Unit Test. Hãy thử lại.");
                    }
                }
                boolean allCovered = approvedCurrent.stream().allMatch(c -> validatedIds.contains(c.getId()) && units.findByTestCaseId(c.getId()) != null);
                if (allCovered) {
                    p.setStatus(ProjectStatus.TEST_GENERATED);
                    projects.save(p);
                } else if (!blockedCases.isEmpty() && p.getStatus() == ProjectStatus.TEST_GENERATED) {
                    p.setStatus(ProjectStatus.CASE_APPROVED);
                    projects.save(p);
                }
                return null;
            });
            // Case hỏng do output AI thường đạt ở lượt gọi mới: tự làm thay thao tác "Tiếp tục sinh", có giới hạn vòng để không đốt credit.
            long unfinished = blockedCases.values().stream()
                    .filter(blocked -> blocked.details() != null && blocked.details().startsWith(UNFINISHED_CASE)).count();
            if (unfinished > 0 && resumeRoundsLeft > 0) {
                generationProgress.log(projectId, GenerationProgressStage.UNIT_TEST,
                        "Còn " + unfinished + " Test Case chưa sinh được; hệ thống tự tiếp tục sinh (còn " + resumeRoundsLeft + " vòng).");
            } else {
                var saved = generated.stream().map(this::from).map(this::dto).toList();
                var allTests = list(projectId, scope != null ? scope.servicePath() : null);
                int totalCount = allTests != null && !allTests.isEmpty() ? allTests.size() : saved.size();
                finishProgress(projectId, totalCount, blockedCases, false);
                return saved != null && !saved.isEmpty() ? saved : allTests;
            }
        } catch(RuntimeException exception){
            if (generationProgress.isPaused(projectId, GenerationProgressStage.UNIT_TEST)) {
                return list(projectId, scope != null ? scope.servicePath() : null);
            }
            boolean isQuota = isUsageQuotaExceeded(exception);
            int failedBatch = LlmBatchExecutor.failedBatch(exception, 0);
            int originalBatch = failedBatch > 0 && failedBatch <= batchIndexes.size()
                    ? batchIndexes.get(failedBatch - 1) + 1
                    : failedBatch;
            String detailMsg = isQuota
                    ? " Bạn đã sử dụng hết quota LLM trong tháng này. Vui lòng nâng cấp lên gói PRO để tiếp tục sinh."
                    : " Sinh Unit Test thất bại; các batch đã sinh trước đó đã được lưu an toàn. Bạn có thể nhấn 'Tiếp tục sinh' để chạy tiếp."
                    + (resume ? "" : " Case chưa tới lượt vẫn giữ Unit Test cũ và 'Tiếp tục sinh' chỉ làm lại phần thiếu hoặc không còn hợp lệ; muốn thay toàn bộ hãy chạy Sinh lại.");
            generationProgress.fail(projectId, GenerationProgressStage.UNIT_TEST,
                    (originalBatch > 0 ? "Dừng ở batch " + originalBatch + ". " : "") + detailMsg);
            throw LlmBatchExecutor.originalFailure(exception);
        }
        // Gọi ngoài try để lỗi của vòng tự tiếp tục không bị báo thất bại thêm lần nữa ở vòng này.
        generate(projectId, scope, true, resumeRoundsLeft - 1);
        return list(projectId, scope != null ? scope.servicePath() : null);
    }
    /** Sinh Unit Test chỉ cho case vòng mới, giữ nguyên toàn bộ output các vòng trước. */
    public List<UnitTestDto> generateSupplemental(Long projectId,String servicePath,List<Long> caseIds){
        var allowed=approvedCases(projectId,scopeResolver.resolve(projectId,servicePath)).stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet());
        if(!allowed.containsAll(caseIds)){
            throw new InvalidProjectStatusException("Test Case bổ sung không thuộc servicePath đã chọn.");
        }
        return generateSupplemental(projectId,caseIds);
    }

    public List<UnitTestDto> generateSupplemental(Long projectId,List<Long> caseIds){
        Project p=ensure(projectId);
        ensureCanGenerate(p);
        var targetIds=new java.util.HashSet<>(caseIds);
        var target=caseIds.stream().map(id->cases.findById(id).orElse(null)).filter(java.util.Objects::nonNull).filter(c->approvedCase(c,projectId)).toList();
        if(target.size()!=targetIds.size()) throw new InvalidProjectStatusException("Test Case bổ sung không hợp lệ.");
        ai.validateUnitTestPrerequisites(projectId, targetIds);
        startProgress(projectId, target, "Bắt đầu sinh Unit Test bổ sung cho coverage gap.");
        try {
        Map<Long,BlockedUnitTestDto> blockedCases = new java.util.concurrent.ConcurrentHashMap<>();
        var generated=generateBatches(projectId,target,blockedCases);
        List<UnitTestDto> saved=transactions.execute(status->persistSupplemental(projectId,targetIds,generated,blockedCases.isEmpty()));
        finishProgress(projectId, saved == null ? 0 : saved.size(), blockedCases, true);
        return saved;
        } catch(RuntimeException exception){
            boolean isQuota = isUsageQuotaExceeded(exception);
            int failedBatch = LlmBatchExecutor.failedBatch(exception, 0);
            String detailMsg = isQuota
                    ? " Bạn đã sử dụng hết quota LLM trong tháng này. Vui lòng nâng cấp lên gói PRO để tiếp tục sinh."
                    : " Sinh Unit Test bổ sung thất bại; xem thông báo lỗi để biết chi tiết.";
            generationProgress.fail(projectId, GenerationProgressStage.UNIT_TEST,
                    (failedBatch > 0 ? "Dừng ở batch " + failedBatch + ". " : "") + detailMsg);
            throw LlmBatchExecutor.originalFailure(exception);
        }
    }

    /**
     * Một test lỗi biên dịch hoặc không đạt khi chạy thật: tên đầy đủ của lớp test, tên method và chi tiết lỗi.
     * assertion = true khi test fail ở assertion (nghi lệch nghiệp vụ, không đưa cho AI sửa); false khi là lỗi kỹ thuật.
     */
    public record TestFailure(String className,String methodName,String detail,boolean assertion){}
    // ponytail: trần cứng để một vòng sửa không đốt quota; đưa ra cấu hình khi cần sửa nhiều test hơn.
    private static final int MAX_RUNTIME_REPAIRS = 30;
    /** Số case tối đa trong một lượt gọi AI: đủ nhỏ để output không bị cắt, đủ lớn để tiết kiệm quota. */
    private static final int RUNTIME_REPAIR_GROUP = 5;

    /**
     * Giai đoạn 2 của sandbox: đưa lỗi chạy thật cho AI để sửa đúng Unit Test đã sinh.
     * Gom các test không đạt của cùng một lớp test vào chung lượt gọi để đỡ tốn quota.
     * Chỉ thay bản cũ khi bản sửa qua được các kiểm tra như lúc sinh. Trả về bản cũ của các test đã thay để có thể khôi phục.
     */
    public List<UnitTest> repairFailing(Long projectId,List<TestFailure> failures){
        ensureCanGenerate(ensure(projectId));
        Map<String,UnitTest> generatedByMethod=new LinkedHashMap<>();
        for(TestCase testCase:approvedCases(projectId)){
            UnitTest test=units.findByTestCaseId(testCase.getId());
            if(test!=null) generatedByMethod.put(test.getPackageName()+"."+test.getTestClassName()+"#"+test.getTestMethodName(),test);
        }
        // Mỗi nhóm: các test không đạt của một lớp test, kèm lỗi của từng test.
        Map<String,List<Map.Entry<UnitTest,String>>> byClass=new LinkedHashMap<>();
        List<GeneratedUnitTestDto> localFixes=new ArrayList<>();
        List<UnitTest> localPrevious=new ArrayList<>();
        int accepted=0;
        for(TestFailure failure:failures){
            if(failure.assertion()) continue;
            // Test viết tay hoặc không còn trong CSDL thì không phải việc của bước này.
            UnitTest old=takeGeneratedTest(generatedByMethod,failure);
            if(old==null) continue;
            if(++accepted>MAX_RUNTIME_REPAIRS) break;
            // Stub thừa Mockito đã chỉ đúng dòng: xoá ở local, không tốn một lượt gọi AI.
            GeneratedUnitTestDto local=withoutUnusedStubs(projectId,old,failure.detail());
            if(local!=null){ localFixes.add(local); localPrevious.add(copy(old)); continue; }
            byClass.computeIfAbsent(failure.className(),ignored->new ArrayList<>()).add(Map.entry(old,failure.detail()));
        }
        if(!localFixes.isEmpty()){
            transactions.executeWithoutResult(status->persistBatch(projectId,localFixes));
            GenerationJobContext.log("Đã tự xoá stub Mockito thừa cho "+localFixes.size()+" test ở local, không cần gọi AI.");
        }
        // Chia đều test lỗi cho số luồng song song (tối đa RUNTIME_REPAIR_GROUP/nhóm): ít lỗi thì mỗi test một request
        // chạy song song, nhiều lỗi thì không nổ số request.
        int total=byClass.values().stream().mapToInt(List::size).sum();
        int slots=batchExecutor==null?1:Math.max(1,batchExecutor.getConcurrency());
        int groupSize=Math.max(1,Math.min(RUNTIME_REPAIR_GROUP,(total+slots-1)/slots));
        List<List<Map.Entry<UnitTest,String>>> groups=new ArrayList<>();
        for(var sameClass:byClass.values()){
            for(int start=0;start<sameClass.size();start+=groupSize){
                groups.add(List.copyOf(sameClass.subList(start,Math.min(start+groupSize,sameClass.size()))));
            }
        }
        var quotaExceeded=new java.util.concurrent.atomic.AtomicBoolean();
        // Worker chỉ đọc snapshot riêng; callback lưu tuần tự để không đua khi kiểm tra tên method trùng.
        var repaired=LlmBatchExecutor.mapOrSequential(batchExecutor,groups,quotaExceeded::get,
                group->repairGroup(projectId,group,quotaExceeded),
                (index,result)->{
                    if(!result.candidates().isEmpty()) transactions.executeWithoutResult(status->persistBatch(projectId,result.candidates()));
                });
        List<UnitTest> replaced=new ArrayList<>(localPrevious);
        for(var result:repaired) replaced.addAll(result.previous());
        return replaced;
    }

    /** Bản test đã xoá các stub Mockito báo thừa, nếu xoá được chính xác và vẫn qua kiểm tra như lúc sinh; ngược lại null. */
    private GeneratedUnitTestDto withoutUnusedStubs(Long projectId,UnitTest old,String detail){
        if(!detail.contains("UnnecessaryStubbingException")) return null;
        List<String> stubLines=detail.lines().filter(line->line.startsWith(com.greytest.service.coverage.SandboxTestRunService.UNUSED_STUB_LINE))
                .map(line->line.substring(com.greytest.service.coverage.SandboxTestRunService.UNUSED_STUB_LINE.length())).distinct().toList();
        String fixed=com.greytest.service.agent.UnitTestAutoFixer.removeUnusedStubs(old.getSourceCode(),old.getTestMethodName(),stubLines);
        if(fixed==null) return null;
        var candidate=new GeneratedUnitTestDto(old.getTestCaseId(),old.getTestClassName(),old.getTestMethodName(),old.getPackageName(),
                old.getGenerationType(),fixed);
        if(!valid(candidate,projectId)||!validJavaSource(candidate)) return null;
        // Cùng kiểm tra ngữ nghĩa như export; không qua thì để AI sửa thay vì để test biến mất khỏi lượt chạy.
        UnitTest probe=copy(old); probe.setSourceCode(fixed);
        try{
            var root=exportSourceRoot(projectId);
            if(exportProvenance==null) ai.validateExistingUnitTest(projectId,probe,root);
            else exportProvenance.inExportScope(()->{ai.validateExistingUnitTest(projectId,probe,root);return null;});
        }catch(LlmResponseException invalid){
            return null;
        }
        return candidate;
    }

    private record RepairedGroup(List<GeneratedUnitTestDto> candidates,List<UnitTest> previous){}

    private UnitTest takeGeneratedTest(Map<String,UnitTest> remaining,TestFailure failure){
        UnitTest exact=remaining.remove(failure.className()+"#"+failure.methodName());
        if(exact!=null || !(failure.detail().startsWith("COMPILATION ERROR:")
                || failure.detail().startsWith("EXPORTED TEST: "))) return exact;
        // Export có thể đổi tên lớp fixture nhưng giữ tên method, kể cả trong báo cáo Surefire.
        var matches=remaining.entrySet().stream().filter(entry->{
            UnitTest test=entry.getValue();
            String original=test.getPackageName()+"."+test.getTestClassName();
            return test.getTestMethodName().equals(failure.methodName())&&failure.className().matches(
                    java.util.regex.Pattern.quote(original)+"_(?:Fixture\\d+Test|GreyTest_[\\da-f]+(?:_\\d+)?Test)");
        }).toList();
        return matches.size()==1 ? remaining.remove(matches.get(0).getKey()) : null;
    }

    private RepairedGroup repairGroup(Long projectId,List<Map.Entry<UnitTest,String>> group,
            java.util.concurrent.atomic.AtomicBoolean quotaExceeded){
        if(quotaExceeded.get()) return new RepairedGroup(List.of(),List.of());
        StringBuilder note=new StringBuilder("The stored Unit Tests below failed to compile or failed during execution against the unchanged production source.\n"
                + "Fix only the tests: fixtures, stubs, argument values, verifications or wrong API usage that make them fail before or at the approved scenario. "
                + "Return one corrected test per case_id listed here. Keep each case_id, package, test class name and the approved inputs and expected result. "
                + "Compare the stored assertions with the approved Test Case, which outranks both the old test and the observed failure. "
                + "Correct only generated literals or interaction counts contradicted by the approved case or its fixture. "
                + "Never weaken, delete or invert an assertion required by the approved case to make a test pass. Preserve real source/oracle differences. "
                + "Stub reached getters on mocks (reflection does not stub a getter), use doThrow for void methods, "
                + "remove unused stubs without relaxing strictness, and count interactions on the entire reached path including repeated cleanup. "
                + "When a private helper throws at Objects.requireNonNull, identify the precise unstubbed call from the stack and source. "
                + "Check which object reaches the helper at every call site, including the saved replacement, not just the old entity. "
                + "Add source-required mock setup even when test_data.mocks omits it, unless the approved case explicitly requires its absence. "
                + "For Mockito Wanted but not invoked, distinguish a required missed interaction from a generated verify on an unreachable branch. "
                + "For javac 'unreported exception ... must be caught or declared', handle all checked exceptions in the test, "
                + "stubs, assertions, setup and helpers. Mail API reads can throw MessagingException or IOException. "
                + "Declare appropriate throws (JUnit permits throws Exception); use assertThrows only if the approved case expects that exception. "
                + "Never swallow the exception, omit an assertion or modify production to hide a compiler error.\n");
        Map<Long,UnitTest> oldByCase=new LinkedHashMap<>();
        for(var item:group){
            oldByCase.put(item.getKey().getTestCaseId(),item.getKey());
            note.append("\n### case_id ").append(item.getKey().getTestCaseId()).append(", method ").append(item.getKey().getTestMethodName())
                    .append("\nCompilation or runtime failure:\n").append(item.getValue())
                    // Dòng fingerprint source chỉ dùng cho export, AI không cần đọc (~500 token mỗi test).
                    .append("\nStored test source:\n").append(com.greytest.service.agent.GenerationSourceFingerprint.withoutMetadata(item.getKey().getSourceCode())).append('\n');
        }
        try{
            var candidates=ai.repairUnitTests(projectId,Set.copyOf(oldByCase.keySet()),note.toString()).unitTests().stream()
                    .filter(java.util.Objects::nonNull).filter(test->oldByCase.containsKey(test.caseId()))
                    // Giữ nguyên lớp test để bản sửa vẫn gộp vào đúng file khi export.
                    .filter(test->{UnitTest old=oldByCase.get(test.caseId());
                        return old.getTestClassName().equals(test.testClassName())&&java.util.Objects.equals(old.getPackageName(),test.packageName());})
                    .filter(test->valid(test,projectId)&&validJavaSource(test))
                    .collect(java.util.stream.Collectors.groupingBy(GeneratedUnitTestDto::caseId,LinkedHashMap::new,java.util.stream.Collectors.toList()))
                    .values().stream().filter(sameCase->sameCase.size()==1).map(sameCase->sameCase.get(0)).toList();
            // AI có thể trả case khác thứ tự; bản cũ luôn theo thứ tự nhóm đầu vào.
            var repairedIds=candidates.stream().map(GeneratedUnitTestDto::caseId).collect(java.util.stream.Collectors.toSet());
            List<UnitTest> previous=oldByCase.values().stream().filter(old->repairedIds.contains(old.getTestCaseId())).map(this::copy).toList();
            return new RepairedGroup(candidates,previous);
        }catch(LlmResponseException|UsageQuotaExceededException unrepaired){
            if(isUsageQuotaExceeded(unrepaired) && quotaExceeded.compareAndSet(false,true)){
                GenerationJobContext.markRepairQuotaExhausted();
                GenerationJobContext.log("Đã hết quota LLM; dừng AI sửa Unit Test. Các test đã đạt được giữ lại.");
            }
            // AI không trả được bản sửa hợp lệ cho nhóm này: giữ bản cũ, sang nhóm kế tiếp.
        }
        return new RepairedGroup(List.of(),List.of());
    }

    /** Trả các Unit Test về bản trước khi AI sửa, dùng khi bản sửa làm hỏng cả lượt build. */
    public void restore(Long projectId,List<UnitTest> previous){
        ensure(projectId);
        transactions.executeWithoutResult(status->{
            for(UnitTest old:previous){
                UnitTest current=units.findByTestCaseId(old.getTestCaseId());
                if(current!=null) units.delete(current);
            }
            units.flush();
            units.saveAll(previous);
        });
    }

    public int removeUncompilable(Long projectId,List<TestFailure> failures){
        ensure(projectId);
        return transactions.execute(status -> {
            Map<String,UnitTest> remaining=new LinkedHashMap<>();
            Map<Long,TestCase> approved=new LinkedHashMap<>();
            for(TestCase testCase:approvedCases(projectId)){
                approved.put(testCase.getId(),testCase);
                UnitTest test=units.findByTestCaseId(testCase.getId());
                if(test!=null) remaining.put(test.getPackageName()+"."+test.getTestClassName()+"#"+test.getTestMethodName(),test);
            }
            int removed=0;
            for(TestFailure failure:failures){
                if(failure.assertion()||!failure.detail().startsWith("COMPILATION ERROR:")) continue;
                UnitTest test=takeGeneratedTest(remaining,failure);
                if(test==null) continue;
                units.delete(test);
                TestCase testCase=approved.get(test.getTestCaseId());
                GenerationJobContext.log("Đã loại Unit Test của "+testCase.getCaseCode()+" (ID "+testCase.getId()+"): "
                        +failure.className()+"#"+failure.methodName()+". Lý do: "+failure.detail());
                removed++;
            }
            units.flush();
            return removed;
        });
    }

    private UnitTest copy(UnitTest source){
        UnitTest copy=new UnitTest();
        copy.setTestCaseId(source.getTestCaseId());copy.setTestClassName(source.getTestClassName());copy.setTestMethodName(source.getTestMethodName());
        copy.setPackageName(source.getPackageName());copy.setGenerationType(source.getGenerationType());
        copy.setExistingTestFilePath(source.getExistingTestFilePath());copy.setSourceCode(source.getSourceCode());copy.setFilePath(source.getFilePath());
        return copy;
    }

    private boolean isUsageQuotaExceeded(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof UsageQuotaExceededException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
    private void finishProgress(Long projectId,int savedCount,Map<Long,BlockedUnitTestDto> blockedCases,boolean supplemental){
        String message = "Đã lưu " + savedCount + " Unit Test" + (supplemental ? " bổ sung" : "") + ".";
        if(blockedCases.isEmpty()){
            generationProgress.completeAfterCommit(projectId, GenerationProgressStage.UNIT_TEST, "Hoàn tất: " + message);
            return;
        }
        // Case bị chặn do source khác case chưa sinh được do lỗi AI/provider: loại sau chạy lại là có thể đạt.
        var groups = blockedCases.values().stream().sorted(java.util.Comparator.comparing(BlockedUnitTestDto::caseId))
                .collect(java.util.stream.Collectors.partitioningBy(blocked -> blocked.details() != null && blocked.details().startsWith(UNFINISHED_CASE)));
        StringBuilder summary = new StringBuilder("Hoàn tất một phần: ").append(message);
        if (!groups.get(false).isEmpty()) summary.append(" Còn ").append(groups.get(false).size())
                .append(" Test Case bị chặn: ").append(caseList(groups.get(false))).append(".");
        if (!groups.get(true).isEmpty()) summary.append(" Còn ").append(groups.get(true).size())
                .append(" Test Case chưa sinh được: ").append(caseList(groups.get(true)))
                .append("; hãy dùng 'Tiếp tục sinh', case đã có Unit Test cũ sẽ giữ bản cũ nếu bản đó còn hợp lệ.");
        generationProgress.partialAfterCommit(projectId, GenerationProgressStage.UNIT_TEST,
                summary.append(" Xem nhật ký để biết lý do từng case.").toString());
    }
    private static String caseList(List<BlockedUnitTestDto> items){
        return items.stream().map(blocked -> (blocked.caseCode() == null ? "ID " + blocked.caseId() : blocked.caseCode() + " (ID " + blocked.caseId() + ")"))
                .collect(java.util.stream.Collectors.joining(", "));
    }
    private List<GeneratedUnitTestDto> generateBatches(Long projectId,List<TestCase> target,Map<Long,BlockedUnitTestDto> blockedCases){
        List<List<TestCase>> batches = caseBatches(target);
        List<Integer> batchIndexes = java.util.stream.IntStream.range(0, batches.size()).boxed().toList();
        return generateBatches(projectId, batches, batchIndexes, batches.size(), blockedCases);
    }
    private List<GeneratedUnitTestDto> generateBatches(
            Long projectId,List<List<TestCase>> batchCaseLists,List<Integer> batchIndexes,int totalBatches,
            Map<Long,BlockedUnitTestDto> blockedCases){
        List<GeneratedUnitTestDto> generated=new ArrayList<>();
        List<Set<Long>> batches=new ArrayList<>();
        for(List<TestCase> batch : batchCaseLists){
            Set<Long> ids=batch.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            batches.add(ids);
        }
        List<List<GeneratedUnitTestDto>> generatedBatches=LlmBatchExecutor.mapOrSequential(
                batchExecutor,
                batches,
                () -> generationProgress.isPaused(projectId, GenerationProgressStage.UNIT_TEST),
                ids -> {
            int currentIndex = batches.indexOf(ids);
            int batchIdx = batchIndexes.get(currentIndex);
            String summary = currentIndex < batchCaseLists.size() ? formatTestCaseBatchSummary(batchCaseLists.get(currentIndex)) : "";
            generationProgress.setStepStatus(projectId, GenerationProgressStage.UNIT_TEST, batchIdx, com.greytest.dto.GenerationProgressStepStatus.RUNNING);
            generationProgress.log(projectId, GenerationProgressStage.UNIT_TEST,
                    "Đang gọi AI sinh mã Unit Test cho batch " + (batchIdx + 1) + "/" + totalBatches + " (" + summary + ")...");
            try {
                return generateBatchWithRecovery(projectId,ids,batchIdx + 1,totalBatches,
                        new RecoveryBudget(MAX_UNIT_TEST_RECOVERY_CALLS),false,blockedCases);
            } catch (RuntimeException exception) {
                generationProgress.failStep(projectId, GenerationProgressStage.UNIT_TEST, batchIdx, exception.getMessage());
                // Lỗi output AI của riêng batch này không được làm dừng cả lượt sinh: đánh dấu các case là chưa sinh được
                // để lượt tự tiếp tục sinh xử lý. Hết quota, bị hủy hoặc lỗi khác vẫn dừng như cũ.
                if (!isolateBatchFailures || !(exception instanceof LlmResponseException failure) || isUsageQuotaExceeded(exception)
                        || Thread.currentThread().isInterrupted()) throw exception;
                ids.forEach(id -> {
                    if (blockedCases.containsKey(id)) return;
                    String code = cases.findById(id).map(TestCase::getCaseCode).orElse(null);
                    blockedCases.put(id, new BlockedUnitTestDto(id, code, UNFINISHED_CASE + failure.getMessage()));
                    ai.recordCaseFailure(projectId, id, failure); rememberOutputFailure(projectId, id, failure);
                });
                generationProgress.log(projectId, GenerationProgressStage.UNIT_TEST, "Batch " + (batchIdx + 1) + "/" + totalBatches
                        + " chưa sinh được; các batch khác vẫn tiếp tục. Có thể thử lại bằng 'Tiếp tục sinh': " + failure.getMessage());
                return List.<GeneratedUnitTestDto>of();
            }
        },
        (completedBatch,valid)-> {
            // Lưu ngay batch này vào CSDL để không mất dữ liệu nếu bị ngắt quãng giữa chừng
            try {
                transactions.execute(status -> {
                    persistBatch(projectId, valid);
                    return null;
                });
            } catch (RuntimeException exception) {
                int index = batchIndexes.get(completedBatch - 1);
                generationProgress.failStep(projectId, GenerationProgressStage.UNIT_TEST, index, exception.getMessage());
                throw exception;
            }
            int batchIdx = completedBatch <= batchIndexes.size() ? batchIndexes.get(completedBatch - 1) : -1;
            String summary = completedBatch <= batchCaseLists.size() ? formatTestCaseBatchSummary(batchCaseLists.get(completedBatch - 1)) : "";
            if (batchIdx >= 0) {
                generationProgress.advanceStep(projectId, GenerationProgressStage.UNIT_TEST, batchIdx,
                        "Batch " + (batchIdx + 1) + "/" + totalBatches + ": đã kiểm tra & lưu "
                                + valid.size() + " Unit Test hợp lệ (" + summary + ").");
            } else {
                generationProgress.advance(projectId, GenerationProgressStage.UNIT_TEST,
                        "Batch: đã kiểm tra & lưu " + valid.size() + " Unit Test hợp lệ (" + summary + ").");
            }
        });
        for(List<GeneratedUnitTestDto> valid:generatedBatches){
            generated.addAll(valid);
        }
        generationProgress.setStepStatus(projectId, GenerationProgressStage.UNIT_TEST, totalBatches, com.greytest.dto.GenerationProgressStepStatus.RUNNING);
        generationProgress.log(projectId, GenerationProgressStage.UNIT_TEST,
                "Đang chuẩn hóa tên phương thức và lưu trữ file test...");
        return uniqueMethodNames(generated,Set.of());
    }
    private record DeclinedCasesKey(Long projectId) {}

    private List<GeneratedUnitTestDto> generateBatchWithRecovery(
            Long projectId,Set<Long> requestedIds,int batchNumber,int totalBatches,
            RecoveryBudget recoveryBudget,boolean recoveryCall,Map<Long,BlockedUnitTestDto> blockedCases){
        return generateBatchWithRecovery(projectId,requestedIds,batchNumber,totalBatches,recoveryBudget,recoveryCall,blockedCases,null);
    }
    private List<GeneratedUnitTestDto> generateBatchWithRecovery(
            Long projectId,Set<Long> requestedIds,int batchNumber,int totalBatches,
            RecoveryBudget recoveryBudget,boolean recoveryCall,Map<Long,BlockedUnitTestDto> blockedCases,String previousFailure){
        // Chỉ nhớ case bị chặn trong cùng job; job mới được đánh giá lại source và Test Case.
        Map<Long,BlockedUnitTestDto> declinedInJob = GenerationJobContext.memoize(
                new DeclinedCasesKey(projectId), java.util.concurrent.ConcurrentHashMap::new);
        Set<Long> eligible = new LinkedHashSet<>();
        requestedIds.forEach(id -> {
            BlockedUnitTestDto declined = declinedInJob.get(id);
            if (declined == null) eligible.add(id);
            else blockedCases.put(id, declined);
        });
        if (eligible.size() != requestedIds.size()) {
            if (eligible.isEmpty()) return List.of();
            return generateBatchWithRecovery(projectId,eligible,batchNumber,totalBatches,
                    recoveryBudget,recoveryCall,blockedCases,previousFailure);
        }
        if(recoveryCall&&!recoveryBudget.tryConsume()){
            throw new LlmResponseException("Không thể sinh Unit Test hợp lệ cho Test Case ID " + requestedIds
                    + " vì đã hết giới hạn " + MAX_UNIT_TEST_RECOVERY_CALLS
                    + " lượt phục hồi sau khi tự chia batch.");
        }
        com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto response;
        try{
            response=previousFailure==null ? ai.generateUnitTests(projectId,requestedIds)
                    : ai.repairUnitTests(projectId,requestedIds,previousFailure);
        }catch(LlmResponseException.OutputTruncated truncated){
            if(requestedIds.size()==1) throw truncated;
            generationProgress.log(projectId,GenerationProgressStage.UNIT_TEST,
                    "Batch " + batchNumber + "/" + totalBatches + " có output AI bị cắt; đang chia nhỏ " + requestedIds.size() + " case.");
            Map<Long,GeneratedUnitTestDto> recovered=new LinkedHashMap<>();
            recoverInGroups(projectId,new ArrayList<>(requestedIds),recovered,batchNumber,totalBatches,recoveryBudget,blockedCases,null);
            return new ArrayList<>(recovered.values());
        }catch(LlmResponseException declined){
            Long declinedId=com.greytest.service.agent.GenerationResponseParser.declinedCaseId(declined);
            if(declinedId==null||!requestedIds.contains(declinedId)) throw declined;
            // AI báo không dựng được test cho một case: chỉ chặn case đó, không để cả lượt sinh dừng giữa chừng.
            String declinedCode=cases.findById(declinedId).map(TestCase::getCaseCode).orElse(null);
            var blocked = new BlockedUnitTestDto(declinedId,declinedCode,declined.getMessage());
            blockedCases.put(declinedId,blocked);
            declinedInJob.put(declinedId,blocked);
            generationProgress.log(projectId,GenerationProgressStage.UNIT_TEST,
                    "Test Case " + declinedCode + " (ID " + declinedId + ") bị chặn, các case còn lại vẫn sinh tiếp: " + declined.getMessage());
            ai.recordCaseFailure(projectId,declinedId,declined);
            List<Long> rest=requestedIds.stream().filter(id->!id.equals(declinedId)).toList();
            if(rest.isEmpty()) return List.of();
            try{
                return generateBatchWithRecovery(projectId,new LinkedHashSet<>(rest),batchNumber,totalBatches,recoveryBudget,true,blockedCases,null);
            }catch(LlmResponseException failure){
                if(Thread.currentThread().isInterrupted()) throw failure;
                // Phần còn lại vẫn hỏng: đánh dấu chưa sinh được để lượt tự tiếp tục sinh xử lý, không làm hỏng các batch khác.
                rest.forEach(id->{
                    if(blockedCases.containsKey(id)) return;
                    String code=cases.findById(id).map(TestCase::getCaseCode).orElse(null);
                    blockedCases.put(id,new BlockedUnitTestDto(id,code,UNFINISHED_CASE + failure.getMessage()));
                    ai.recordCaseFailure(projectId,id,failure); rememberOutputFailure(projectId,id,failure);
                });
                return List.of();
            }
        }
        // Metadata blocked chỉ do backend tạo; không đưa các case này vào lượt phục hồi LLM.
        for(BlockedUnitTestDto blocked : response.blockedCases() == null ? List.<BlockedUnitTestDto>of() : response.blockedCases()){
            if(blocked == null || !requestedIds.contains(blocked.caseId())) continue;
            blockedCases.put(blocked.caseId(),blocked);
            declinedInJob.put(blocked.caseId(),blocked);
            generationProgress.log(projectId,GenerationProgressStage.UNIT_TEST,
                    "Test Case " + blocked.caseCode() + " (ID " + blocked.caseId() + ") bị chặn: " + blocked.details());
            ai.recordCaseFailure(projectId,blocked.caseId(),new LlmResponseException(blocked.details(),LlmResponseException.ErrorClassification.SOURCE_ANALYSIS_UNRESOLVED));
        }
        Map<Long,List<GeneratedUnitTestDto>> candidates=response.unitTests().stream()
                .filter(java.util.Objects::nonNull)
                .filter(test->requestedIds.contains(test.caseId()))
                .filter(test->!blockedCases.containsKey(test.caseId()))
                .filter(test->valid(test,projectId)&&validJavaSource(test))
                .collect(java.util.stream.Collectors.groupingBy(
                        GeneratedUnitTestDto::caseId,LinkedHashMap::new,java.util.stream.Collectors.toList()));
        Map<Long,GeneratedUnitTestDto> accepted=new LinkedHashMap<>();
        requestedIds.forEach(id->{
            List<GeneratedUnitTestDto> matches=candidates.getOrDefault(id,List.of());
            if(matches.size()==1) accepted.put(id,matches.get(0));
        });
        List<Long> unresolved=requestedIds.stream().filter(id->!accepted.containsKey(id)&&!blockedCases.containsKey(id)).toList();
        if(unresolved.isEmpty()) return new ArrayList<>(accepted.values());
        if(requestedIds.size()==1){
            if(!recoveryCall){
                generationProgress.log(projectId,GenerationProgressStage.UNIT_TEST,
                        "Batch " + batchNumber + "/" + totalBatches + " có Unit Test thiếu hoặc hỏng cho Test Case ID "
                                + unresolved + ". Hệ thống đang thử lại riêng case này.");
                return generateBatchWithRecovery(projectId,requestedIds,batchNumber,totalBatches,recoveryBudget,true,blockedCases,response.repairNote());
            }
            Long caseId=requestedIds.iterator().next();
            throw new LlmResponseException("Không thể sinh Unit Test hợp lệ cho Test Case ID " + caseId
                    + " sau khi đã tự chia batch và thử lại.");
        }
        generationProgress.log(projectId,GenerationProgressStage.UNIT_TEST,
                "Batch " + batchNumber + "/" + totalBatches + " thiếu hoặc hỏng Unit Test cho Test Case ID "
                        + unresolved + ". Hệ thống đang tự chia nhóm và chỉ thử lại các case này.");
        recoverInGroups(projectId,unresolved,accepted,batchNumber,totalBatches,recoveryBudget,blockedCases,response.repairNote());
        return requestedIds.stream().map(accepted::get).filter(java.util.Objects::nonNull).toList();
    }
    /** Chia đôi case còn thiếu; nhóm hỏng không được làm mất test đã đạt, nó được đánh dấu để Tiếp tục sinh xử lý. */
    private void recoverInGroups(Long projectId,List<Long> ids,Map<Long,GeneratedUnitTestDto> accepted,int batchNumber,int totalBatches,
            RecoveryBudget recoveryBudget,Map<Long,BlockedUnitTestDto> blockedCases,String previousFailure){
        int middle=Math.max(1,ids.size()/2);
        List<List<Long>> groups=ids.size()==1 ? List.of(ids) : List.of(ids.subList(0,middle),ids.subList(middle,ids.size()));
        Map<List<Long>,LlmResponseException> failed=new LinkedHashMap<>();
        for(List<Long> group:groups){
            try{
                generateBatchWithRecovery(projectId,new LinkedHashSet<>(group),batchNumber,totalBatches,recoveryBudget,true,blockedCases,previousFailure)
                        .forEach(test->accepted.put(test.caseId(),test));
            }catch(LlmResponseException failure){
                // Job bị hủy thì dừng ngay, không ghi nhận như case chưa sinh được.
                if(Thread.currentThread().isInterrupted()) throw failure;
                failed.put(group,failure);
            }
        }
        if(failed.isEmpty()) return;
        // Chưa có test nào đạt thì giữ hành vi cũ: báo lỗi batch để người dùng thấy rõ.
        if(accepted.isEmpty()) throw failed.values().iterator().next();
        failed.forEach((group,failure)->group.forEach(id->{
            if (blockedCases.containsKey(id)) return;
            String code=cases.findById(id).map(TestCase::getCaseCode).orElse(null);
            blockedCases.put(id,new BlockedUnitTestDto(id,code,UNFINISHED_CASE + failure.getMessage()));
            generationProgress.log(projectId,GenerationProgressStage.UNIT_TEST,
                    "Test Case " + code + " (ID " + id + ") chưa sinh được và sẽ được xử lý khi Tiếp tục sinh: " + failure.getMessage());
            ai.recordCaseFailure(projectId,id,failure); rememberOutputFailure(projectId,id,failure);
        }));
    }
    /** Chỉ lỗi output AI mới được nhớ; lỗi provider/hạ tầng luôn được thử lại ở lượt sau. */
    private void rememberOutputFailure(Long projectId,Long caseId,LlmResponseException failure){
        if(failure.getClassification()!=LlmResponseException.ErrorClassification.LLM_OUTPUT_INVALID) return;
        roundOutputFailures.computeIfAbsent(projectId,ignored->new java.util.concurrent.ConcurrentHashMap<>())
                .put(caseId,String.valueOf(failure.getMessage()));
    }

    /** Kết thúc một lượt sinh: case có cùng lỗi output ở lượt này và lượt trước thì lượt sau không gọi AI nữa. */
    private void closeOutputFailureRound(Long projectId){
        Map<Long,String> current=roundOutputFailures.remove(projectId);
        if(current==null) current=Map.of();
        Map<Long,String> previous=lastOutputFailures.getOrDefault(projectId,Map.of());
        for(var entry:current.entrySet()){
            if(entry.getValue().equals(previous.get(entry.getKey())))
                repeatedOutputFailures.computeIfAbsent(projectId,ignored->new java.util.concurrent.ConcurrentHashMap<>())
                        .put(entry.getKey(),entry.getValue());
        }
        lastOutputFailures.put(projectId,Map.copyOf(current));
    }

    private void forgetOutputFailures(Long projectId){
        roundOutputFailures.remove(projectId);
        lastOutputFailures.remove(projectId);
        repeatedOutputFailures.remove(projectId);
    }

    private static com.github.javaparser.JavaParser javaParser() {
        return new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21));
    }

    private boolean validJavaSource(GeneratedUnitTestDto test){
        if(test.sourceCode()==null||test.sourceCode().isBlank()) return false;
        try{
            var configuration=new com.github.javaparser.ParserConfiguration()
                    .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21);
            var result=new com.github.javaparser.JavaParser(configuration).parse(test.sourceCode());
            var unit=result.getResult().filter(ignored->result.isSuccessful()).orElse(null);
            if(unit==null) return false;
            return unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                    .filter(type->test.testClassName().equals(type.getNameAsString()))
                    .flatMap(type->type.getMethodsByName(test.testMethodName()).stream())
                    .anyMatch(method->method.getAnnotations().stream()
                            .anyMatch(annotation->annotation.getNameAsString().equals("Test")
                                    || annotation.getNameAsString().endsWith(".Test")));
        }catch(RuntimeException exception){
            return false;
        }
    }
    private static final class RecoveryBudget{
        private int remainingCalls;
        private RecoveryBudget(int remainingCalls){this.remainingCalls=remainingCalls;}
        private boolean tryConsume(){
            if(remainingCalls<=0) return false;
            remainingCalls--;
            return true;
        }
    }
    private void startProgress(Long projectId,List<TestCase> target,String message){
        List<List<TestCase>> batches = caseBatches(target);
        List<String> stepLabels = progressLabels(batches);
        generationProgress.start(projectId, GenerationProgressStage.UNIT_TEST, stepLabels,
                message + " Tổng cộng " + target.size() + " Test Case trong " + batches.size() + " batch.");
    }
    /** Khi bật, case của cùng class được gộp tới giới hạn mỗi request; output bị cắt vẫn tự chia nhỏ như batch thường. */
    private Long testedClassId(TestCase testCase) {
        return plans.findById(testCase.getTestPlanId())
                .map(TestPlan::getBusinessRuleId).flatMap(rules::findById)
                .map(com.greytest.entity.BusinessRule::getMethodId).flatMap(methods::findById)
                .map(com.greytest.entity.JavaMethod::getClassId).orElse(-1L);
    }
    private List<List<TestCase>> caseBatches(List<TestCase> testCases) {
        if (unitTestPerService && rules != null && methods != null) {
            List<List<TestCase>> batches = new ArrayList<>();
            int limit = Math.max(1, unitTestPerServiceMaxCases);
            for (List<TestCase> group : testCases.stream().collect(java.util.stream.Collectors.groupingBy(
                    this::testedClassId, LinkedHashMap::new, java.util.stream.Collectors.toList())).values()) {
                // Chia đều theo số batch tối thiểu (20 case, giới hạn 8 → 7+7+6 thay vì 8+8+4).
                int parts = (group.size() + limit - 1) / limit;
                int size = (group.size() + parts - 1) / parts;
                for (int start = 0; start < group.size(); start += size) {
                    batches.add(group.subList(start, Math.min(start + size, group.size())));
                }
            }
            // Batch lớn (lâu nhất) chạy trước để batch cuối cùng không kéo dài cả bước; sort ổn định giữ thứ tự source.
            batches.sort(java.util.Comparator.comparingInt((List<TestCase> batch) -> batch.size()).reversed());
            return batches;
        }
        List<List<TestCase>> batches = new ArrayList<>();
        for (int start = 0; start < testCases.size(); start += GenerationContextBuilder.MAX_UNIT_TEST_CASES) {
            batches.add(testCases.subList(start, Math.min(start + GenerationContextBuilder.MAX_UNIT_TEST_CASES, testCases.size())));
        }
        return batches;
    }
    private List<String> progressLabels(List<List<TestCase>> batches) {
        List<String> stepLabels = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            stepLabels.add("Sinh Unit Test - batch " + (i + 1) + "/" + batches.size() + ": " + formatTestCaseBatchSummary(batches.get(i)));
        }
        stepLabels.add("Kiểm tra biên dịch & lưu Unit Test vào CSDL");
        return stepLabels;
    }
    private String formatTestCaseBatchSummary(List<TestCase> batch) {
        if (batch == null || batch.isEmpty()) return "0 case";
        List<String> codes = batch.stream()
                .map(TestCase::getCaseCode)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (codes.isEmpty()) return batch.size() + " case";
        return String.join(", ", codes);
    }
    private int batchCount(int itemCount){return (itemCount+GenerationContextBuilder.MAX_UNIT_TEST_CASES-1)/GenerationContextBuilder.MAX_UNIT_TEST_CASES;}
    private List<GeneratedUnitTestDto> uniqueMethodNames(List<GeneratedUnitTestDto> generated,Set<String> existingKeys){
        Set<String> used=new java.util.HashSet<>(existingKeys);
        List<GeneratedUnitTestDto> unique=new ArrayList<>();
        for(GeneratedUnitTestDto test:generated){
            String name=test.testMethodName();
            String key=methodKey(test);
            if(!used.add(key)){
                name=uniqueName(test,used);
                key=test.packageName()+"\n"+test.testClassName()+"\n"+name;
                used.add(key);
            }
            unique.add(new GeneratedUnitTestDto(test.caseId(),test.testClassName(),name,test.packageName(),test.generationType(),renameMethod(test.sourceCode(),test.testClassName(),test.testMethodName(),name)));
        }
        return unique;
    }
    private String uniqueName(GeneratedUnitTestDto test,Set<String> used){
        String base=test.testMethodName()+"Case"+test.caseId();
        String name=base;
        int suffix=2;
        while(used.contains(test.packageName()+"\n"+test.testClassName()+"\n"+name)) name=base+"_"+suffix++;
        return name;
    }
    private String renameMethod(String source,String testClassName,String oldName,String newName){
        if(oldName.equals(newName)) return source;
        try{
            var result=javaParser().parse(source);
            var unit=result.getResult().filter(ignored->result.isSuccessful()).orElse(null);
            if(unit!=null){
                var targetType=unit.getTypes().stream()
                        .filter(type->testClassName.equals(type.getNameAsString())).findFirst().orElse(null);
                if(targetType!=null){
                    var method=junitTestMethods(unit,importsJunitTest(unit)).stream()
                            .filter(candidate->targetType.getMethods().contains(candidate))
                            .filter(candidate->oldName.equals(candidate.getNameAsString()))
                            .findFirst().orElse(null);
                    if(method!=null){method.setName(newName);return unit.toString();}
                }
            }
        }catch(Exception ignored){
            // Nếu source AI chưa parse được, vẫn đổi tên bằng regex tối thiểu để user có file sửa tiếp.
        }
        return source.replaceFirst("(?<![A-Za-z0-9_$])"+java.util.regex.Pattern.quote(oldName)+"\\s*\\(",java.util.regex.Matcher.quoteReplacement(newName+"("));
    }
    private String methodKey(GeneratedUnitTestDto test){return test.packageName()+"\n"+test.testClassName()+"\n"+test.testMethodName();}
    private String methodKey(UnitTest test){return test.getPackageName()+"\n"+test.getTestClassName()+"\n"+test.getTestMethodName();}
    private List<UnitTestDto> persistGenerated(Long projectId,ServiceScopeResolver.ServiceScope scope,Set<Long> expectedIds,List<GeneratedUnitTestDto> generated,boolean resume){
        Project p=lockedProject(projectId);
        if(scope==null)ensureCanGenerate(p);
        var approved=scope==null?approvedCases(projectId):approvedCases(projectId,scope);
        if(!resume){
            var currentIds=approved.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet());
            if(!currentIds.equals(expectedIds)) throw new InvalidProjectStatusException("Test Case đã thay đổi trong lúc sinh Unit Test. Hãy thử lại.");
        }
        var saved=units.saveAll(generated.stream().map(this::from).toList());
        p.setStatus(ProjectStatus.TEST_GENERATED); projects.save(p);
        return saved.stream().map(this::dto).toList();
    }
    private List<UnitTest> persistBatch(Long projectId, List<GeneratedUnitTestDto> valid) {
        if (valid == null || valid.isEmpty()) return List.of();
        var replacedCaseIds = valid.stream().map(GeneratedUnitTestDto::caseId).collect(java.util.stream.Collectors.toSet());
        // UT cũ của chính case đang thay không được coi là trùng tên với UT mới.
        var existingKeys = approvedCases(projectId).stream()
                .filter(testCase -> !replacedCaseIds.contains(testCase.getId()))
                .map(testCase -> units.findByTestCaseId(testCase.getId()))
                .filter(java.util.Objects::nonNull)
                .map(this::methodKey)
                .collect(java.util.stream.Collectors.toSet());
        var unique = uniqueMethodNames(valid, existingKeys);
        for (var x : unique) {
            UnitTest old = units.findByTestCaseId(x.caseId());
            if (old != null) {
                units.delete(old);
            }
            TestCase tc = cases.findById(x.caseId()).orElse(null);
            if (tc != null) {
                tc.setStatus(ReviewStatus.APPROVED);
                // Sinh UT không sửa TC; giữ dấu vết oracle đã được người dùng chỉnh/duyệt.
                cases.save(tc);
            }
        }
        var saved = units.saveAll(unique.stream().map(this::from).toList());
        units.flush();
        return saved;
    }
    private List<UnitTestDto> persistSupplemental(Long projectId,Set<Long> expectedIds,List<GeneratedUnitTestDto> generated,boolean complete){
        Project p=lockedProject(projectId);
        ensureCanGenerate(p);
        var current=expectedIds.stream().map(id->cases.findById(id).orElse(null)).filter(java.util.Objects::nonNull).filter(c->approvedCase(c,projectId)).toList();
        if(current.size()!=expectedIds.size()) throw new InvalidProjectStatusException("Test Case bổ sung đã thay đổi trong lúc sinh Unit Test. Hãy thử lại.");
        var existingKeys=approvedCases(projectId).stream().filter(testCase->!expectedIds.contains(testCase.getId()))
                .map(testCase->units.findByTestCaseId(testCase.getId())).filter(java.util.Objects::nonNull)
                .map(this::methodKey).collect(java.util.stream.Collectors.toSet());
        generated=uniqueMethodNames(generated,existingKeys);
        var saved=units.saveAll(generated.stream().map(x->from(x,"SUPPLEMENT_EXISTING_TEST")).toList());
        if(complete){p.setStatus(ProjectStatus.TEST_GENERATED); projects.save(p);}
        else if(p.getStatus()==ProjectStatus.TEST_GENERATED
                && current.stream().anyMatch(testCase->units.findByTestCaseId(testCase.getId())==null)){
            p.setStatus(ProjectStatus.CASE_APPROVED);
            projects.save(p);
        }
        return saved.stream().map(this::dto).toList();
    }
    private void ensureCanGenerate(Project p){if(!Set.of(ProjectStatus.CASE_PENDING_REVIEW, ProjectStatus.CASE_APPROVED, ProjectStatus.TEST_GENERATED, ProjectStatus.COVERAGE_ANALYZED, ProjectStatus.COMPLETED).contains(p.getStatus())) throw new InvalidProjectStatusException("Chỉ sinh Unit Test sau khi Test Case đã có.");}
    private List<TestCase> approvedCases(Long projectId){return cases.findByProjectId(projectId).stream().filter(c->approvedCase(c,projectId)).sorted(java.util.Comparator.comparing(TestCase::getId)).toList();}
    private Project lockedProject(Long id){return projects.findByIdForUpdate(id).orElseThrow(()->new ProjectNotFoundException(id));}
    private List<TestCase> approvedCases(Long projectId,ServiceScopeResolver.ServiceScope scope){return scopedArtifacts.cases(projectId,scope).stream().filter(c->approvedCase(c,projectId)).sorted(java.util.Comparator.comparing(TestCase::getId)).toList();}
    private boolean valid(GeneratedUnitTestDto x,Long projectId){if(x==null)return false; TestCase c=cases.findById(x.caseId()).orElse(null); return c!=null&&approvedCase(c,projectId)&&x.sourceCode()!=null&&!x.sourceCode().isBlank()&&validIdentifier(x.testClassName())&&validIdentifier(x.testMethodName())&&validPackage(x.packageName())&&containsExactlyOneRequestedTest(x)&&hasInitializedReferencedFixtures(x);}
    /** Chuẩn hóa source AI trả về cả class nhiều test thành đúng test case đang xử lý. */
    private GeneratedUnitTestDto normalizeCandidate(GeneratedUnitTestDto candidate){
        if(candidate==null||candidate.sourceCode()==null||candidate.sourceCode().isBlank())return candidate;
        try{
            var result=javaParser().parse(candidate.sourceCode());
            var unit=result.getResult().filter(ignored->result.isSuccessful()).orElse(null);
            if(unit==null)return candidate;
            var targetType=unit.getTypes().stream()
                    .filter(type->candidate.testClassName().equals(type.getNameAsString())).findFirst().orElse(null);
            if(targetType==null)return candidate;
            var testMethods=junitTestMethods(unit,importsJunitTest(unit));
            if(testMethods.size()<=1)return candidate;
            var requested=testMethods.stream()
                    .filter(method->candidate.testMethodName().equals(method.getNameAsString()))
                    .findFirst().orElse(null);
            if(requested==null||!targetType.getMethods().contains(requested))return candidate;
            testMethods.stream().filter(method->method!=requested).forEach(method->method.remove());
            return new GeneratedUnitTestDto(candidate.caseId(),candidate.testClassName(),candidate.testMethodName(),
                    candidate.packageName(),candidate.generationType(),unit.toString());
        }catch(Exception ignored){
            return candidate;
        }
    }

    /**
     * Từ chối test dùng fixture object chưa được khởi tạo, tránh NPE runtime sau khi user dán source.
     */
    private boolean hasInitializedReferencedFixtures(GeneratedUnitTestDto test) {
        try {
            var parsed = javaParser().parse(test.sourceCode());
            var unit = parsed.getResult().filter(ignored -> parsed.isSuccessful()).orElse(null);
            if (unit == null) return false;
            var targetType = unit.getTypes().stream()
                    .filter(type -> test.testClassName().equals(type.getNameAsString()))
                    .findFirst().orElse(null);
            if (targetType == null) return false;
            var testMethod = junitTestMethods(unit, importsJunitTest(unit)).stream()
                    .filter(method -> targetType.getMethods().contains(method))
                    .filter(method -> test.testMethodName().equals(method.getNameAsString()))
                    .findFirst().orElse(null);
            if (testMethod == null) return false;

            Set<String> candidates = new java.util.HashSet<>();
            for (com.github.javaparser.ast.body.FieldDeclaration field : targetType.getFields()) {
                if (isManagedFixture(field)) continue;
                field.getVariables().stream()
                        .filter(variable -> !variable.getType().isPrimitiveType())
                        .filter(variable -> variable.getInitializer().map(initializer -> initializer.isNullLiteralExpr()).orElse(true))
                        .map(com.github.javaparser.ast.body.VariableDeclarator::getNameAsString)
                        .forEach(candidates::add);
            }
            if (candidates.isEmpty()) return true;

            Set<String> initializedInLifecycle = new java.util.HashSet<>();
            targetType.getConstructors().forEach(constructor ->
                    collectAssignedFields(constructor, candidates, initializedInLifecycle));
            targetType.getMethods().stream()
                    .filter(this::isLifecycleMethod)
                    .forEach(method -> collectAssignedFields(method, candidates, initializedInLifecycle));
            targetType.getMembers().stream()
                    .filter(member -> member instanceof com.github.javaparser.ast.body.InitializerDeclaration)
                    .forEach(member -> collectAssignedFields(member, candidates, initializedInLifecycle));

            for (String fieldName : candidates) {
                if (!referencesField(testMethod, fieldName)) continue;
                if (initializedInLifecycle.contains(fieldName)) continue;
                var firstUse = firstFieldUse(testMethod, fieldName);
                var firstAssignment = firstFieldAssignment(testMethod, fieldName);
                if (firstUse.isEmpty() || firstAssignment.isEmpty()
                        || !isBefore(firstAssignment.get(), firstUse.get())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void collectAssignedFields(com.github.javaparser.ast.Node node,
            Set<String> candidates, Set<String> assigned) {
        node.findAll(com.github.javaparser.ast.expr.AssignExpr.class).forEach(assignment -> {
            if (!isUnconditionalAssignment(assignment, node)) return;
            String name = assignedFieldName(assignment.getTarget());
            if (name == null || !candidates.contains(name)
                    || assignment.getOperator() != com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN) {
                return;
            }
            if (assignment.getTarget().isNameExpr()) {
                var owner = assignment.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class).orElse(null);
                if (owner != null && isShadowedAt(
                        assignment.getTarget().asNameExpr(), owner, name)) {
                    return;
                }
                var constructor = assignment.findAncestor(
                        com.github.javaparser.ast.body.ConstructorDeclaration.class).orElse(null);
                var initializer = assignment.findAncestor(
                        com.github.javaparser.ast.body.InitializerDeclaration.class).orElse(null);
                if (constructor != null || initializer != null) {
                    return;
                }
            }
            if (assignment.getValue().isNullLiteralExpr()) return;
            assigned.add(name);
        });
    }

    private boolean isLifecycleMethod(com.github.javaparser.ast.body.MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getNameAsString())
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .anyMatch(name -> switch (name) {
                    case "BeforeEach", "BeforeAll", "Before", "BeforeClass" -> true;
                    default -> false;
                });
    }

    private boolean referencesField(
            com.github.javaparser.ast.body.MethodDeclaration method, String fieldName) {
        return firstFieldUse(method, fieldName).isPresent();
    }

    private java.util.Optional<com.github.javaparser.ast.Node> firstFieldUse(
            com.github.javaparser.ast.body.MethodDeclaration method, String fieldName) {
        List<com.github.javaparser.ast.Node> uses = new ArrayList<>();
        method.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .filter(name -> fieldName.equals(name.getNameAsString()))
                .filter(name -> !isShadowedAt(name, method, fieldName))
                .filter(name -> !isWriteOnlyAssignmentTarget(name))
                .forEach(uses::add);
        method.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                .filter(field -> field.getScope().isThisExpr())
                .filter(field -> fieldName.equals(field.getNameAsString()))
                .filter(field -> !isWriteOnlyAssignmentTarget(field))
                .forEach(uses::add);
        return uses.stream().min(this::comparePosition);
    }

    private java.util.Optional<com.github.javaparser.ast.Node> firstFieldAssignment(
            com.github.javaparser.ast.body.MethodDeclaration method, String fieldName) {
        return method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .filter(assignment -> isFieldAssignment(assignment, method, fieldName))
                .filter(assignment -> assignment.getOperator() == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN)
                .map(assignment -> (com.github.javaparser.ast.Node) assignment)
                .min(this::comparePosition);
    }

    private boolean isFieldAssignment(
            com.github.javaparser.ast.expr.AssignExpr assignment,
            com.github.javaparser.ast.body.MethodDeclaration method,
            String fieldName) {
        var target = assignment.getTarget();
        if (target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()) {
            return fieldName.equals(target.asFieldAccessExpr().getNameAsString())
                    && isUnconditionalAssignment(assignment, method)
                    && !assignment.getValue().isNullLiteralExpr();
        }
        return target.isNameExpr()
                && fieldName.equals(target.asNameExpr().getNameAsString())
                && isUnconditionalAssignment(assignment, method)
                && !isShadowedAt(target.asNameExpr(), method, fieldName)
                && !assignment.getValue().isNullLiteralExpr();
    }

    private boolean isUnconditionalAssignment(
            com.github.javaparser.ast.expr.AssignExpr assignment,
            com.github.javaparser.ast.Node owner) {
        if (assignment.getParentNode()
                .filter(com.github.javaparser.ast.stmt.ExpressionStmt.class::isInstance)
                .isEmpty()) {
            return false;
        }
        var current = assignment.getParentNode().orElse(null);
        while (current != null && current != owner) {
            if (current instanceof com.github.javaparser.ast.expr.LambdaExpr
                    || current instanceof com.github.javaparser.ast.stmt.IfStmt
                    || current instanceof com.github.javaparser.ast.stmt.SwitchEntry
                    || current instanceof com.github.javaparser.ast.stmt.ForStmt
                    || current instanceof com.github.javaparser.ast.stmt.ForEachStmt
                    || current instanceof com.github.javaparser.ast.stmt.WhileStmt
                    || current instanceof com.github.javaparser.ast.stmt.DoStmt
                    || current instanceof com.github.javaparser.ast.stmt.TryStmt
                    || current instanceof com.github.javaparser.ast.stmt.CatchClause
                    || current instanceof com.github.javaparser.ast.expr.ConditionalExpr
                    || current instanceof com.github.javaparser.ast.body.MethodDeclaration
                    || current instanceof com.github.javaparser.ast.body.ConstructorDeclaration
                    || current instanceof com.github.javaparser.ast.body.InitializerDeclaration) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return current == owner;
    }
    private boolean isShadowedAt(
            com.github.javaparser.ast.expr.NameExpr reference,
            com.github.javaparser.ast.body.MethodDeclaration method,
            String fieldName) {
        if (method.getParameters().stream()
                .anyMatch(parameter -> fieldName.equals(parameter.getNameAsString()))) {
            return true;
        }
        for (com.github.javaparser.ast.body.VariableDeclarator variable :
                method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            if (!fieldName.equals(variable.getNameAsString())
                    || !isBefore(variable, reference)) continue;
            com.github.javaparser.ast.Node declarationScope = variableScope(variable, method);
            com.github.javaparser.ast.Node referenceScope = reference.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class).map(block -> (com.github.javaparser.ast.Node) block).orElse(method);
            if (isAncestorOrSame(declarationScope, referenceScope)) return true;
        }
        var current = reference.getParentNode().orElse(null);
        while (current != null && current != method) {
            if (current instanceof com.github.javaparser.ast.expr.LambdaExpr lambda
                    && lambda.getParameters().stream()
                            .anyMatch(parameter -> fieldName.equals(parameter.getNameAsString()))) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private com.github.javaparser.ast.Node variableScope(
            com.github.javaparser.ast.body.VariableDeclarator variable,
            com.github.javaparser.ast.body.MethodDeclaration method) {
        var enhancedFor = variable.findAncestor(com.github.javaparser.ast.stmt.ForEachStmt.class).orElse(null);
        if (enhancedFor != null) return enhancedFor;
        var classicFor = variable.findAncestor(com.github.javaparser.ast.stmt.ForStmt.class).orElse(null);
        if (classicFor != null) return classicFor;
        return variable.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class)
                .map(block -> (com.github.javaparser.ast.Node) block).orElse(method);
    }

    private boolean isAncestorOrSame(
            com.github.javaparser.ast.Node ancestor, com.github.javaparser.ast.Node node) {
        var current = node;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private String assignedFieldName(com.github.javaparser.ast.expr.Expression target) {
        if (target.isNameExpr()) return target.asNameExpr().getNameAsString();
        if (target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()) {
            return target.asFieldAccessExpr().getNameAsString();
        }
        return null;
    }

    private boolean isWriteOnlyAssignmentTarget(com.github.javaparser.ast.Node node) {
        return node.getParentNode()
                .filter(com.github.javaparser.ast.expr.AssignExpr.class::isInstance)
                .map(com.github.javaparser.ast.expr.AssignExpr.class::cast)
                .filter(assignment -> assignment.getTarget().equals(node))
                .map(assignment -> assignment.getOperator()
                        == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN)
                .orElse(false);
    }

    private int comparePosition(com.github.javaparser.ast.Node left, com.github.javaparser.ast.Node right) {
        var leftPosition = left.getBegin().orElse(null);
        var rightPosition = right.getBegin().orElse(null);
        if (leftPosition == null || rightPosition == null) return 0;
        int line = Integer.compare(leftPosition.line, rightPosition.line);
        return line != 0 ? line : Integer.compare(leftPosition.column, rightPosition.column);
    }

    private boolean isBefore(com.github.javaparser.ast.Node left, com.github.javaparser.ast.Node right) {
        return comparePosition(left, right) < 0;
    }

    private boolean isManagedFixture(com.github.javaparser.ast.body.FieldDeclaration field) {
        return field.getAnnotations().stream()
                .map(annotation -> annotation.getNameAsString())
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .anyMatch(name -> switch (name) {
                    case "Mock", "InjectMocks", "Spy", "Captor", "Autowired", "Inject", "Resource" -> true;
                    default -> false;
                });
    }
    private boolean containsExactlyOneRequestedTest(GeneratedUnitTestDto test){
        try{
            var result=javaParser().parse(test.sourceCode());
            var unit=result.getResult().filter(ignored->result.isSuccessful()).orElse(null);
            if(unit==null)return false;
            var packageName=unit.getPackageDeclaration()
                    .map(declaration->declaration.getNameAsString()).orElse("");
            if(!test.packageName().equals(packageName))return false;
            var targetType=unit.getTypes().stream()
                    .filter(type->test.testClassName().equals(type.getNameAsString())).findFirst().orElse(null);
            if(targetType==null)return false;
            var testMethods=junitTestMethods(unit,importsJunitTest(unit));
            return testMethods.size()==1
                    &&targetType.getMethods().contains(testMethods.get(0))
                    &&test.testMethodName().equals(testMethods.get(0).getNameAsString());
        }catch(Exception ignored){
            return false;
        }
    }

    private boolean importsJunitTest(com.github.javaparser.ast.CompilationUnit unit){
        return unit.getImports().stream().anyMatch(importDeclaration->
                !importDeclaration.isStatic()&&(
                        "org.junit.Test".equals(importDeclaration.getNameAsString())
                        ||"org.junit.jupiter.api.Test".equals(importDeclaration.getNameAsString())
                        ||(importDeclaration.isAsterisk()
                                &&("org.junit".equals(importDeclaration.getNameAsString())
                                        ||"org.junit.jupiter.api".equals(importDeclaration.getNameAsString())))));
    }

    private List<com.github.javaparser.ast.body.MethodDeclaration> junitTestMethods(
            com.github.javaparser.ast.CompilationUnit unit,boolean imported){
        return unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .filter(method->method.getAnnotations().stream().anyMatch(annotation->{
                    String name=annotation.getNameAsString();
                    return "org.junit.Test".equals(name)
                            ||"org.junit.jupiter.api.Test".equals(name)
                            ||(imported&&"Test".equals(name));
                })).toList();
    }
    private boolean validIdentifier(String value){return value!=null&&javax.lang.model.SourceVersion.isIdentifier(value)&&!javax.lang.model.SourceVersion.isKeyword(value);}
    private boolean validPackage(String value){return value!=null&&!value.isBlank()&&java.util.Arrays.stream(value.split("\\.",-1)).allMatch(this::validIdentifier);}
    private boolean approvedCase(TestCase c,Long projectId){TestPlan p=plans.findById(c.getTestPlanId()).orElse(null); return p!=null&&projectId.equals(p.getProjectId())&&p.getStatus()==ReviewStatus.APPROVED&&c.getStatus()==ReviewStatus.APPROVED;}
    private UnitTest from(GeneratedUnitTestDto x){UnitTest u=new UnitTest();u.setTestCaseId(x.caseId());u.setTestClassName(x.testClassName());u.setTestMethodName(x.testMethodName());u.setPackageName(x.packageName());u.setGenerationType(x.generationType());u.setSourceCode(withTraceComment(x));u.setFilePath("src/test/java/"+x.packageName().replace('.','/')+"/"+x.testClassName()+".java"); return u;}
    private String withTraceComment(GeneratedUnitTestDto x){
        String source = sanitizeSourceCode(x.sourceCode());
        if(source.contains("// GreyTest trace:")) return source;
        TestCase testCase=cases.findById(x.caseId()).orElse(null);
        if(testCase==null) return source;
        String trace="// GreyTest trace: "+testCase.getCaseCode()+" | "+testCase.getTraceSource();
        return trace+"\n"+source;
    }
    private String sanitizeSourceCode(String source){
        if(source==null) return "";
        String normalized = source.startsWith("\uFEFF") ? source.substring(1) : source;
        return normalized.replace("org.mockito.Matchers", "org.mockito.ArgumentMatchers");
    }
    private UnitTest from(GeneratedUnitTestDto x,String generationType){UnitTest u=from(x);u.setGenerationType(generationType);return u;}
    /** Gộp unit test theo test class thành file hoàn chỉnh như trong project thật. */
    @Transactional(readOnly=true) public List<com.greytest.dto.UnitTestFileDto> listFiles(Long projectId){
        ensure(projectId);
        var approved=cases.findByProjectId(projectId).stream().filter(c->approvedCase(c,projectId)).toList();
        var codeByCaseId=approved.stream().collect(java.util.stream.Collectors.toMap(TestCase::getId,TestCase::getCaseCode));
        var tests=approved.stream().map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).toList();
        var exportTests=validatedForListing(projectId,approved,tests);
        return files.mergeByClass(exportTests).stream()
                .map(f->new com.greytest.dto.UnitTestFileDto(f.filePath(),f.testClassName(),f.packageName(),f.caseIds().size(),
                        f.caseIds().stream().map(codeByCaseId::get).filter(java.util.Objects::nonNull).toList(),com.greytest.service.agent.GenerationSourceFingerprint.withoutMetadata(f.sourceCode())))
                .toList();
    }
    @Transactional(readOnly=true) public List<com.greytest.dto.UnitTestFileDto> listFiles(Long projectId,String servicePath){
        ensure(projectId);
        var approved=approvedCases(projectId,scopeResolver.resolve(projectId,servicePath));
        var codeByCaseId=approved.stream().collect(java.util.stream.Collectors.toMap(TestCase::getId,TestCase::getCaseCode));
        var tests=approved.stream().map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).toList();
        var exportTests=validatedForListing(projectId,approved,tests);
        return files.mergeByClass(exportTests).stream()
                .map(f->new com.greytest.dto.UnitTestFileDto(f.filePath(),f.testClassName(),f.packageName(),f.caseIds().size(),
                        f.caseIds().stream().map(codeByCaseId::get).filter(java.util.Objects::nonNull).toList(),com.greytest.service.agent.GenerationSourceFingerprint.withoutMetadata(f.sourceCode())))
                .toList();
    }

    /** Gói export cùng danh sách case bị bỏ để giao diện báo rõ phần thiếu. */
    public record ExportArchive(byte[] content,List<String> skippedCaseCodes){}

    /** Đóng gói toàn bộ file test đã gộp thành ZIP để user tải về chạy JaCoCo local. */
    @Transactional(readOnly=true) public byte[] zipFiles(Long projectId){
        return exportArchive(projectId).content();
    }

    @Transactional(readOnly=true) public byte[] zipFiles(Long projectId,String servicePath){
        return exportArchive(projectId,servicePath).content();
    }

    @Transactional(readOnly=true) public ExportArchive exportArchive(Long projectId){
        ensure(projectId);
        return buildArchive(projectId,cases.findByProjectId(projectId).stream().filter(c->approvedCase(c,projectId)).toList());
    }

    @Transactional(readOnly=true) public ExportArchive exportArchive(Long projectId,String servicePath){
        ensure(projectId);
        return buildArchive(projectId,approvedCases(projectId,scopeResolver.resolve(projectId,servicePath)));
    }

    @Transactional(readOnly=true) public ExportArchive exportSandboxArchive(Long projectId,String servicePath){
        ensure(projectId);
        var approved=approvedCases(projectId,scopeResolver.resolve(projectId,servicePath));
        if(approved.stream().noneMatch(testCase->units.findByTestCaseId(testCase.getId())!=null))
            return new ExportArchive(files.createEmptySandboxArchive(),List.of());
        return buildArchive(projectId,approved);
    }

    /** Test đã lưu không còn qua validator thì không xuất ra, vì biết trước sẽ fail; bản trong database vẫn giữ để sinh lại. */
    private String exportKey(Long projectId,List<TestCase> approved){
        return projectId+":"+com.greytest.service.agent.GenerationSourceFingerprint.hash(approved.stream().map(c->String.valueOf(c.getId())).collect(java.util.stream.Collectors.joining(",")));
    }

    /** Null nghĩa là không đủ dữ liệu để chắc kết quả cũ còn đúng, nên phải kiểm tra lại. */
    private String exportFingerprint(Long projectId,List<TestCase> approved,List<UnitTest> tests){
        if(rules==null||exportProvenance==null) return null;
        try{
            StringBuilder input=new StringBuilder();
            for(UnitTest t:tests) input.append(java.util.Arrays.asList(t.getId(),t.getTestCaseId(),t.getTestClassName(),t.getTestMethodName(),t.getPackageName(),t.getFilePath(),t.getSourceCode())).append('\n');
            for(TestCase c:approved) input.append(java.util.Arrays.asList(c.getId(),c.getCaseCode(),c.getTestPlanId(),c.getDescription(),c.getPreconditions(),c.getTestData(),c.getExpectedResult(),c.getStatus(),c.getIsModified())).append('\n');
            for(TestPlan p:plans.findByProjectId(projectId)) input.append(java.util.Arrays.asList(p.getId(),p.getBusinessRuleId(),p.getTitle(),p.getDescription(),p.getStatus())).append('\n');
            for(var r:rules.findByProjectId(projectId)) input.append(java.util.Arrays.asList(r.getId(),r.getMethodId(),r.getDescription(),r.getReviewNote(),r.getStatus())).append('\n');
            var root=exportSourceRoot(projectId);
            if(root!=null) input.append(com.greytest.service.agent.PersistentSourceAnalysisService.contentDigest(root));
            return com.greytest.service.agent.GenerationSourceFingerprint.hash(input.toString());
        }catch(RuntimeException unavailable){
            return null;
        }
    }

    private List<UnitTest> validatedForListing(Long projectId,List<TestCase> approved,List<UnitTest> tests){
        if(exportProvenance==null) return tests;
        String fingerprint=exportFingerprint(projectId,approved,tests);
        String key=exportKey(projectId,approved);
        var cached=validatedExports.get(key);
        if(fingerprint!=null&&cached!=null&&fingerprint.equals(cached.fingerprint())) return cached.tests();
        var prepared=exportProvenance.prepare(projectId,tests,exportSourceRoot(projectId));
        if(fingerprint!=null) validatedExports.put(key,new ValidatedExport(fingerprint,prepared));
        return prepared;
    }

    private ExportArchive buildArchive(Long projectId,List<TestCase> approved){
        var codeByCaseId=approved.stream().collect(java.util.stream.Collectors.toMap(TestCase::getId,TestCase::getCaseCode));
        var stored=approved.stream().map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).toList();
        String fingerprint=exportFingerprint(projectId,approved,stored);
        String key=exportKey(projectId,approved);
        var cached=validatedExports.get(key);
        Map<String,String> skipped=new LinkedHashMap<>();
        List<UnitTest> exportTests;
        if(fingerprint!=null&&cached!=null&&fingerprint.equals(cached.fingerprint())){
            exportTests=cached.tests();
        }else{
            var root=exportSourceRoot(projectId);
            String context=validationContext(projectId,approved);
            String[] failure=new String[stored.size()];
            java.util.function.Consumer<List<Integer>> check=indexes->{
                for(int index:indexes){
                    UnitTest test=stored.get(index);
                    String testKey=context==null?null:context+":"+test.getId()+":"+test.getTestCaseId()+":"
                            +com.greytest.service.agent.GenerationSourceFingerprint.hash(test.getPackageName()+"|"
                            +test.getTestClassName()+"|"+test.getTestMethodName()+"|"+test.getSourceCode());
                    if(testKey!=null&&validatedTests.containsKey(testKey)) continue;
                    try{
                        ai.validateExistingUnitTest(projectId,test,root);
                        if(testKey!=null){
                            // ponytail: xoá cả cache khi quá lớn thay vì LRU; đủ cho vài project chạy cùng lúc.
                            if(validatedTests.size()>20_000) validatedTests.clear();
                            validatedTests.put(testKey,Boolean.TRUE);
                        }
                    }catch(LlmResponseException invalid){
                        failure[index]=invalid.getMessage();
                    }
                }
            };
            validateInParallel(stored.size(),check);
            List<UnitTest> valid=new ArrayList<>();
            for(int index=0;index<stored.size();index++){
                if(failure[index]==null) valid.add(stored.get(index));
                else skipped.put(codeByCaseId.get(stored.get(index).getTestCaseId()),failure[index]);
            }
            exportTests=exportProvenance==null?valid:exportProvenance.prepareValidated(projectId,valid,root);
            if(fingerprint!=null&&skipped.isEmpty()) validatedExports.put(key,new ValidatedExport(fingerprint,exportTests));
        }
        var fileList=files.mergeByClass(exportTests).stream()
                .map(f->new com.greytest.dto.UnitTestFileDto(f.filePath(),f.testClassName(),f.packageName(),f.caseIds().size(),
                        f.caseIds().stream().map(codeByCaseId::get).filter(java.util.Objects::nonNull).toList(),f.sourceCode()))
                .toList();
        byte[] content=files.createCoverageArchive(fileList,exportSourceRoot(projectId),
                skipped.entrySet().stream().map(item->item.getKey()+": "+item.getValue()).toList());
        return new ExportArchive(content,List.copyOf(skipped.keySet()));
    }

    // ponytail: pool cố định 4 luồng dùng chung mọi project; tăng hoặc đưa ra cấu hình nếu nhiều người export cùng lúc.
    private static final int EXPORT_VALIDATION_THREADS=4;
    private static final java.util.concurrent.ExecutorService EXPORT_VALIDATION=java.util.concurrent.Executors.newFixedThreadPool(
            EXPORT_VALIDATION_THREADS,new java.util.concurrent.ThreadFactory(){
                private final java.util.concurrent.atomic.AtomicInteger next=new java.util.concurrent.atomic.AtomicInteger();
                @Override public Thread newThread(Runnable task){
                    Thread thread=new Thread(task,"greytest-export-validate-"+next.incrementAndGet());thread.setDaemon(true);return thread;}});

    /**
     * Kiểm tra lại test trước export: mỗi test kiểm tra độc lập nên chia nhóm chạy song song (mỗi nhóm trong export scope riêng,
     * như khi chạy tuần tự). Trước đây ~130 test kiểm tra tuần tự mất ~13 s trước mỗi lần chạy sandbox.
     */
    private void validateInParallel(int size,java.util.function.Consumer<List<Integer>> check){
        java.util.function.Consumer<List<Integer>> scoped=indexes->{
            if(exportProvenance==null) check.accept(indexes);
            else exportProvenance.inExportScope(()->{check.accept(indexes);return null;});
        };
        int groups=exportProvenance==null?1:Math.max(1,Math.min(EXPORT_VALIDATION_THREADS,size/8));
        List<List<Integer>> chunks=new ArrayList<>();
        for(int g=0;g<groups;g++) chunks.add(new ArrayList<>());
        for(int index=0;index<size;index++) chunks.get(index%groups).add(index);
        if(groups==1){ scoped.accept(chunks.get(0)); return; }
        var futures=chunks.stream().map(chunk->java.util.concurrent.CompletableFuture.supplyAsync(
                GenerationJobContext.wrap(()->{scoped.accept(chunk);return null;}),EXPORT_VALIDATION)).toList();
        try{
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        }catch(java.util.concurrent.CompletionException failed){
            if(failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failed;
        }
    }

    /** Dấu vân tay mọi đầu vào của validator ngoài chính test (TC/TP/BR đã duyệt, source); null khi không đủ dữ liệu. */
    private String validationContext(Long projectId,List<TestCase> approved){
        if(rules==null) return null;
        try{
            StringBuilder input=new StringBuilder();
            for(TestCase c:approved) input.append(java.util.Arrays.asList(c.getId(),c.getCaseCode(),c.getTestPlanId(),c.getDescription(),c.getPreconditions(),c.getTestData(),c.getExpectedResult(),c.getStatus(),c.getIsModified())).append('\n');
            for(TestPlan p:plans.findByProjectId(projectId)) input.append(java.util.Arrays.asList(p.getId(),p.getBusinessRuleId(),p.getTitle(),p.getDescription(),p.getStatus())).append('\n');
            for(var r:rules.findByProjectId(projectId)) input.append(java.util.Arrays.asList(r.getId(),r.getMethodId(),r.getDescription(),r.getReviewNote(),r.getStatus())).append('\n');
            var root=exportSourceRoot(projectId);
            if(root!=null) input.append(com.greytest.service.agent.PersistentSourceAnalysisService.contentDigest(root));
            return projectId+":"+com.greytest.service.agent.GenerationSourceFingerprint.hash(input.toString());
        }catch(RuntimeException unavailable){
            return null;
        }
    }

    private java.nio.file.Path exportSourceRoot(Long projectId) {
        String path = ensure(projectId).getStoragePath();
        return path == null || path.isBlank() ? null : java.nio.file.Path.of(path);
    }

    private Project ensure(Long id){return projects.findById(id).orElseThrow(()->new ProjectNotFoundException(id));}
    private UnitTestDto dto(UnitTest u){return new UnitTestDto(u.getId(),u.getTestCaseId(),u.getTestClassName(),u.getTestMethodName(),u.getPackageName(),u.getGenerationType(),u.getExistingTestFilePath(),com.greytest.service.agent.GenerationSourceFingerprint.withoutMetadata(u.getSourceCode()),u.getFilePath(),u.getCreatedAt());}
}
