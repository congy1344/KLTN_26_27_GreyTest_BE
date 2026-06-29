package com.greytest.dto.agent;

import java.util.List;

import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.ControllerServiceRelationDto;
import com.greytest.dto.EndpointDto;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.RelevantAnnotationDto;
import com.greytest.dto.ServiceRelationDto;

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
            List<EndpointDto> endpoints) {
    }

    public record BusinessRuleContextDto(
            Long id,
            Long methodId,
            String ruleCode,
            String description,
            String reviewNote,
            String source,
            String status,
            Boolean isModified) {
    }

    public record ExistingTestContextDto(
            Long id,
            String filePath,
            String packageName,
            String testClassName,
            Long relatedClassId,
            Long relatedMethodId,
            List<?> testMethods,
            List<String> imports) {
    }

    public record BusinessRuleGenerationContextDto(
            ProjectContextDto project,
            AnalysisSummaryDto analysis,
            List<ClassContextDto> classes,
            List<ServiceRelationDto> serviceRepositoryRelations,
            List<ControllerServiceRelationDto> controllerServiceRelations,
            List<ExistingTestContextDto> existingTests) {
    }

    public record BusinessRuleReviewContextDto(
            ProjectContextDto project,
            AnalysisSummaryDto analysis,
            List<ClassContextDto> classes,
            List<ServiceRelationDto> serviceRepositoryRelations,
            List<ControllerServiceRelationDto> controllerServiceRelations,
            List<BusinessRuleContextDto> businessRules,
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
            List<ExistingTestContextDto> existingTests) {
    }

    public record UnitTestContextDto(
            ProjectContextDto project,
            AnalysisSummaryDto analysis,
            List<ClassContextDto> classes,
            List<BusinessRuleContextDto> approvedBusinessRules,
            List<ExistingTestContextDto> existingTests) {
    }
}
