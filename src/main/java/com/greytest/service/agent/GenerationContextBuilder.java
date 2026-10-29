package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.ControllerServiceRelationDto;
import com.greytest.dto.EndpointDto;
import com.greytest.dto.CoverageGapDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.ExistingTestDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.ServiceRelationDto;
import com.greytest.dto.agent.GenerationContextDtos.AnalysisSummaryDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleGenerationContextDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleReviewContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.CoverageRefinementContextDto;
import com.greytest.dto.agent.GenerationContextDtos.DependencyCallContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ExistingTestContextDto;
import com.greytest.dto.agent.GenerationContextDtos.GeneratedUnitTestContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ProjectContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.analysis.AnalysisManifestService;
import com.greytest.service.analysis.AnalysisService;
import com.greytest.service.analysis.ExistingTestService;
import com.greytest.service.ServiceScopeResolver;
import com.greytest.service.GenerationJobContext;

/**
 * Gom context grey-box theo schema on dinh truoc khi dua sang prompt/LLM.
 */
@Service
public class GenerationContextBuilder {
    private static final ThreadLocal<Map<Object,Object>> INPUT_SNAPSHOT=new ThreadLocal<>();
    private record InputReadKey(String type,Long projectId,boolean source) { }

    /** Chụp upstream bất biến trong một batch; scope lồng nhau luôn đọc mới trước commit. */
    public static <T> T withInputSnapshot(java.util.function.Supplier<T> action) {
        var previous=INPUT_SNAPSHOT.get(); INPUT_SNAPSHOT.set(new java.util.HashMap<>());
        try { return action.get(); }
        finally { if(previous==null) INPUT_SNAPSHOT.remove(); else INPUT_SNAPSHOT.set(previous); }
    }

    @SuppressWarnings("unchecked")
    public static <T> T inputSnapshot(Object key,java.util.function.Supplier<T> read) {
        var snapshot=INPUT_SNAPSHOT.get();
        if(snapshot==null) return read.get();
        if(snapshot.containsKey(key)) return (T)snapshot.get(key);
        // Loader có thể đọc thêm key upstream trên cùng thread; computeIfAbsent không hỗ trợ sửa map lồng nhau.
        T value=read.get(); snapshot.put(key,value); return value;
    }

    private static final String SERVICE_CLASS_TYPE = "SERVICE";
    // ponytail: cap theo method de prompt khong phinh vo han; thay bang token-aware chunking khi can LLM that.
    private static final int MAX_METHOD_SOURCE_CHARS = 4_000;
    // Gom toi da ba method cung Service de giam so request nhung van giu prompt gon.
    public static final int MAX_GENERATION_METHODS = 3;
    public static final int MAX_TEST_PLAN_METHODS = 8;
    // ponytail: batch co dinh giu moi request gon gang, 8 item/batch toi uu toc do va quota
    public static final int MAX_TEST_CASE_PLANS = 8;
    // ponytail: Unit Test gom 8 case/batch de giam so lan goi mang
    public static final int MAX_UNIT_TEST_CASES = 8;
    public static final int MAX_UNIT_TEST_RETRY_CASES = 2;
    private static final int MAX_UNIT_TEST_REFERENCES = 10;
    // Giữ prompt có giới hạn nhưng phải báo thiếu context thay vì âm thầm bỏ dependency.
    private static final int MAX_SUPPORTING_SOURCE_CHARS = 100_000;
    private static final int MAX_GENERATION_RELATIONS = 40;
    private static final int MAX_GENERATION_DEPENDENCIES = 40;
    // ponytail: chỉ kèm vài callee Service để giữ prompt có giới hạn; tăng khi benchmark chứng minh cần.
    private static final int MAX_CROSS_SERVICE_SOURCES = 5;
    private record CollaboratorCallEvidence(String collaboratorName, String calleeMethodName, int argumentCount) {}
    public static final int MAX_REVIEW_RULES = 10;

    private final AnalysisService analysisService;
    private final ExistingTestService existingTestService;
    private final BusinessRuleRepository businessRuleRepository;
    private final TestPlanRepository testPlanRepository;
    private final TestPlanCoveredRuleRepository testPlanCoveredRuleRepository;
    private final TestCaseRepository testCaseRepository;
    private final UnitTestRepository unitTestRepository;
    private final ProjectRepository projectRepository;
    private PersistentSourceAnalysisService persistentAnalysis;
    private com.greytest.repository.SourceUpdateRepository sourceUpdates;
    private com.greytest.repository.SourceUpdateItemRepository sourceUpdateItems;
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    void configureIncrementalInputs(PersistentSourceAnalysisService persistentAnalysis,
            com.greytest.repository.SourceUpdateRepository sourceUpdates,
            com.greytest.repository.SourceUpdateItemRepository sourceUpdateItems,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.persistentAnalysis = persistentAnalysis;
        this.sourceUpdates = sourceUpdates;
        this.sourceUpdateItems = sourceUpdateItems;
        this.objectMapper = objectMapper;
    }

    public GenerationContextBuilder(
            AnalysisService analysisService,
            AnalysisManifestService ignoredManifestService,
            ExistingTestService existingTestService,
            BusinessRuleRepository businessRuleRepository,
            TestPlanRepository testPlanRepository,
            TestPlanCoveredRuleRepository testPlanCoveredRuleRepository,
            TestCaseRepository testCaseRepository,
            UnitTestRepository unitTestRepository,
            ProjectRepository projectRepository) {
        this.analysisService = analysisService;
        this.existingTestService = existingTestService;
        this.businessRuleRepository = businessRuleRepository;
        this.testPlanRepository = testPlanRepository;
        this.testPlanCoveredRuleRepository = testPlanCoveredRuleRepository;
        this.testCaseRepository = testCaseRepository;
        this.unitTestRepository = unitTestRepository;
        this.projectRepository = projectRepository;
    }

