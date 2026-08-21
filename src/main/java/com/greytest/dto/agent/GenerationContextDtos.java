package com.greytest.dto.agent;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.ControllerServiceRelationDto;
import com.greytest.dto.CoverageGapDto;
import com.greytest.dto.EndpointDto;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.RelevantAnnotationDto;
import com.greytest.dto.ServiceRelationDto;
import com.greytest.dto.SourceBranchDto;

public final class GenerationContextDtos {

    private GenerationContextDtos() {
    }

    public record ProjectContextDto(
            Long id,
            String name,
            String status) {
    }

    public record AnalysisSummaryDto(
            int totalClasses,
            int totalMethods,
            int totalEndpoints,
            int totalRelations,
            int totalControllerServiceRelations,
            int existingTestFiles,
            int totalProductionFiles,
            int parsedProductionFiles,
            int failedParseFiles,
            List<String> failedParseFilePaths,
            @JsonIgnore
            AnalysisManifestDto manifest) {
    }

    public record ClassContextDto(
            Long id,
            String packageName,
            String className,
            String qualifiedName,
            String classType,
            String filePath,
            List<RelevantAnnotationDto> annotations,
            List<MethodContextDto> methods) {
    }

    public record MethodContextDto(
            Long id,
            String classQualifiedName,
            String methodName,
            String returnType,
            List<MethodParamDto> parameters,
            List<String> throwsList,
            String visibility,
            String sourceCode,
            Integer lineStart,
            Integer lineEnd,
            List<RelevantAnnotationDto> annotations,
            List<EndpointDto> endpoints,
            List<SourceBranchDto> branches) {
    }

    public record BusinessRuleContextDto(
            Long id,
            Long methodId,
            String ruleCode,
            String description,
            String reviewNote,
            String source,
            String status,
            Boolean isModified,
            String sourceBranchId) {
    }

    public record ExistingTestContextDto(
            Long id,
            String filePath,
            String packageName,
            String testClassName,
            Long relatedClassId,
            Long relatedMethodId,
            List<?> testMethods,
            List<String> imports,
            String sourceCode) {
    }

    public record TestPlanContextItemDto(
            Long id,
            Long businessRuleId,
            List<Long> coveredRuleIds,
            String planCode,
            String title,
            String description,
            String testType,
            String status,
            Boolean isModified) {
    }

    public record TestCaseContextItemDto(
            Long id,
            Long testPlanId,
            String caseCode,
            String testType,
            String description,
            String preconditions,
            Map<String, Object> testData,
            String expectedResult,
            String priority,
            String traceSource,
            String status,
            Boolean isModified) {
    }

    public record GeneratedUnitTestContextDto(
            Long testCaseId,
            String testClassName,
            String testMethodName,
            String packageName,
            String filePath,
            String sourceCode) {
    }

    public record DependencyCallContextDto(
            Long callerMethodId,
            String callerClassQualifiedName,
            String collaboratorName,
            String collaboratorType,
            String calleeClassName,
            String calleeQualifiedName,
            String calleeMethodName,
            String httpMethod,
            String endpointPath) {
    }

    public record BusinessRuleGenerationContextDto(
            ProjectContextDto project,
            AnalysisSummaryDto analysis,
            List<ClassContextDto> classes,
            List<ServiceRelationDto> serviceRepositoryRelations,
            List<ControllerServiceRelationDto> controllerServiceRelations,
            List<DependencyCallContextDto> dependencyCalls,
            List<ExistingTestContextDto> existingTests) {
    }

    public record BusinessRuleReviewContextDto(
            ProjectContextDto project,
            AnalysisSummaryDto analysis,
            List<ClassContextDto> classes,
            List<ServiceRelationDto> serviceRepositoryRelations,
            List<ControllerServiceRelationDto> controllerServiceRelations,
            List<BusinessRuleContextDto> businessRules,
            List<BusinessRuleContextDto> relatedBusinessRules,
            List<ExistingTestContextDto> existingTests) {
    }

    public record TestPlanContextDto(
            ProjectContextDto project,
            AnalysisSummaryDto analysis,
            List<ClassContextDto> classes,
            List<BusinessRuleContextDto> approvedBusinessRules,
            List<ExistingTestContextDto> existingTests) {
    }

    public record TestCaseContextDto(
            ProjectContextDto project,
            AnalysisSummaryDto analysis,
            List<ClassContextDto> classes,
            List<BusinessRuleContextDto> approvedBusinessRules,
            List<TestPlanContextItemDto> approvedTestPlans,
            List<ExistingTestContextDto> existingTests) {
    }

    public record UnitTestContextDto(
            ProjectContextDto project,
            AnalysisSummaryDto analysis,
            List<ClassContextDto> classes,
            List<BusinessRuleContextDto> approvedBusinessRules,
            List<TestPlanContextItemDto> approvedTestPlans,
            List<TestCaseContextItemDto> approvedTestCases,
            List<TestCaseContextItemDto> existingApprovedTestCases,
            List<GeneratedUnitTestContextDto> previousGeneratedUnitTests,
            List<ExistingTestContextDto> existingTests) {
    }

    public record CoverageRefinementContextDto(
            ProjectContextDto project,
            AnalysisSummaryDto analysis,
            List<ClassContextDto> classes,
            List<BusinessRuleContextDto> approvedBusinessRules,
            List<TestPlanContextItemDto> approvedTestPlans,
            List<TestCaseContextItemDto> approvedTestCases,
            List<ExistingTestContextDto> existingTests,
            int round,
            List<CoverageGapDto> coverageGaps) {
    }
}
