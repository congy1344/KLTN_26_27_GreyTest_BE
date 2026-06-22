package com.greytest.service.analysis;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.ServiceRelationDto;
import com.greytest.entity.Endpoint;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.ServiceRepositoryRelation;
import com.greytest.entity.enums.ClassType;
import com.greytest.mapper.AnalysisMapper;
import com.greytest.repository.EndpointRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ServiceRepositoryRelationRepository;

/** Dựng AnalysisResultDto từ dữ liệu static analysis đã lưu. */
@Component
class AnalysisResultBuilder {

    private final JavaClassRepository classRepository;
    private final JavaMethodRepository methodRepository;
    private final EndpointRepository endpointRepository;
    private final ServiceRepositoryRelationRepository relationRepository;
    private final AnalysisMapper mapper;

    AnalysisResultBuilder(
            JavaClassRepository classRepository,
            JavaMethodRepository methodRepository,
            EndpointRepository endpointRepository,
            ServiceRepositoryRelationRepository relationRepository,
            AnalysisMapper mapper) {
        this.classRepository = classRepository;
        this.methodRepository = methodRepository;
        this.endpointRepository = endpointRepository;
        this.relationRepository = relationRepository;
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
            classDtos.add(mapper.toDto(javaClass, methods, classEndpoints));
        }

        List<ServiceRelationDto> relationDtos = buildRelations(project, classes);
        return new AnalysisResultDto(
                project.getId(), project.getName(), project.getStatus().name(),
                classes.size(), totalMethods, allEndpoints.size(), relationDtos.size(),
                existingTestFileCount, classDtos, relationDtos);
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
}