    /** Context cho AI tu sinh Business Rule tu cac service method. */
    @Transactional(readOnly = true)
    public BusinessRuleGenerationContextDto buildBusinessRuleGenerationContext(Long projectId) {
        AnalysisResultDto analysis = storedAnalysis(projectId);
        Set<Long> uncoveredMethodIds = serviceMethodIds(analysis);
        uncoveredMethodIds.removeAll(methodIds(businessRules(projectId)));
        Set<Long> firstServiceBatch = analysis.classes().stream()
                .filter(javaClass -> SERVICE_CLASS_TYPE.equals(javaClass.classType()))
                .sorted(Comparator.comparing(JavaClassDto::filePath, Comparator.nullsLast(String::compareTo))
                        .thenComparing(JavaClassDto::qualifiedName))
                .map(javaClass -> javaClass.methods().stream()
                        .filter(method -> uncoveredMethodIds.contains(method.id()))
                        .sorted(Comparator.comparing(JavaMethodDto::lineStart, Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(JavaMethodDto::id))
                        .limit(MAX_GENERATION_METHODS)
                        .map(JavaMethodDto::id)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)))
                .filter(methodIds -> !methodIds.isEmpty())
                .findFirst()
                .orElseGet(java.util.LinkedHashSet::new);
        return buildBusinessRuleGenerationContext(analysis, firstServiceBatch);
    }

    /** Context BR duoc gioi han trong dung mot Service va batch method da chon. */
    @Transactional(readOnly = true)
    public BusinessRuleGenerationContextDto buildBusinessRuleGenerationContext(
            Long projectId, Set<Long> targetMethodIds) {
        AnalysisResultDto analysis = storedAnalysis(projectId);
        return buildBusinessRuleGenerationContext(analysis, targetMethodIds);
    }

    /** BR của draft dùng candidate source và resource, không sửa source active trước Apply. */
    @Transactional(readOnly = true)
    public BusinessRuleGenerationContextDto buildSnapshotBusinessRuleGenerationContext(
            Long projectId, Set<Long> targetMethodIds, java.nio.file.Path snapshot) {
        return buildBusinessRuleGenerationContext(sourceAnalysis(projectId, snapshot, true), targetMethodIds, snapshot);
    }

    private BusinessRuleGenerationContextDto buildBusinessRuleGenerationContext(
            AnalysisResultDto analysis, Set<Long> targetMethodIds) {
        return buildBusinessRuleGenerationContext(analysis, targetMethodIds, activeSourceRoot(analysis.projectId()));
    }

    private BusinessRuleGenerationContextDto buildBusinessRuleGenerationContext(
            AnalysisResultDto analysis, Set<Long> targetMethodIds, java.nio.file.Path sourceRoot) {
        Set<Long> requestedIds = targetMethodIds == null ? Set.of() : Set.copyOf(targetMethodIds);
        List<JavaClassDto> targetServices = analysis.classes().stream()
                .filter(javaClass -> SERVICE_CLASS_TYPE.equals(javaClass.classType()))
                .filter(javaClass -> javaClass.methods().stream().anyMatch(method -> requestedIds.contains(method.id())))
                .toList();
        Set<Long> validIds = targetServices.stream()
                .flatMap(javaClass -> javaClass.methods().stream())
                .map(JavaMethodDto::id)
                .filter(requestedIds::contains)
                .collect(java.util.stream.Collectors.toSet());
        if (!validIds.equals(requestedIds) || targetServices.size() > 1) {
            throw new IllegalArgumentException(
                    "Business Rule context chỉ được chứa method hợp lệ của đúng một Service.");
        }
        List<DependencyCallContextDto> dependencyCalls = dependencyCalls(analysis, targetServices, requestedIds);
        List<ClassContextDto> sourceClasses = sourceContextClasses(analysis, requestedIds, true);
        var resources = BusinessRuleResourceCollector.collect(sourceRoot, sourceClasses);
        Set<Long> targetClassIds = targetServices.stream().map(JavaClassDto::id).collect(java.util.stream.Collectors.toSet());
        return new BusinessRuleGenerationContextDto(
                project(analysis),
                summary(analysis.projectId(), analysis),
                sourceClasses,
                relevantServiceRelations(analysis, targetServices, dependencyCalls),
                relevantControllerServiceRelations(analysis, targetServices, requestedIds),
                dependencyCalls,
                relevantExistingTests(analysis.projectId(), requestedIds, targetClassIds, true),
                resources.resources(),
                resources.warnings());
    }

    private List<ServiceRelationDto> relevantServiceRelations(
            AnalysisResultDto analysis,
            List<JavaClassDto> targetServices,
            List<DependencyCallContextDto> dependencyCalls) {
        Set<String> targetServiceNames = targetServices.stream()
                .map(JavaClassDto::qualifiedName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> dependencyTypes = dependencyCalls.stream()
                .flatMap(call -> java.util.stream.Stream.of(call.calleeQualifiedName(), call.collaboratorType()))
                .filter(java.util.Objects::nonNull)
                .map(this::simpleName)
                .collect(java.util.stream.Collectors.toSet());
        return serviceRelations(analysis).stream()
                .filter(relation -> targetServiceNames.contains(relation.serviceQualifiedName()))
                .filter(relation -> dependencyTypes.contains(simpleName(relation.repositoryQualifiedName())))
                .sorted(Comparator.comparing(ServiceRelationDto::serviceQualifiedName,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(ServiceRelationDto::repositoryQualifiedName,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(ServiceRelationDto::id, Comparator.nullsLast(Long::compareTo)))
                .limit(MAX_GENERATION_RELATIONS)
                .toList();
    }

    private List<ControllerServiceRelationDto> relevantControllerServiceRelations(
            AnalysisResultDto analysis,
            List<JavaClassDto> targetServices,
            Set<Long> targetMethodIds) {
        Set<String> targetServiceNames = targetServices.stream()
                .map(JavaClassDto::qualifiedName)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Long> methodNameCounts = targetServices.stream()
                .flatMap(javaClass -> javaClass.methods().stream())
                .collect(java.util.stream.Collectors.groupingBy(
                        JavaMethodDto::methodName, java.util.stream.Collectors.counting()));
        Set<String> targetMethodNames = targetServices.stream()
                .flatMap(javaClass -> javaClass.methods().stream())
                .filter(method -> targetMethodIds.contains(method.id()))
                .filter(method -> methodNameCounts.getOrDefault(method.methodName(), 0L) == 1L)
                .map(JavaMethodDto::methodName)
                .collect(java.util.stream.Collectors.toSet());
        return controllerServiceRelations(analysis).stream()
                .filter(relation -> targetServiceNames.contains(relation.serviceQualifiedName()))
                .filter(relation -> targetMethodNames.contains(relation.serviceMethodName()))
                .sorted(Comparator.comparing(ControllerServiceRelationDto::controllerQualifiedName,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(ControllerServiceRelationDto::controllerMethodName,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(ControllerServiceRelationDto::serviceQualifiedName,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(ControllerServiceRelationDto::serviceMethodName,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(ControllerServiceRelationDto::id, Comparator.nullsLast(Long::compareTo)))
                .limit(MAX_GENERATION_RELATIONS)
                .toList();
    }

    private List<DependencyCallContextDto> dependencyCalls(
            AnalysisResultDto analysis,
            List<JavaClassDto> targetServices,
            Set<Long> targetMethodIds) {
        Map<String, List<JavaClassDto>> classesBySimpleName = new LinkedHashMap<>();
        analysis.classes().stream()
                .filter(javaClass -> javaClass.className() != null)
                .forEach(javaClass -> classesBySimpleName
                        .computeIfAbsent(javaClass.className(), ignored -> new ArrayList<>())
                        .add(javaClass));

        List<DependencyCallContextDto> result = new ArrayList<>();
        int detailedServiceCalls = 0;
        for (JavaClassDto caller : targetServices) {
            Map<String, String> fieldTypes = fieldTypes(caller.sourceCode());
            for (JavaMethodDto method : caller.methods()) {
                if (!targetMethodIds.contains(method.id())) continue;
                Set<String> seen = new java.util.HashSet<>();
                for (CollaboratorCallEvidence call : collaboratorCalls(method.sourceCode())) {
                    String collaboratorName = call.collaboratorName();
                    String calleeMethodName = call.calleeMethodName();
                    String collaboratorType = fieldTypes.get(collaboratorName);
                    if (collaboratorType == null) continue;
                    String key = collaboratorName + ":" + calleeMethodName + ":" + call.argumentCount();
                    if (!seen.add(key)) continue;

                    List<JavaClassDto> candidates = classesBySimpleName.getOrDefault(
                            simpleName(collaboratorType), List.of());
                    JavaClassDto callee = candidates.size() == 1 ? candidates.get(0) : null;
                    List<JavaMethodDto> calleeMethods = callee == null ? List.of()
                            : callee.methods().stream()
                                    .filter(candidate -> calleeMethodName.equals(candidate.methodName()))
                                    .filter(candidate -> candidate.parameters().size() == call.argumentCount())
                                    .toList();
                    JavaMethodDto calleeMethod = calleeMethods.size() == 1 ? calleeMethods.get(0) : null;
                    EndpointDto endpoint = calleeMethod == null || calleeMethod.endpoints().size() != 1
                            ? null
                            : calleeMethod.endpoints().get(0);
                    String calleeServiceSourceCode = null;
                    if (detailedServiceCalls < MAX_CROSS_SERVICE_SOURCES
                            && callee != null
                            && calleeMethod != null
                            && SERVICE_CLASS_TYPE.equals(callee.classType())
                            && !java.util.Objects.equals(caller.qualifiedName(), callee.qualifiedName())) {
                        calleeServiceSourceCode = completeMethodSource(calleeMethod.sourceCode());
                        if (calleeServiceSourceCode != null && !calleeServiceSourceCode.isBlank()) {
                            detailedServiceCalls++;
                        } else {
                            calleeServiceSourceCode = null;
                        }
                    }
                    result.add(new DependencyCallContextDto(
                            method.id(),
                            caller.qualifiedName(),
                            collaboratorName,
                            collaboratorType,
                            callee == null ? null : callee.className(),
                            callee == null ? null : callee.qualifiedName(),
                            calleeMethod == null ? calleeMethodName : calleeMethod.methodName(),
                            endpoint == null ? null : endpoint.httpMethod(),
                            endpoint == null ? null : endpoint.path(),
                            calleeServiceSourceCode));
                    if (result.size() >= MAX_GENERATION_DEPENDENCIES) break;
                }
                if (result.size() >= MAX_GENERATION_DEPENDENCIES) break;
            }
            if (result.size() >= MAX_GENERATION_DEPENDENCIES) break;
        }
        return result.stream()
                .sorted(Comparator.comparing(DependencyCallContextDto::callerMethodId,
                                Comparator.nullsLast(Long::compareTo))
                        .thenComparing(DependencyCallContextDto::collaboratorName)
                        .thenComparing(DependencyCallContextDto::calleeMethodName,
                                Comparator.nullsLast(String::compareTo)))
                .limit(MAX_GENERATION_DEPENDENCIES)
                .toList();
    }

    private List<CollaboratorCallEvidence> collaboratorCalls(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) return List.of();
        try {
            var parsed = new JavaParser().parseMethodDeclaration(sourceCode);
            MethodDeclaration method = parsed.getResult().orElse(null);
            if (!parsed.isSuccessful() || method == null) return List.of();
            return method.findAll(MethodCallExpr.class).stream()
                    .filter(call -> collaboratorName(call) != null)
                    .map(call -> new CollaboratorCallEvidence(
                            collaboratorName(call),
                            call.getNameAsString(),
                            call.getArguments().size()))
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
    private String collaboratorName(MethodCallExpr call) {
        return call.getScope()
                .map(scope -> {
                    if (scope.isNameExpr()) return scope.asNameExpr().getNameAsString();
                    if (scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr()) {
                        return scope.asFieldAccessExpr().getNameAsString();
                    }
                    return null;
                })
                .orElse(null);
    }

    private Map<String, String> fieldTypes(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) return Map.of();
        try {
            var parsed = new JavaParser().parse(sourceCode);
            CompilationUnit unit = parsed.getResult().orElse(null);
            if (!parsed.isSuccessful() || unit == null) return Map.of();
            Map<String, String> result = new LinkedHashMap<>();
            for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
                String type = field.getElementType().asString();
                field.getVariables().forEach(variable -> result.put(variable.getNameAsString(), type));
            }
            return result;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private String simpleName(String typeName) {
        if (typeName == null || typeName.isBlank()) return "";
        String raw = typeName;
        int genericStart = raw.indexOf('<');
        if (genericStart >= 0) raw = raw.substring(0, genericStart);
        int lastDot = raw.lastIndexOf('.');
        return lastDot >= 0 ? raw.substring(lastDot + 1) : raw;
    }
    /** Context cho AI review Business Rule user da nhap. */
    @Transactional(readOnly = true)
    public BusinessRuleReviewContextDto buildBusinessRuleReviewContext(Long projectId) {
        return buildBusinessRuleReviewContext(projectId, Set.of());
    }

    @Transactional(readOnly = true)
    public BusinessRuleReviewContextDto buildBusinessRuleReviewContext(Long projectId, Set<Long> targetRuleIds) {
        AnalysisResultDto analysis = storedAnalysis(projectId);
        List<BusinessRuleContextDto> allRules = businessRules(projectId).stream()
                .filter(rule -> targetRuleIds == null || targetRuleIds.isEmpty() || targetRuleIds.contains(rule.id()))
                .toList();
        List<BusinessRuleContextDto> dirtyRules = allRules.stream()
                .filter(rule -> Boolean.TRUE.equals(rule.isModified()))
                .limit(MAX_REVIEW_RULES)
                .toList();
        Set<Long> dirtyMethodIds = methodIds(dirtyRules);
        List<BusinessRuleContextDto> relatedRules = allRules.stream()
                .filter(rule -> dirtyMethodIds.contains(rule.methodId()))
                .filter(rule -> !dirtyRules.contains(rule))
                .toList();
        return new BusinessRuleReviewContextDto(
                project(analysis),
                summary(projectId, analysis),
                sourceContextClasses(analysis, dirtyMethodIds),
                serviceRelations(analysis),
                controllerServiceRelations(analysis),
                dirtyRules,
                relatedRules,
                existingTests(projectId, false));
    }

    /** Context cho sinh Test Plan tu Business Rule da approve. */
    @Transactional(readOnly = true)
    public TestPlanContextDto buildTestPlanContext(Long projectId) {
        return buildTestPlanContext(projectId, Set.of());
    }

    /** Context cho mot batch Business Rule da approve de tranh LLM tra JSON bi cat. */
    @Transactional(readOnly = true)
    public TestPlanContextDto buildTestPlanContext(Long projectId, Set<Long> targetRuleIds) {
        return buildTestPlanContext(projectId, targetRuleIds, activeGenerationAnalysis(projectId), activeSourceRoot(projectId));
    }

    @Transactional(readOnly = true)
    public TestPlanContextDto buildSnapshotTestPlanContext(Long projectId, Set<Long> targetRuleIds, java.nio.file.Path snapshot) {
        return buildTestPlanContext(projectId, targetRuleIds, sourceAnalysis(projectId, snapshot, true), snapshot);
    }

    private TestPlanContextDto buildTestPlanContext(Long projectId, Set<Long> targetRuleIds,
            AnalysisResultDto analysis, java.nio.file.Path sourceRoot) {
        List<BusinessRuleContextDto> approvedRules = approvedBusinessRules(projectId).stream()
                .filter(rule -> targetRuleIds == null || targetRuleIds.isEmpty() || targetRuleIds.contains(rule.id()))
                .toList();
        requireRequestedIds(targetRuleIds, approvedRules.stream().map(BusinessRuleContextDto::id).collect(java.util.stream.Collectors.toSet()), "Business Rule");
        List<ClassContextDto> sourceClasses = sourceContextClasses(analysis, methodIds(approvedRules));
        var resources = BusinessRuleResourceCollector.collect(sourceRoot, sourceClasses);
        return new TestPlanContextDto(
                project(analysis),
                summary(projectId, analysis),
                sourceClasses,
                approvedRules,
                existingTests(projectId, false),
                resources.resources(), resources.warnings());
    }

    /** Context cho sinh Test Case tu Test Plan da approve. */
    @Transactional(readOnly = true)
    public TestCaseContextDto buildTestCaseContext(Long projectId) {
        return buildTestCaseContext(projectId, Set.of());
    }

    /** Context cho mot batch Test Plan de payload khong tang theo kich thuoc project. */
    @Transactional(readOnly = true)
    public TestCaseContextDto buildTestCaseContext(Long projectId, Set<Long> targetPlanIds) {
        return buildTestCaseContext(projectId, targetPlanIds, activeGenerationAnalysis(projectId), activeSourceRoot(projectId));
    }

    @Transactional(readOnly = true)
    public TestCaseContextDto buildSnapshotTestCaseContext(Long projectId, Set<Long> targetPlanIds, java.nio.file.Path snapshot) {
        return buildTestCaseContext(projectId, targetPlanIds, sourceAnalysis(projectId, snapshot, true), snapshot);
    }

    private TestCaseContextDto buildTestCaseContext(Long projectId, Set<Long> targetPlanIds,
            AnalysisResultDto analysis, java.nio.file.Path sourceRoot) {
        List<TestPlanContextItemDto> targetPlans = approvedTestPlans(projectId).stream()
                .filter(plan -> targetPlanIds == null || targetPlanIds.isEmpty() || targetPlanIds.contains(plan.id()))
                .toList();
        Set<Long> targetRuleIds = targetPlans.stream()
                .flatMap(plan -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(plan.businessRuleId()),
                        plan.coveredRuleIds().stream()))
                .collect(java.util.stream.Collectors.toSet());
        List<BusinessRuleContextDto> approvedRules = approvedBusinessRules(projectId).stream()
                .filter(rule -> targetRuleIds.contains(rule.id()))
                .toList();
        requireRequestedIds(targetPlanIds, targetPlans.stream().map(TestPlanContextItemDto::id).collect(java.util.stream.Collectors.toSet()), "Test Plan");
        requireApprovedRuleLinks(targetPlans, approvedRules);
        Set<Long> targetMethodIds = methodIds(approvedRules);
        Set<Long> targetClassIds = analysis.classes().stream()
                .filter(javaClass -> javaClass.methods().stream()
                        .anyMatch(method -> targetMethodIds.contains(method.id())))
                .map(JavaClassDto::id)
                .collect(java.util.stream.Collectors.toSet());
        List<ClassContextDto> sourceClasses = sourceContextClasses(analysis, targetMethodIds);
        var resources = BusinessRuleResourceCollector.collect(sourceRoot, sourceClasses);
        return new TestCaseContextDto(
                project(analysis),
                summary(projectId, analysis),
                sourceClasses,
                approvedRules,
                targetPlans,
                relevantExistingTests(projectId, targetMethodIds, targetClassIds),
                resources.resources(), resources.warnings());
    }

    private record StoredAnalysisKey(Long projectId) { }
    private record SourceAnalysisKey(Long projectId, java.nio.file.Path root, String digest, boolean strict) { }
    private record SourceDigestKey(java.nio.file.Path root) { }
    private record ParsedSourceAnalysisKey(Long projectId, java.nio.file.Path root, String digest, boolean strict) { }

    private AnalysisResultDto storedAnalysis(Long projectId) {
        return GenerationJobContext.memoize(new StoredAnalysisKey(projectId),
                () -> analysisService.getAnalysisResult(projectId));
    }

    private AnalysisResultDto sourceAnalysis(Long projectId, java.nio.file.Path root, boolean strict) {
        AnalysisResultDto stored = storedAnalysis(projectId);
        java.nio.file.Path normalized = root.toAbsolutePath().normalize();
        String digest = inputSnapshot(new SourceDigestKey(normalized),()->PersistentSourceAnalysisService.contentDigest(normalized));
        var parsed = GenerationJobContext.memoize(new ParsedSourceAnalysisKey(projectId, normalized, digest, strict),
                () -> persistentAnalysis == null ? SnapshotAnalysisLoader.parse(normalized, strict)
                        : persistentAnalysis.load(projectId, normalized, strict, digest));
        return GenerationJobContext.memoize(new SourceAnalysisKey(projectId, normalized, digest, strict),
                () -> SnapshotAnalysisLoader.remap(stored, parsed));
    }

    private AnalysisResultDto activeGenerationAnalysis(Long projectId) {
        AnalysisResultDto analysis = storedAnalysis(projectId);
        return projectRepository.findById(projectId).map(Project::getStoragePath)
                .filter(path -> !path.isBlank())
                .map(path -> sourceAnalysis(projectId, java.nio.file.Path.of(path), false))
                .orElse(analysis);
    }

    private java.nio.file.Path activeSourceRoot(Long projectId) {
        return projectRepository.findById(projectId).map(Project::getStoragePath)
                .filter(path -> !path.isBlank()).map(java.nio.file.Path::of).orElse(null);
    }

    /** Context cho sinh/cai thien Unit Test, gom existing tests rieng de khong tinh vao production counters. */
    @Transactional(readOnly = true)
    public UnitTestContextDto buildUnitTestContext(Long projectId) {
        return buildUnitTestContext(projectId, Set.of());
    }

    /** Context toi gian cho vong bo sung dua tren gap JaCoCo. */
    @Transactional(readOnly = true)
    public CoverageRefinementContextDto buildCoverageRefinementContext(
            Long projectId, int round, List<CoverageGapDto> gaps) {
        AnalysisResultDto analysis = storedAnalysis(projectId);
        Set<Long> gapMethodIds = gaps.stream()
                .map(CoverageGapDto::methodId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<BusinessRuleContextDto> targetRules = approvedBusinessRules(projectId).stream()
                .filter(rule -> gapMethodIds.contains(rule.methodId()))
                .toList();
        Set<Long> targetRuleIds = targetRules.stream()
                .map(BusinessRuleContextDto::id)
                .collect(java.util.stream.Collectors.toSet());
        List<TestPlanContextItemDto> targetPlans = approvedTestPlans(projectId).stream()
                .filter(plan -> plan.coveredRuleIds().stream().anyMatch(targetRuleIds::contains))
                .toList();
        Set<Long> targetClassIds = analysis.classes().stream()
                .filter(javaClass -> javaClass.methods().stream()
                        .anyMatch(method -> gapMethodIds.contains(method.id())))
                .map(JavaClassDto::id)
                .collect(java.util.stream.Collectors.toSet());
        return new CoverageRefinementContextDto(
                project(analysis),
                summary(projectId, analysis),
                classes(analysis, gapMethodIds),
                targetRules,
                targetPlans,
                approvedTestCases(targetPlans),
                relevantExistingTests(projectId, gapMethodIds, targetClassIds),
                round,
                gaps);
    }

    /** Unit Test vong bo sung chi nhan cac Test Case vua sinh. */
    @Transactional(readOnly = true)
    public UnitTestContextDto buildUnitTestContext(Long projectId, Set<Long> targetCaseIds) {
        AnalysisResultDto analysis = storedAnalysis(projectId);
        java.nio.file.Path sourceRoot = activeSourceRoot(projectId);
        // Analysis cũ có thể thiếu imports; đọc snapshot trong bộ nhớ để giữ nguyên ID và artifact đã duyệt.
        if (sourceRoot != null) {
            return buildVerifiedUnitTestContext(projectId, targetCaseIds,
                    sourceAnalysis(projectId, sourceRoot, false), sourceRoot);
        }
        return buildUnitTestContext(projectId, targetCaseIds, analysis, null);
    }

    private UnitTestContextDto buildUnitTestContext(Long projectId, Set<Long> targetCaseIds,
            AnalysisResultDto analysis, java.nio.file.Path sourceRoot) {
        List<TestCaseContextItemDto> allCases = approvedTestCases(projectId);
        boolean fullGeneration = targetCaseIds == null || targetCaseIds.isEmpty();
        List<TestCaseContextItemDto> targetCases = allCases.stream()
                .filter(testCase -> fullGeneration || targetCaseIds.contains(testCase.id()))
                .toList();
        Set<Long> targetPlanIds = targetCases.stream()
                .map(TestCaseContextItemDto::testPlanId)
                .collect(java.util.stream.Collectors.toSet());
        List<TestCaseContextItemDto> previousCases = fullGeneration ? List.of() : allCases.stream()
                .filter(testCase -> targetPlanIds.contains(testCase.testPlanId()))
                .filter(testCase -> !targetCaseIds.contains(testCase.id()))
                .limit(MAX_UNIT_TEST_REFERENCES)
                .toList();
        List<TestPlanContextItemDto> targetPlans = approvedTestPlans(projectId).stream()
                .filter(plan -> targetPlanIds.contains(plan.id()))
                .toList();
        Set<Long> targetRuleIds = targetPlans.stream()
                .flatMap(plan -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(plan.businessRuleId()),
                        plan.coveredRuleIds().stream()))
                .collect(java.util.stream.Collectors.toSet());
        List<BusinessRuleContextDto> targetRules = approvedBusinessRules(projectId).stream()
                .filter(rule -> targetRuleIds.contains(rule.id()))
                .toList();
        requireRequestedIds(targetCaseIds, targetCases.stream().map(TestCaseContextItemDto::id).collect(java.util.stream.Collectors.toSet()), "Test Case");
        requireApprovedRuleLinks(targetPlans, targetRules);
        Set<Long> targetMethodIds = methodIds(targetRules);
        Set<Long> targetClassIds = analysis.classes().stream()
                .filter(javaClass -> javaClass.methods().stream()
                        .anyMatch(method -> targetMethodIds.contains(method.id())))
                .map(JavaClassDto::id)
                .collect(java.util.stream.Collectors.toSet());
        List<ClassContextDto> sourceClasses = sourceContextClasses(analysis, targetMethodIds);
        var resources = BusinessRuleResourceCollector.collect(sourceRoot, sourceClasses);
        return new UnitTestContextDto(
                project(analysis),
                summary(projectId, analysis),
                sourceClasses,
                targetRules,
                targetPlans,
                targetCases,
                previousCases,
                generatedUnitTests(previousCases).stream().limit(MAX_UNIT_TEST_REFERENCES).toList(),
                relevantExistingTests(projectId, targetMethodIds, targetClassIds, true),
                resources.resources(), resources.warnings());
    }

    /** Context Unit Test từ snapshot source của bản cập nhật. */
    @Transactional(readOnly = true)
    public UnitTestContextDto buildSnapshotUnitTestContext(Long projectId, Set<Long> targetCaseIds, java.nio.file.Path snapshot) {
        return buildVerifiedUnitTestContext(projectId, targetCaseIds,
                sourceAnalysis(projectId, snapshot, true), snapshot);
    }

    private UnitTestContextDto buildVerifiedUnitTestContext(Long projectId, Set<Long> targetCaseIds,
            AnalysisResultDto analysis, java.nio.file.Path sourceRoot) {
        UnitTestContextDto context = buildUnitTestContext(projectId, targetCaseIds, analysis, sourceRoot);
        Set<Long> requiredIds = context.approvedBusinessRules().stream().map(BusinessRuleContextDto::methodId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Set<Long> availableIds = context.classes().stream().flatMap(c -> c.methods().stream()).map(MethodContextDto::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!availableIds.containsAll(requiredIds)) {
            throw new LlmResponseException("Source snapshot does not contain every target method linked to the selected Test Cases.");
        }
        return context;
    }

    /** Context Unit Test cho source update, ghi de source cua method vua thay doi. */
    @Transactional(readOnly = true)
    public UnitTestContextDto buildUnitTestContext(
            Long projectId, Set<Long> targetCaseIds, List<MethodDiffItem> changedMethods) {
        UnitTestContextDto context = buildUnitTestContext(projectId, targetCaseIds);
        if (changedMethods == null || changedMethods.isEmpty()) return context;
        return new UnitTestContextDto(
                context.project(),
                context.analysis(),
                replaceChangedMethodSources(context.classes(), changedMethods),
                context.approvedBusinessRules(),
                context.approvedTestPlans(),
                context.approvedTestCases(),
                context.existingApprovedTestCases(),
                context.previousGeneratedUnitTests(),
                context.existingTests(),
                context.supportingResources(), context.contextWarnings());
    }

    /** Không dùng artifact chưa duyệt hoặc thiếu cha đã duyệt làm oracle. */
    private void requireRequestedIds(Set<Long> requested, Set<Long> approved, String artifact) {
        if (requested != null && !requested.isEmpty() && !approved.containsAll(requested)) {
            throw new InvalidProjectStatusException(artifact + " được chọn chưa APPROVED hoặc thiếu liên kết đã duyệt.");
        }
    }

    private void requireApprovedRuleLinks(List<TestPlanContextItemDto> plans, List<BusinessRuleContextDto> rules) {
        Set<Long> approvedIds = rules.stream().map(BusinessRuleContextDto::id).collect(java.util.stream.Collectors.toSet());
        for (TestPlanContextItemDto plan : plans) {
            if (!approvedIds.contains(plan.businessRuleId()) || !approvedIds.containsAll(plan.coveredRuleIds())) {
                throw new InvalidProjectStatusException("Test Plan " + plan.id() + " thiếu Business Rule APPROVED.");
            }
        }
    }

    private List<ClassContextDto> replaceChangedMethodSources(
            List<ClassContextDto> classes, List<MethodDiffItem> changedMethods) {
        return classes.stream().map(javaClass -> {
            List<MethodContextDto> methods = javaClass.methods().stream().map(method -> {
                    MethodDiffItem changed = changedMethods.stream()
                            .filter(diff -> java.util.Objects.equals(diff.methodKey(), methodKey(method)))
                            .findFirst()
                            .orElse(null);
                    if (changed != null && (changed.afterSource() == null || changed.afterSource().isBlank())) {
                        return null;
                    }
                    if (changed == null) {
                        return method;
                    }
                    return new MethodContextDto(
                            method.id(),
                            method.classQualifiedName(),
                            method.methodName(),
                            method.returnType(),
                            method.parameters(),
                            method.throwsList(),
                            method.visibility(),
                            changed.afterSource(),
                            method.lineStart(),
                            method.lineEnd(),
                            method.annotations(),
                            method.endpoints(),
                            method.branches());
                }).filter(java.util.Objects::nonNull).toList();
            String validationSource = replaceChangedClassSource(
                    javaClass.validationSourceCode(), javaClass.qualifiedName(), changedMethods);
            if (validationSource == null && !methods.isEmpty()) {
                throw new LlmResponseException("Cannot verify current source of " + javaClass.qualifiedName()
                        + " after source update. Reanalyze the project before generating Unit Tests.");
            }
            return new ClassContextDto(javaClass.id(), javaClass.packageName(), javaClass.className(),
                    javaClass.qualifiedName(), javaClass.classType(), javaClass.filePath(),
                    validationSource, javaClass.annotations(), methods, validationSource);
        })
                .toList();
    }

    private String replaceChangedClassSource(
            String originalSource, String qualifiedName, List<MethodDiffItem> changedMethods) {
        String source = originalSource;
        for (MethodDiffItem changed : changedMethods) {
            if (!java.util.Objects.equals(qualifiedName, changed.qualifiedClassName())) continue;
            if (changed.beforeSource() == null) {
                if (source == null || changed.afterSource() == null) return null;
                if (source.contains(changed.afterSource())) continue;
                var parsedClass = new JavaParser().parse(source).getResult();
                var parsedMethod = new JavaParser().parseMethodDeclaration(changed.afterSource()).getResult();
                if (parsedClass.isEmpty() || parsedMethod.isEmpty()) return null;
                var targetClass = parsedClass.get().getTypes().stream()
                        .filter(type -> type.getFullyQualifiedName().filter(qualifiedName::equals).isPresent())
                        .findFirst();
                if (targetClass.isEmpty()) return null;
                LexicalPreservingPrinter.setup(parsedClass.get());
                targetClass.get().addMember(parsedMethod.get());
                source = LexicalPreservingPrinter.print(parsedClass.get());
                continue;
            }
            if (source == null || !source.contains(changed.beforeSource())) return null;
            source = source.replace(changed.beforeSource(),
                    changed.afterSource() == null ? "" : changed.afterSource());
        }
        return source;
    }

    private String methodKey(MethodContextDto method) {
        String parameterTypes = method.parameters() == null ? ""
                : method.parameters().stream().map(MethodParamDto::type).collect(java.util.stream.Collectors.joining(","));
        return method.classQualifiedName() + "#" + method.methodName() + "(" + parameterTypes + ")";
    }

    /** Các bước sinh dùng cùng khai báo kiểu và helper để không truyền kỳ vọng sai xuống Unit Test. */
    private List<ClassContextDto> sourceContextClasses(AnalysisResultDto analysis, Set<Long> targetMethodIds) {
        return sourceContextClasses(analysis, targetMethodIds, false);
    }

    private List<ClassContextDto> sourceContextClasses(
            AnalysisResultDto analysis, Set<Long> targetMethodIds, boolean includeImplementations) {
        List<ClassContextDto> result = new ArrayList<>(classes(analysis, targetMethodIds, true));
        Set<String> targetModules = result.stream().map(type -> ServiceScopeResolver.modulePath(type.filePath()))
                .collect(java.util.stream.Collectors.toSet());
        int supportingSourceChars = 0;
        Set<String> included = result.stream().map(ClassContextDto::qualifiedName)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, String> includedPaths = result.stream().collect(java.util.stream.Collectors.toMap(
                ClassContextDto::qualifiedName, type -> type.filePath() == null ? "" : type.filePath()));
        List<JavaClassDto> candidates = analysis.classes().stream()
                .filter(javaClass -> javaClass.sourceCode() != null && !javaClass.sourceCode().isBlank())
                .sorted(Comparator.comparing(JavaClassDto::filePath, Comparator.nullsLast(String::compareTo))
                        .thenComparing(JavaClassDto::qualifiedName))
                .toList();
        List<JavaClassDto> implementationCandidates = includeImplementations ? candidates.stream()
                .filter(type -> targetModules.contains(ServiceScopeResolver.modulePath(type.filePath()))).toList() : List.of();
        // Duyệt tiếp DTO/repository vừa tìm được để không thiếu kiểu generic, enum và lớp cha của chúng.
        for (int index = 0; index < result.size(); index++) {
            ClassContextDto owner = result.get(index);
            CompilationUnit source = owner.sourceCode() == null ? null : new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                    .parse(owner.sourceCode()).getResult().orElse(null);
            Set<String> references = sourceTypeReferences(source);
            ensureReferencedSourcesParsed(analysis, owner, source, references);
            for (JavaClassDto candidate : candidates) {
                boolean dependency = isUnitTestDependency(candidate, owner, source, references, candidates);
                boolean implementation = includeImplementations && implementationCandidates.contains(candidate)
                        && implementsSourceType(candidate, owner, source, candidates);
                if (!dependency && !implementation) continue;
                if (!preferredDeclaration(candidate, owner, dependency ? candidates : implementationCandidates)) continue;
                if (included.contains(candidate.qualifiedName())) {
                    String path = candidate.filePath() == null ? "" : candidate.filePath();
                    if (!path.equals(includedPaths.get(candidate.qualifiedName()))) {
                        throw new LlmResponseException("Conflicting source modules for referenced type " + candidate.qualifiedName());
                    }
                    continue;
                }
                supportingSourceChars += candidate.sourceCode().length();
                if (supportingSourceChars > MAX_SUPPORTING_SOURCE_CHARS) {
                    throw new LlmResponseException("Required supporting production source exceeds "
                            + MAX_SUPPORTING_SOURCE_CHARS + " characters; cannot omit required types. "
                            + "Generate a smaller group of source methods, rules or cases.");
                }
                included.add(candidate.qualifiedName());
                includedPaths.put(candidate.qualifiedName(), candidate.filePath() == null ? "" : candidate.filePath());
                result.add(classContext(candidate, Set.of(), true));
            }
        }
        return List.copyOf(result);
    }

    private boolean preferredDeclaration(JavaClassDto candidate, ClassContextDto owner, List<JavaClassDto> candidates) {
        var matching = candidates.stream().filter(type -> type.qualifiedName().equals(candidate.qualifiedName())).toList();
        String module = ServiceScopeResolver.modulePath(owner.filePath());
        var local = matching.stream().filter(type -> ServiceScopeResolver.modulePath(type.filePath()).equals(module)).toList();
        if (local.size() == 1) return java.util.Objects.equals(local.get(0).filePath(), candidate.filePath());
        if (matching.size() == 1) return true;
        throw new LlmResponseException("Ambiguous production modules for referenced type " + candidate.qualifiedName());
    }

    private boolean implementsSourceType(JavaClassDto candidate, ClassContextDto owner,
            CompilationUnit ownerSource, List<JavaClassDto> candidates) {
        if (ownerSource == null || ownerSource.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                .noneMatch(type -> type.getFullyQualifiedName().filter(owner.qualifiedName()::equals).isPresent()
                        && (type.isInterface() || type.isAbstract()))) return false;
        var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(candidate.sourceCode()).getResult();
        if (parsed.isEmpty()) return false;
        var source = parsed.get();
        return source.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getFullyQualifiedName().filter(candidate.qualifiedName()::equals).isPresent())
                .flatMap(type -> Stream.concat(type.getImplementedTypes().stream(), type.getExtendedTypes().stream()))
                .anyMatch(parent -> {
                    String name = parent.getNameWithScope();
                    if (name.equals(owner.qualifiedName())) return true;
                    if (!name.equals(owner.className()) || source.findAll(com.github.javaparser.ast.type.TypeParameter.class).stream()
                            .anyMatch(parameter -> parameter.getNameAsString().equals(name))) return false;
                    var imports = source.getImports().stream().filter(value -> !value.isStatic() && !value.isAsterisk())
                            .map(value -> value.getNameAsString()).filter(value -> value.endsWith("." + name)).toList();
                    if (!imports.isEmpty()) return imports.size() == 1 && imports.get(0).equals(owner.qualifiedName());
                    if (java.util.Objects.equals(candidate.packageName(), owner.packageName())) return true;
                    if (candidates.stream().anyMatch(type -> name.equals(type.className())
                            && java.util.Objects.equals(candidate.packageName(), type.packageName()))) return false;
                    var wildcard = candidates.stream().filter(type -> name.equals(type.className()))
                            .filter(type -> source.getImports().stream().anyMatch(value -> !value.isStatic() && value.isAsterisk()
                                    && value.getNameAsString().equals(type.packageName())))
                            .map(JavaClassDto::qualifiedName).distinct().toList();
                    return wildcard.size() == 1 && wildcard.get(0).equals(owner.qualifiedName());
                });
    }

    private void ensureReferencedSourcesParsed(AnalysisResultDto analysis, ClassContextDto owner,
            CompilationUnit source, Set<String> references) {
        if (source == null || analysis.failedParseFilePaths().isEmpty()) return;
        Set<String> requiredTypes = source.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk())
                .map(i -> i.getNameAsString()).collect(java.util.stream.Collectors.toSet());
        references.stream().filter(name -> name.contains(".")).forEach(requiredTypes::add);
        for (String name : references) {
            if (name.contains(".") || requiredTypes.stream().anyMatch(type -> type.endsWith("." + name))) continue;
            String samePackage = owner.packageName() == null || owner.packageName().isBlank()
                    ? name : owner.packageName() + "." + name;
            requiredTypes.add(samePackage);
            if (analysis.classes().stream().noneMatch(c -> samePackage.equals(c.qualifiedName()))) {
                source.getImports().stream().filter(i -> !i.isStatic() && i.isAsterisk())
                        .map(i -> i.getNameAsString() + "." + name).forEach(requiredTypes::add);
            }
        }
        // Chỉ bỏ qua file lỗi ở phần không liên quan; không được thiếu source của dependency đang dùng.
        for (String failed : analysis.failedParseFilePaths()) {
            String path = "/" + failed.replace('\\', '/');
            String sourceRoot = "/src/main/java/";
            int rootIndex = path.lastIndexOf(sourceRoot);
            String declaringType = rootIndex < 0 || !path.endsWith(".java") ? null
                    : path.substring(rootIndex + sourceRoot.length(), path.length() - 5).replace('/', '.');
            if (requiredTypes.stream().filter(type -> analysis.classes().stream()
                    .noneMatch(c -> type.equals(c.qualifiedName()) && c.sourceCode() != null
                            && !ServiceScopeResolver.modulePath(failed).equals(ServiceScopeResolver.modulePath(owner.filePath()))
                            && ServiceScopeResolver.modulePath(c.filePath()).equals(ServiceScopeResolver.modulePath(owner.filePath()))))
                    .anyMatch(type -> path.endsWith("/" + type.replace('.', '/') + ".java")
                            || declaringType != null && type.startsWith(declaringType + "."))) {
                throw new LlmResponseException("Cannot parse referenced production source " + failed + " required by " + owner.qualifiedName());
            }
        }
    }

    private Set<String> sourceTypeReferences(CompilationUnit source) {
        if (source == null) return Set.of();
        Set<String> references = new java.util.HashSet<>();
        source.findAll(ClassOrInterfaceType.class)
                .forEach(type -> references.add(type.getNameWithScope()));
        source.findAll(NameExpr.class).stream()
                .filter(name -> name.getParentNode().map(parent -> parent instanceof MethodCallExpr
                        || parent instanceof com.github.javaparser.ast.expr.FieldAccessExpr).orElse(false))
                .forEach(name -> references.add(name.getNameAsString()));
        return references;
    }

    private boolean isUnitTestDependency(JavaClassDto candidate, ClassContextDto owner,
            CompilationUnit source, Set<String> references, List<JavaClassDto> candidates) {
        if (references.contains(candidate.qualifiedName())) return true;
        if (source != null) {
            var explicitImport = source.getImports().stream().filter(value -> !value.isStatic() && !value.isAsterisk())
                    .filter(value -> value.getName().getIdentifier().equals(candidate.className())).findFirst();
            if (explicitImport.isPresent()) {
                return explicitImport.get().getNameAsString().equals(candidate.qualifiedName());
            }
        }
        if (!references.contains(candidate.className())) return false;
        boolean samePackageTypeExists = candidates.stream()
                .anyMatch(other -> java.util.Objects.equals(other.className(), candidate.className())
                        && java.util.Objects.equals(other.packageName(), owner.packageName()));
        if (samePackageTypeExists) return java.util.Objects.equals(owner.packageName(), candidate.packageName());
        List<String> wildcardMatches = source == null ? List.of() : candidates.stream()
                .filter(other -> java.util.Objects.equals(other.className(), candidate.className()))
                .filter(other -> source.getImports().stream().anyMatch(value -> !value.isStatic() && value.isAsterisk()
                        && value.getNameAsString().equals(other.packageName())))
                .map(JavaClassDto::qualifiedName).distinct().toList();
        if (wildcardMatches.size() == 1) {
            return java.util.Objects.equals(wildcardMatches.get(0), candidate.qualifiedName());
        }
        // Source phân tích có thể chỉ chứa khai báo class, thiếu imports: chỉ suy ra tên khi không mơ hồ.
        if (!commonBasePackage(owner.packageName(), candidate.packageName())) return false;
        long matchingTypes = candidates.stream()
                .filter(other -> java.util.Objects.equals(other.className(), candidate.className()))
                .filter(other -> commonBasePackage(owner.packageName(), other.packageName()))
                .map(JavaClassDto::qualifiedName).distinct().count();
        if (matchingTypes > 1 || wildcardMatches.size() > 1) {
            throw new LlmResponseException("Cannot resolve referenced production type '" + candidate.className()
                    + "' in " + owner.qualifiedName() + "; reanalyze the project to provide package and imports.");
        }
        return matchingTypes == 1;
    }

    private boolean commonBasePackage(String pkg1, String pkg2) {
        if (pkg1 == null || pkg2 == null) return false;
        if (pkg1.equals(pkg2) || pkg1.startsWith(pkg2 + ".") || pkg2.startsWith(pkg1 + ".")) return true;
        String[] parts1 = pkg1.split("\\.");
        String[] parts2 = pkg2.split("\\.");
        if (parts1.length >= 2 && parts2.length >= 2) {
            return parts1[0].equals(parts2[0]) && parts1[1].equals(parts2[1]);
        }
        return false;
    }

    private List<GeneratedUnitTestContextDto> generatedUnitTests(List<TestCaseContextItemDto> testCases) {
        return testCases.stream()
                .map(testCase -> unitTestRepository.findByTestCaseId(testCase.id()))
                .filter(java.util.Objects::nonNull)
                .map(unitTest -> new GeneratedUnitTestContextDto(
                        unitTest.getTestCaseId(),
                        unitTest.getTestClassName(),
                        unitTest.getTestMethodName(),
                        unitTest.getPackageName(),
                        unitTest.getFilePath(),
                        trimmed(unitTest.getSourceCode())))
                .toList();
    }

    private ProjectContextDto project(AnalysisResultDto analysis) {
        return new ProjectContextDto(analysis.projectId(), analysis.projectName(), analysis.status());
    }

    private AnalysisSummaryDto summary(Long projectId, AnalysisResultDto analysis) {
        return new AnalysisSummaryDto(
                analysis.totalClasses(),
                analysis.totalMethods(),
                analysis.totalEndpoints(),
                analysis.totalRelations(),
                analysis.totalControllerServiceRelations(),
                analysis.existingTestFiles(),
                analysis.totalProductionFiles(),
                analysis.parsedProductionFiles(),
                analysis.failedParseFiles(),
                sorted(analysis.failedParseFilePaths()),
                null);
    }

    private List<ClassContextDto> classes(AnalysisResultDto analysis, Set<Long> selectedMethodIds) {
        return classes(analysis, selectedMethodIds, false);
    }

    private List<ClassContextDto> classes(
            AnalysisResultDto analysis, Set<Long> selectedMethodIds, boolean includeClassSource) {
        return analysis.classes().stream()
                .map(javaClass -> classContext(javaClass, selectedMethodIds, includeClassSource))
                .filter(javaClass -> !javaClass.methods().isEmpty())
                .sorted(Comparator.comparing(ClassContextDto::filePath, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ClassContextDto::qualifiedName))
                .toList();
    }

    private ClassContextDto classContext(
            JavaClassDto javaClass, Set<Long> selectedMethodIds, boolean includeClassSource) {
        List<MethodContextDto> methods = javaClass.methods().stream()
                .filter(method -> selectedMethodIds.contains(method.id()))
                .map(method -> methodContext(javaClass, method))
                .sorted(Comparator.comparing(MethodContextDto::lineStart, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(MethodContextDto::id))
                .toList();
        return new ClassContextDto(
                javaClass.id(),
                javaClass.packageName(),
                javaClass.className(),
                javaClass.qualifiedName(),
                javaClass.classType(),
                javaClass.filePath(),
                includeClassSource ? javaClass.sourceCode() : null,
                javaClass.annotations(),
                methods,
                includeClassSource ? javaClass.sourceCode() : null);
    }

    private MethodContextDto methodContext(JavaClassDto javaClass, JavaMethodDto method) {
        return new MethodContextDto(
                method.id(),
                javaClass.qualifiedName(),
                method.methodName(),
                method.returnType(),
                method.parameters(),
                sorted(method.throwsList()),
                method.visibility(),
                trimmed(method.sourceCode()),
                method.lineStart(),
                method.lineEnd(),
                method.annotations(),
                method.endpoints().stream()
                        .sorted(Comparator.comparing(endpoint -> endpoint.httpMethod() + " " + endpoint.path()))
                        .toList(),
                method.branches());
    }

    private List<BusinessRuleContextDto> businessRules(Long projectId) {
        return inputSnapshot(new InputReadKey("rules",projectId,false),()->loadBusinessRules(projectId));
    }

    private List<BusinessRuleContextDto> loadBusinessRules(Long projectId) {
        Map<Long, BusinessRuleContextDto> rules = businessRuleRepository.findByProjectId(projectId).stream()
                .map(this::ruleContext)
                .collect(java.util.stream.Collectors.toMap(BusinessRuleContextDto::id, rule -> rule));
        overlay(projectId, com.greytest.entity.enums.SourceUpdateTargetType.BUSINESS_RULE, rules, (old, item) -> {
            var value = readDraft(item, com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto.class);
            if (!java.util.Objects.equals(old.methodId(), value.methodId())) throw new IllegalArgumentException("Draft rule changed its source method");
            return new BusinessRuleContextDto(old.id(), old.methodId(), old.ruleCode(), value.description(),
                    old.reviewNote(), old.source(), old.status(), old.isModified(), value.branchId());
        });
        return rules.values().stream()
                .sorted(Comparator.comparingInt((BusinessRuleContextDto rule) -> ruleNumber(rule.ruleCode()))
                        .thenComparing(BusinessRuleContextDto::ruleCode))
                .toList();
    }

    private List<BusinessRuleContextDto> approvedBusinessRules(Long projectId) {
        return businessRules(projectId).stream()
                .filter(rule -> ReviewStatus.APPROVED.name().equals(rule.status())
                        || GenerationJobContext.sourceUpdateId() != null && ReviewStatus.PENDING_REVIEW.name().equals(rule.status()))
                .toList();
    }

    private int ruleNumber(String code) {
        try {
            return code != null && code.startsWith("BR-") ? Integer.parseInt(code.substring(3)) : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private BusinessRuleContextDto ruleContext(BusinessRule rule) {
        return new BusinessRuleContextDto(
                rule.getId(),
                rule.getMethodId(),
                rule.getRuleCode(),
                rule.getDescription(),
                rule.getReviewNote(),
                rule.getSource() == null ? null : rule.getSource().name(),
                rule.getStatus() == null ? null : rule.getStatus().name(),
                rule.getIsModified(),
                sourceBranchId(rule.getReviewNote()));
    }

    private List<ExistingTestContextDto> existingTests(Long projectId, boolean includeSource) {
        return inputSnapshot(new InputReadKey("existingTests",projectId,includeSource),()->loadExistingTests(projectId,includeSource));
    }

    private List<ExistingTestContextDto> loadExistingTests(Long projectId,boolean includeSource) {
        return existingTestService.list(projectId).stream()
                .map(test -> existingTestContext(test, includeSource))
                .sorted(Comparator.comparing(ExistingTestContextDto::filePath))
                .toList();
    }

    private List<ExistingTestContextDto> relevantExistingTests(
            Long projectId,
            Set<Long> targetMethodIds,
            Set<Long> targetClassIds) {
        return relevantExistingTests(projectId, targetMethodIds, targetClassIds, false);
    }

    private List<ExistingTestContextDto> relevantExistingTests(
            Long projectId,
            Set<Long> targetMethodIds,
            Set<Long> targetClassIds,
            boolean includeSource) {
        return existingTests(projectId, includeSource).stream()
                .filter(test -> test.relatedMethodId() != null && targetMethodIds.contains(test.relatedMethodId())
                        || test.relatedClassId() != null && targetClassIds.contains(test.relatedClassId()))
                .limit(MAX_UNIT_TEST_REFERENCES)
                .toList();
    }

    private ExistingTestContextDto existingTestContext(ExistingTestDto test, boolean includeSource) {
        return new ExistingTestContextDto(
                test.id(),
                test.filePath(),
                test.packageName(),
                test.testClassName(),
                test.relatedClassId(),
                test.relatedMethodId(),
                test.testMethods(),
                sorted(test.imports()),
                includeSource ? trimmed(test.sourceCode()) : null);
    }

    private List<TestPlanContextItemDto> approvedTestPlans(Long projectId) {
        return inputSnapshot(new InputReadKey("plans",projectId,false),()->loadApprovedTestPlans(projectId));
    }

    private List<TestPlanContextItemDto> loadApprovedTestPlans(Long projectId) {
        List<TestPlan> plans = testPlanRepository.findByProjectId(projectId).stream()
                .filter(plan -> plan.getStatus() == ReviewStatus.APPROVED
                        || GenerationJobContext.sourceUpdateId() != null && plan.getStatus() == ReviewStatus.PENDING_REVIEW)
                .sorted(Comparator.comparing(TestPlan::getPlanCode))
                .toList();
        Map<Long, List<Long>> coveredRuleIds = coveredRuleIdsByPlan(plans);
        Map<Long, TestPlanContextItemDto> contexts = plans.stream()
                .map(plan -> testPlanContext(plan, coveredRuleIds.getOrDefault(plan.getId(), List.of(plan.getBusinessRuleId()))))
                .collect(java.util.stream.Collectors.toMap(TestPlanContextItemDto::id, plan -> plan));
        overlay(projectId, com.greytest.entity.enums.SourceUpdateTargetType.TEST_PLAN, contexts, (old, item) -> {
            var value = readDraft(item, com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto.class);
            if (!java.util.Objects.equals(old.businessRuleId(), value.ruleId())) throw new IllegalArgumentException("Draft plan changed its parent rule");
            var effectiveRules = businessRules(projectId);
            var covered = value.coveredRuleIds() == null ? List.of(value.ruleId()) : value.coveredRuleIds();
            if (covered.stream().anyMatch(id -> effectiveRules.stream().noneMatch(rule -> java.util.Objects.equals(rule.id(), id)
                    && java.util.Objects.equals(rule.methodId(), value.methodId())))) {
                throw new IllegalArgumentException("Draft plan references an unrelated business rule");
            }
            return new TestPlanContextItemDto(old.id(), old.businessRuleId(), covered, old.planCode(), value.title(), value.description(),
                    value.testType(), old.status(), old.isModified());
        });
        return contexts.values().stream().sorted(Comparator.comparing(TestPlanContextItemDto::planCode)).toList();
    }

    private TestPlanContextItemDto testPlanContext(TestPlan plan, List<Long> coveredRuleIds) {
        return new TestPlanContextItemDto(
                plan.getId(), plan.getBusinessRuleId(), coveredRuleIds, plan.getPlanCode(), plan.getTitle(), plan.getDescription(),
                plan.getTestType() == null ? null : plan.getTestType().name(),
                plan.getStatus() == null ? null : plan.getStatus().name(), plan.getIsModified());
    }

    private Map<Long, List<Long>> coveredRuleIdsByPlan(List<TestPlan> plans) {
        if (plans.isEmpty()) return Map.of();
        List<Long> planIds = plans.stream().map(TestPlan::getId).toList();
        return testPlanCoveredRuleRepository.findByTestPlanIdIn(planIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        TestPlanCoveredRule::getTestPlanId,
                        java.util.stream.Collectors.mapping(
                                TestPlanCoveredRule::getBusinessRuleId,
                                java.util.stream.Collectors.collectingAndThen(
                                        java.util.stream.Collectors.toCollection(java.util.TreeSet::new),
                                        List::copyOf))));
    }

    private List<TestCaseContextItemDto> approvedTestCases(Long projectId) {
        return inputSnapshot(new InputReadKey("cases",projectId,false),()->loadApprovedTestCases(projectId));
    }

    private List<TestCaseContextItemDto> loadApprovedTestCases(Long projectId) {
        Map<Long, TestCaseContextItemDto> contexts = approvedTestPlans(projectId).stream()
                .flatMap(plan -> testCaseRepository.findByTestPlanId(plan.id()).stream())
                .filter(testCase -> testCase.getStatus() == ReviewStatus.APPROVED
                        || GenerationJobContext.sourceUpdateId() != null && testCase.getStatus() == ReviewStatus.PENDING_REVIEW)
                .map(this::testCaseContext)
                .collect(java.util.stream.Collectors.toMap(TestCaseContextItemDto::id, testCase -> testCase));
        overlay(projectId, com.greytest.entity.enums.SourceUpdateTargetType.TEST_CASE, contexts, (old, item) -> {
            var value = readDraft(item, com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto.class);
            if (!java.util.Objects.equals(old.testPlanId(), value.planId())) throw new IllegalArgumentException("Draft case changed its parent plan");
            return new TestCaseContextItemDto(old.id(), old.testPlanId(), old.caseCode(), value.testType(), value.description(),
                    value.preconditions(), value.testData(), value.expectedResult(), value.priority(), value.traceSource(), old.status(), old.isModified());
        });
        return contexts.values().stream().sorted(Comparator.comparing(TestCaseContextItemDto::caseCode)).toList();
    }

    /** Đọc proposal mỗi lần để stage sau nhìn thấy nội dung người dùng vừa sửa trong đúng draft. */
    private <T> void overlay(Long projectId, com.greytest.entity.enums.SourceUpdateTargetType type,
            Map<Long, T> contexts, java.util.function.BiFunction<T, com.greytest.entity.SourceUpdateItem, T> apply) {
        Long updateId = GenerationJobContext.sourceUpdateId();
        if (updateId == null || sourceUpdates == null || sourceUpdateItems == null) return;
        sourceUpdates.findByIdAndProjectId(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Draft does not belong to selected project"));
        for (var item : sourceUpdateItems.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId)) {
            if (item.getTargetType() != type) continue;
            if (item.getTargetId() == null) throw new IllegalArgumentException("Draft artifact has no logical ID");
            boolean rejected = item.getReviewStatus() == com.greytest.entity.enums.SourceUpdateReviewStatus.REJECTED
                    || item.getAction()==com.greytest.entity.enums.SourceUpdateAction.KEEP;
            if (rejected) {
                if (item.getAction() == com.greytest.entity.enums.SourceUpdateAction.CREATE) contexts.remove(item.getTargetId());
                continue;
            }
            T previous = contexts.get(item.getTargetId());
            if (previous == null) {
                // Artifact con của một parent đã REMOVE không tham gia context downstream.
                if (type == com.greytest.entity.enums.SourceUpdateTargetType.TEST_CASE) continue;
                throw new IllegalArgumentException("Draft artifact is outside the selected project");
            }
            if (item.getAction() == com.greytest.entity.enums.SourceUpdateAction.REMOVE) contexts.remove(item.getTargetId());
            else contexts.put(item.getTargetId(), apply.apply(previous, item));
        }
    }

    private <T> T readDraft(com.greytest.entity.SourceUpdateItem item, Class<T> type) {
        try {
            return objectMapper.readValue(item.getAfterData(), type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid draft artifact " + item.getId(), exception);
        }
    }

    private List<TestCaseContextItemDto> approvedTestCases(List<TestPlanContextItemDto> plans) {
        return plans.stream()
                .flatMap(plan -> testCaseRepository.findByTestPlanId(plan.id()).stream())
                .filter(testCase -> testCase.getStatus() == ReviewStatus.APPROVED
                        || GenerationJobContext.sourceUpdateId() != null && testCase.getStatus() == ReviewStatus.PENDING_REVIEW)
                .map(this::testCaseContext)
                .sorted(Comparator.comparing(TestCaseContextItemDto::caseCode))
                .toList();
    }

    private TestCaseContextItemDto testCaseContext(TestCase testCase) {
        return new TestCaseContextItemDto(
                testCase.getId(), testCase.getTestPlanId(), testCase.getCaseCode(),
                testCase.getTestType() == null ? null : testCase.getTestType().name(), testCase.getDescription(),
                testCase.getPreconditions(), testCase.getTestData(), testCase.getExpectedResult(),
                testCase.getPriority() == null ? null : testCase.getPriority().name(), testCase.getTraceSource(),
                testCase.getStatus() == null ? null : testCase.getStatus().name(), testCase.getIsModified());
    }

    private Set<Long> serviceMethodIds(AnalysisResultDto analysis) {
        return analysis.classes().stream()
                .filter(javaClass -> SERVICE_CLASS_TYPE.equals(javaClass.classType()))
                .flatMap(javaClass -> javaClass.methods().stream())
                .map(JavaMethodDto::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    private Set<Long> methodIds(List<BusinessRuleContextDto> rules) {
        return rules.stream()
                .map(BusinessRuleContextDto::methodId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    private List<String> sorted(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    private List<ServiceRelationDto> serviceRelations(AnalysisResultDto analysis) {
        return analysis.relations().stream()
                .sorted(Comparator.comparing(ServiceRelationDto::serviceQualifiedName)
                        .thenComparing(ServiceRelationDto::repositoryQualifiedName))
                .toList();
    }

    private List<ControllerServiceRelationDto> controllerServiceRelations(AnalysisResultDto analysis) {
        return analysis.controllerServiceRelations().stream()
                .sorted(Comparator.comparing(ControllerServiceRelationDto::controllerQualifiedName)
                        .thenComparing(ControllerServiceRelationDto::controllerMethodName)
                        .thenComparing(ControllerServiceRelationDto::serviceQualifiedName)
                        .thenComparing(ControllerServiceRelationDto::serviceMethodName))
                .toList();
    }

    private String trimmed(String sourceCode) {
        if (sourceCode == null || sourceCode.length() <= MAX_METHOD_SOURCE_CHARS) {
            return sourceCode;
        }
        return sourceCode.substring(0, MAX_METHOD_SOURCE_CHARS) + "\n/* truncated */";
    }

    /** Chỉ dùng source callee đầy đủ để tránh suy luận invariant từ đoạn bị cắt. */
    private String completeMethodSource(String sourceCode) {
        if (sourceCode == null || sourceCode.length() > MAX_METHOD_SOURCE_CHARS) {
            return null;
        }
        return sourceCode;
    }

    private String sourceBranchId(String reviewNote) {
        if (reviewNote == null || !reviewNote.startsWith("SOURCE_BRANCH:")) return null;
        int lineEnd = reviewNote.indexOf('\n');
        String value = reviewNote.substring("SOURCE_BRANCH:".length(), lineEnd < 0 ? reviewNote.length() : lineEnd);
        if (value.isBlank()) return null;
        String normalized = value.trim();
        int outcomeSeparator = normalized.indexOf("::");
        return outcomeSeparator < 0
                ? normalized.replaceFirst("-(TRUE|FALSE)$", "")
                : normalized.substring(0, outcomeSeparator);
    }
}
