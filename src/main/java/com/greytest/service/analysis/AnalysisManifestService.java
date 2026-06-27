package com.greytest.service.analysis;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.AnalysisManifestInput;
import com.greytest.dto.AnalysisManifestValidationDto;
import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.ControllerServiceRelationDto;
import com.greytest.dto.EndpointDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.ManifestCategoryDiffDto;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.RelevantAnnotationDto;
import com.greytest.dto.ServiceRelationDto;

/**
 * Xuất manifest deterministic từ kết quả static analysis và so sánh với ground truth.
 * Mọi entry dùng fully-qualified type name và method signature để không mơ hồ.
 */
@Service
public class AnalysisManifestService {

    private static final String MANIFEST_VERSION = "1.1";

    private final AnalysisService analysisService;

    public AnalysisManifestService(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** Xuất manifest JSON-ready của project theo thứ tự ổn định. */
    @Transactional(readOnly = true)
    public AnalysisManifestDto exportManifest(Long projectId) {
        return buildManifest(analysisService.getAnalysisResult(projectId));
    }

    /** So sánh manifest thực tế với ground truth và trả missing/unexpected từng nhóm. */
    @Transactional(readOnly = true)
    public AnalysisManifestValidationDto validateManifest(Long projectId, AnalysisManifestInput expected) {
        AnalysisManifestDto actual = exportManifest(projectId);
        ManifestCategoryDiffDto classDiff = compare(expected.classes(), actual.classes());
        ManifestCategoryDiffDto methodDiff = compare(expected.methods(), actual.methods());
        ManifestCategoryDiffDto endpointDiff = compare(expected.endpoints(), actual.endpoints());
        ManifestCategoryDiffDto annotationDiff = compare(expected.annotations(), actual.annotations());
        ManifestCategoryDiffDto relationDiff = compare(
                expected.serviceRepositoryRelations(), actual.serviceRepositoryRelations());
        ManifestCategoryDiffDto controllerServiceRelationDiff = compare(
                expected.controllerServiceRelations(), actual.controllerServiceRelations());
        boolean exactMatch = classDiff.exactMatch()
                && methodDiff.exactMatch()
                && endpointDiff.exactMatch()
                && annotationDiff.exactMatch()
                && relationDiff.exactMatch()
                && controllerServiceRelationDiff.exactMatch();

        return new AnalysisManifestValidationDto(
                projectId,
                actual.projectName(),
                exactMatch,
                classDiff,
                methodDiff,
                endpointDiff,
                annotationDiff,
                relationDiff,
                controllerServiceRelationDiff);
    }

    AnalysisManifestDto buildManifest(AnalysisResultDto analysis) {
        Set<String> classes = new TreeSet<>();
        Set<String> methods = new TreeSet<>();
        Set<String> endpoints = new TreeSet<>();
        Set<String> annotations = new TreeSet<>();

        for (JavaClassDto javaClass : analysis.classes()) {
            classes.add(javaClass.qualifiedName());
            for (RelevantAnnotationDto annotation : javaClass.annotations()) {
                annotations.add("CLASS " + javaClass.qualifiedName() + " " + annotationIdentity(annotation));
            }
            for (JavaMethodDto method : javaClass.methods()) {
                String signature = methodSignature(javaClass.qualifiedName(), method);
                methods.add(signature);
                for (RelevantAnnotationDto annotation : method.annotations()) {
                    annotations.add("METHOD " + signature + " " + annotationIdentity(annotation));
                }
                for (EndpointDto endpoint : method.endpoints()) {
                    endpoints.add(endpoint.httpMethod() + " " + endpoint.path() + " -> " + signature);
                }
            }
        }

        Set<String> relations = new TreeSet<>();
        for (ServiceRelationDto relation : analysis.relations()) {
            relations.add(relation.serviceQualifiedName() + " -> " + relation.repositoryQualifiedName());
        }

        Set<String> controllerServiceRelations = new TreeSet<>();
        for (ControllerServiceRelationDto relation : analysis.controllerServiceRelations()) {
            controllerServiceRelations.add(relation.controllerQualifiedName()
                    + "#" + relation.controllerMethodName()
                    + " -> " + relation.serviceQualifiedName()
                    + "#" + relation.serviceMethodName()
                    + " via " + relation.serviceFieldName());
        }

        return new AnalysisManifestDto(
                analysis.projectId(),
                analysis.projectName(),
                MANIFEST_VERSION,
                List.copyOf(classes),
                List.copyOf(methods),
                List.copyOf(endpoints),
                List.copyOf(annotations),
                List.copyOf(relations),
                List.copyOf(controllerServiceRelations));
    }

    private String annotationIdentity(RelevantAnnotationDto annotation) {
        return annotation.category() + " " + annotation.attributes();
    }

    private String methodSignature(String qualifiedClassName, JavaMethodDto method) {
        String parameterTypes = method.parameters().stream()
                .map(MethodParamDto::type)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String returnType = method.returnType() == null ? "void" : method.returnType();
        return qualifiedClassName + "#" + method.methodName()
                + "(" + parameterTypes + "):" + returnType;
    }

    private ManifestCategoryDiffDto compare(List<String> expected, List<String> actual) {
        Set<String> expectedSet = new TreeSet<>(expected);
        Set<String> actualSet = new TreeSet<>(actual);
        Set<String> missing = new TreeSet<>(expectedSet);
        missing.removeAll(actualSet);
        Set<String> unexpected = new TreeSet<>(actualSet);
        unexpected.removeAll(expectedSet);

        return new ManifestCategoryDiffDto(
                expectedSet.size(),
                actualSet.size(),
                missing.isEmpty() && unexpected.isEmpty(),
                List.copyOf(missing),
                List.copyOf(unexpected));
    }
}
