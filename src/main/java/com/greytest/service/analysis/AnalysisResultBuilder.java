package com.greytest.service.analysis;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.ControllerServiceRelationDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.ServiceRelationDto;
import com.greytest.entity.ControllerServiceRelation;
import com.greytest.entity.Endpoint;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.RelevantAnnotation;
import com.greytest.entity.ServiceRepositoryRelation;
import com.greytest.entity.enums.ClassType;
import com.greytest.mapper.AnalysisMapper;
import com.greytest.repository.ControllerServiceRelationRepository;
import com.greytest.repository.EndpointRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.RelevantAnnotationRepository;
import com.greytest.repository.ServiceRepositoryRelationRepository;

/** Dựng AnalysisResultDto từ dữ liệu static analysis đã lưu. */
@Component
class AnalysisResultBuilder {

    private final JavaClassRepository classRepository;
    private final JavaMethodRepository methodRepository;
    private final EndpointRepository endpointRepository;
    private final ServiceRepositoryRelationRepository relationRepository;
    private final ControllerServiceRelationRepository controllerServiceRelationRepository;
    private final RelevantAnnotationRepository annotationRepository;
    private final AnalysisMapper mapper;

    AnalysisResultBuilder(
            JavaClassRepository classRepository,
            JavaMethodRepository methodRepository,
            EndpointRepository endpointRepository,
            ServiceRepositoryRelationRepository relationRepository,
            ControllerServiceRelationRepository controllerServiceRelationRepository,
            RelevantAnnotationRepository annotationRepository,
            AnalysisMapper mapper) {
        this.classRepository = classRepository;
        this.methodRepository = methodRepository;
        this.endpointRepository = endpointRepository;
        this.relationRepository = relationRepository;
        this.controllerServiceRelationRepository = controllerServiceRelationRepository;
        this.annotationRepository = annotationRepository;
        this.mapper = mapper;
    }

    AnalysisResultDto build(Project project, int existingTestFileCount) {
        List<JavaClass> classes = classRepository.findByProjectId(project.getId());
        List<Endpoint> allEndpoints = new ArrayList<>();
        List<JavaClassDto> classDtos = new ArrayList<>();
        int totalMethods = 0;

        for (JavaClass javaClass : classes) {
            List<JavaMethod> methods = methodRepository.findByClassId(javaClass.getId());
            totalMethods += methods.size();
            List<Endpoint> classEndpoints = new ArrayList<>();
            for (JavaMethod method : methods) {
                classEndpoints.addAll(endpointRepository.findByMethodId(method.getId()));
            }
            allEndpoints.addAll(classEndpoints);
            List<Long> methodIds = methods.stream().map(JavaMethod::getId).toList();
            List<RelevantAnnotation> methodAnnotations = methodIds.isEmpty()
                    ? List.of()
                    : annotationRepository.findByMethodIdIn(methodIds);
            classDtos.add(mapper.toDto(
                    javaClass,
                    methods,
                    classEndpoints,
                    annotationRepository.findByClassId(javaClass.getId()),
                    methodAnnotations));
        }

        List<ServiceRelationDto> relationDtos = buildRelations(project, classes);
        List<ControllerServiceRelationDto> controllerServiceRelationDtos =
                buildControllerServiceRelations(project, classes);
        return new AnalysisResultDto(
                project.getId(), project.getName(), project.getStatus().name(),
                classes.size(), totalMethods, allEndpoints.size(), relationDtos.size(),
                controllerServiceRelationDtos.size(), existingTestFileCount,
                safeInt(project.getTotalProductionFiles()),
                safeInt(project.getParsedProductionFiles()),
                safeInt(project.getFailedParseFiles()),
                project.getFailedParseFilePaths() != null ? project.getFailedParseFilePaths() : List.of(),
                classDtos, relationDtos, controllerServiceRelationDtos);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private List<ServiceRelationDto> buildRelations(Project project, List<JavaClass> classes) {
        List<ServiceRelationDto> result = new ArrayList<>();
        List<JavaClass> services = classRepository.findByProjectIdAndClassType(
                project.getId(), ClassType.SERVICE);
        for (JavaClass service : services) {
            for (ServiceRepositoryRelation relation : relationRepository.findByServiceClassId(service.getId())) {
                JavaClass repository = classes.stream()
                        .filter(javaClass -> javaClass.getId().equals(relation.getRepositoryClassId()))
                        .findFirst()
                        .orElse(null);
                result.add(mapper.toRelationDto(
                        relation,
                        service.getClassName(),
                        service.getQualifiedName(),
                        repository != null ? repository.getClassName() : "Unknown",
                        repository != null ? repository.getQualifiedName() : "Unknown"));
            }
        }
        return result;
    }

    private List<ControllerServiceRelationDto> buildControllerServiceRelations(
            Project project, List<JavaClass> classes) {
        List<ControllerServiceRelationDto> result = new ArrayList<>();
        List<JavaClass> controllers = classRepository.findByProjectIdAndClassType(
                project.getId(), ClassType.CONTROLLER);
        for (JavaClass controller : controllers) {
            for (ControllerServiceRelation relation :
                    controllerServiceRelationRepository.findByControllerClassId(controller.getId())) {
                JavaMethod controllerMethod = methodRepository.findById(relation.getControllerMethodId())
                        .orElse(null);
                JavaClass service = classes.stream()
                        .filter(javaClass -> javaClass.getId().equals(relation.getServiceClassId()))
                        .findFirst()
                        .orElse(null);
                JavaMethod serviceMethod = relation.getServiceMethodId() == null
                        ? null
                        : methodRepository.findById(relation.getServiceMethodId()).orElse(null);
                result.add(mapper.toControllerServiceRelationDto(
                        relation,
                        controller.getClassName(),
                        controller.getQualifiedName(),
                        controllerMethod != null ? controllerMethod.getMethodName() : "Unknown",
                        service != null ? service.getClassName() : "Unknown",
                        service != null ? service.getQualifiedName() : "Unknown",
                        serviceMethod != null ? serviceMethod.getMethodName() : relation.getCalledMethodName()));
            }
        }
        return result;
    }
}
