package com.greytest.service.agent;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.ControllerServiceRelationDto;
import com.greytest.dto.ExistingTestDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.ServiceRelationDto;
import com.greytest.dto.agent.GenerationContextDtos.AnalysisSummaryDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleGenerationContextDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleReviewContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ExistingTestContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ProjectContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.service.analysis.AnalysisManifestService;
import com.greytest.service.analysis.AnalysisService;
import com.greytest.service.analysis.ExistingTestService;

/**
 * Gom context grey-box theo schema on dinh truoc khi dua sang prompt/LLM.
 */
@Service
public class GenerationContextBuilder {

    private static final String SERVICE_CLASS_TYPE = "SERVICE";
    // ponytail: cap theo method de prompt khong phinh vo han; thay bang token-aware chunking khi can LLM that.
    private static final int MAX_METHOD_SOURCE_CHARS = 4_000;

    private final AnalysisService analysisService;
    private final AnalysisManifestService manifestService;
    private final ExistingTestService existingTestService;
    private final BusinessRuleRepository businessRuleRepository;
    private final TestPlanRepository testPlanRepository;
    private final TestCaseRepository testCaseRepository;

    public GenerationContextBuilder(
            AnalysisService analysisService,
            AnalysisManifestService manifestService,
            ExistingTestService existingTestService,
            BusinessRuleRepository businessRuleRepository,
            TestPlanRepository testPlanRepository,
            TestCaseRepository testCaseRepository) {
        this.analysisService = analysisService;
        this.manifestService = manifestService;
        this.existingTestService = existingTestService;
        this.businessRuleRepository = businessRuleRepository;
        this.testPlanRepository = testPlanRepository;
        this.testCaseRepository = testCaseRepository;
    }

    /** Context cho AI tu sinh Business Rule tu cac service method. */
    @Transactional(readOnly = true)
    public BusinessRuleGenerationContextDto buildBusinessRuleGenerationContext(Long projectId) {
        AnalysisResultDto analysis = analysisService.getAnalysisResult(projectId);
        return new BusinessRuleGenerationContextDto(
                project(analysis),
                summary(projectId, analysis),
                classes(analysis, serviceMethodIds(analysis)),
                serviceRelations(analysis),
                controllerServiceRelations(analysis),
                existingTests(projectId, false));
    }

    /** Context cho AI review Business Rule user da nhap. */
    @Transactional(readOnly = true)
    public BusinessRuleReviewContextDto buildBusinessRuleReviewContext(Long projectId) {
        AnalysisResultDto analysis = analysisService.getAnalysisResult(projectId);
        return new BusinessRuleReviewContextDto(
                project(analysis),
                summary(projectId, analysis),
                classes(analysis, serviceMethodIds(analysis)),
                serviceRelations(analysis),
                controllerServiceRelations(analysis),
                businessRules(projectId),
                existingTests(projectId, false));
    }

    /** Context cho sinh Test Plan tu Business Rule da approve. */
    @Transactional(readOnly = true)
    public TestPlanContextDto buildTestPlanContext(Long projectId) {
        AnalysisResultDto analysis = analysisService.getAnalysisResult(projectId);
        List<BusinessRuleContextDto> approvedRules = approvedBusinessRules(projectId);
        return new TestPlanContextDto(
                project(analysis),
                summary(projectId, analysis),
                classes(analysis, methodIds(approvedRules)),
                approvedRules,
                existingTests(projectId, false));
    }

    /** Context cho sinh Test Case tu Test Plan da approve. */
    @Transactional(readOnly = true)
    public TestCaseContextDto buildTestCaseContext(Long projectId) {
        AnalysisResultDto analysis = analysisService.getAnalysisResult(projectId);
        List<BusinessRuleContextDto> approvedRules = approvedBusinessRules(projectId);
        return new TestCaseContextDto(
                project(analysis),
                summary(projectId, analysis),
                classes(analysis, methodIds(approvedRules)),
                approvedRules,
                approvedTestPlans(projectId),
                existingTests(projectId, false));
    }

