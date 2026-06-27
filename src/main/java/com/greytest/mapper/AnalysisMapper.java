package com.greytest.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.greytest.dto.EndpointDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.ControllerServiceRelationDto;
import com.greytest.dto.RelevantAnnotationDto;
import com.greytest.dto.ServiceRelationDto;
import com.greytest.entity.ControllerServiceRelation;
import com.greytest.entity.Endpoint;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.MethodParam;
import com.greytest.entity.RelevantAnnotation;
import com.greytest.entity.ServiceRepositoryRelation;

/**
 * Mapper chuyển đổi Analysis entities sang DTO cho API response.
 */
@Component
public class AnalysisMapper {

    public JavaClassDto toDto(JavaClass entity, List<JavaMethod> methods,
            List<Endpoint> allEndpoints,
            List<RelevantAnnotation> classAnnotations,
            List<RelevantAnnotation> methodAnnotations) {
        List<JavaMethodDto> methodDtos = methods.stream()
                .map(m -> toMethodDto(
                        m,
                        allEndpoints,
                        methodAnnotations.stream()
                                .filter(annotation -> annotation.getMethodId() != null
                                        && annotation.getMethodId().equals(m.getId()))
                                .toList()))
                .toList();

        return new JavaClassDto(
                entity.getId(),
                entity.getPackageName(),
                entity.getClassName(),
                entity.getQualifiedName(),
                entity.getClassType() != null ? entity.getClassType().name() : "OTHER",
                entity.getFilePath(),
                classAnnotations.stream().map(this::toAnnotationDto).toList(),
                methodDtos
        );
    }

    public JavaMethodDto toMethodDto(JavaMethod method, List<Endpoint> allEndpoints,
            List<RelevantAnnotation> annotations) {
        List<MethodParamDto> paramDtos = method.getParameters() != null
                ? method.getParameters().stream().map(this::toParamDto).toList()
                : List.of();

        List<EndpointDto> endpointDtos = allEndpoints.stream()
                .filter(e -> e.getMethodId() != null && e.getMethodId().equals(method.getId()))
                .map(e -> toEndpointDto(e, method.getMethodName()))
                .toList();

        return new JavaMethodDto(
                method.getId(),
                method.getMethodName(),
                method.getReturnType(),
                paramDtos,
                method.getThrowsList() != null ? method.getThrowsList() : List.of(),
                method.getVisibility() != null ? method.getVisibility().name() : null,
                method.getSourceCode(),
                method.getLineStart(),
                method.getLineEnd(),
                annotations.stream().map(this::toAnnotationDto).toList(),
                endpointDtos
        );
    }

    public EndpointDto toEndpointDto(Endpoint endpoint, String methodName) {
        return new EndpointDto(
                endpoint.getId(),
                endpoint.getHttpMethod() != null ? endpoint.getHttpMethod().name() : null,
                endpoint.getPath(),
                endpoint.getConsumes(),
                endpoint.getProduces(),
                methodName
        );
    }

    public MethodParamDto toParamDto(MethodParam param) {
        return new MethodParamDto(param.name(), param.type());
    }

    public RelevantAnnotationDto toAnnotationDto(RelevantAnnotation annotation) {
        return new RelevantAnnotationDto(
                annotation.getId(),
                annotation.getTargetType() != null ? annotation.getTargetType().name() : null,
                annotation.getCategory() != null ? annotation.getCategory().name() : null,
                annotation.getAnnotationName(),
                annotation.getAttributes());
    }

    public ServiceRelationDto toRelationDto(ServiceRepositoryRelation relation,
            String serviceClassName,
            String serviceQualifiedName,
            String repositoryClassName,
            String repositoryQualifiedName) {
        return new ServiceRelationDto(
                relation.getId(),
                serviceClassName,
                serviceQualifiedName,
                repositoryClassName,
                repositoryQualifiedName
        );
    }

    public ControllerServiceRelationDto toControllerServiceRelationDto(
            ControllerServiceRelation relation,
            String controllerClassName,
            String controllerQualifiedName,
            String controllerMethodName,
            String serviceClassName,
            String serviceQualifiedName,
            String serviceMethodName) {
        return new ControllerServiceRelationDto(
                relation.getId(),
                controllerClassName,
                controllerQualifiedName,
                controllerMethodName,
                serviceClassName,
                serviceQualifiedName,
                serviceMethodName,
                relation.getServiceFieldName(),
                relation.getServiceFieldType());
    }
}