    /** Context cho sinh/cai thien Unit Test, gom existing tests rieng de khong tinh vao production counters. */
    @Transactional(readOnly = true)
    public UnitTestContextDto buildUnitTestContext(Long projectId) {
        AnalysisResultDto analysis = analysisService.getAnalysisResult(projectId);
        List<BusinessRuleContextDto> approvedRules = approvedBusinessRules(projectId);
        return new UnitTestContextDto(
                project(analysis),
                summary(projectId, analysis),
                classes(analysis, methodIds(approvedRules)),
                approvedRules,
                approvedTestPlans(projectId),
                approvedTestCases(projectId),
                existingTests(projectId, true));
    }

    private ProjectContextDto project(AnalysisResultDto analysis) {
        return new ProjectContextDto(analysis.projectId(), analysis.projectName(), analysis.status());
    }

    private AnalysisSummaryDto summary(Long projectId, AnalysisResultDto analysis) {
        AnalysisManifestDto manifest = manifestService.exportManifest(projectId);
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
                manifest);
    }

    private List<ClassContextDto> classes(AnalysisResultDto analysis, Set<Long> selectedMethodIds) {
        return analysis.classes().stream()
                .map(javaClass -> classContext(javaClass, selectedMethodIds))
                .filter(javaClass -> !javaClass.methods().isEmpty())
                .sorted(Comparator.comparing(ClassContextDto::qualifiedName))
                .toList();
    }

    private ClassContextDto classContext(JavaClassDto javaClass, Set<Long> selectedMethodIds) {
        List<MethodContextDto> methods = javaClass.methods().stream()
                .filter(method -> selectedMethodIds.contains(method.id()))
                .map(method -> methodContext(javaClass, method))
                .sorted(Comparator.comparing(MethodContextDto::methodName).thenComparing(MethodContextDto::id))
                .toList();
        return new ClassContextDto(
                javaClass.id(),
                javaClass.packageName(),
                javaClass.className(),
                javaClass.qualifiedName(),
                javaClass.classType(),
                javaClass.filePath(),
                javaClass.annotations(),
                methods);
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
                        .toList());
    }

    private List<BusinessRuleContextDto> businessRules(Long projectId) {
        return businessRuleRepository.findByProjectId(projectId).stream()
                .map(this::ruleContext)
                .sorted(Comparator.comparing(BusinessRuleContextDto::ruleCode))
                .toList();
    }

    private List<BusinessRuleContextDto> approvedBusinessRules(Long projectId) {
        return businessRuleRepository.findByProjectIdAndStatus(projectId, ReviewStatus.APPROVED).stream()
                .map(this::ruleContext)
                .sorted(Comparator.comparing(BusinessRuleContextDto::ruleCode))
                .toList();
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
                rule.getIsModified());
    }

    private List<ExistingTestContextDto> existingTests(Long projectId, boolean includeSource) {
        return existingTestService.list(projectId).stream()
                .map(test -> existingTestContext(test, includeSource))
                .sorted(Comparator.comparing(ExistingTestContextDto::filePath))
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
                includeSource ? test.sourceCode() : null);
    }

    private List<TestPlanContextItemDto> approvedTestPlans(Long projectId) {
        return testPlanRepository.findByProjectId(projectId).stream()
                .filter(plan -> plan.getStatus() == ReviewStatus.APPROVED)
                .map(this::testPlanContext)
                .sorted(Comparator.comparing(TestPlanContextItemDto::planCode))
                .toList();
    }

    private TestPlanContextItemDto testPlanContext(TestPlan plan) {
        return new TestPlanContextItemDto(
                plan.getId(), plan.getBusinessRuleId(), plan.getPlanCode(), plan.getTitle(), plan.getDescription(),
                plan.getTestType() == null ? null : plan.getTestType().name(),
                plan.getStatus() == null ? null : plan.getStatus().name(), plan.getIsModified());
    }

    private List<TestCaseContextItemDto> approvedTestCases(Long projectId) {
        return approvedTestPlans(projectId).stream()
                .flatMap(plan -> testCaseRepository.findByTestPlanId(plan.id()).stream())
                .filter(testCase -> testCase.getStatus() == ReviewStatus.APPROVED)
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
}
