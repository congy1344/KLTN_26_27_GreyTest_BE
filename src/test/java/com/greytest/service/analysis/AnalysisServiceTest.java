package com.greytest.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.greytest.entity.Endpoint;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.ServiceRepositoryRelation;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.mapper.AnalysisMapper;
import com.greytest.repository.EndpointRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.ServiceRepositoryRelationRepository;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private JavaClassRepository classRepository;
    @Mock private JavaMethodRepository methodRepository;
    @Mock private EndpointRepository endpointRepository;
    @Mock private ServiceRepositoryRelationRepository relationRepository;

    @Test
    void mapsOverloadsAndDuplicateRepositoryNamesCorrectly(@TempDir Path sourceDir) throws IOException {
        writeSources(sourceDir);
        Project project = project(sourceDir);
        List<JavaClass> classes = new ArrayList<>();
        List<JavaMethod> methods = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();
        List<ServiceRepositoryRelation> relations = new ArrayList<>();
        AtomicLong ids = new AtomicLong(1);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(classRepository.save(any(JavaClass.class))).thenAnswer(invocation -> {
            JavaClass entity = invocation.getArgument(0);
            entity.setId(ids.getAndIncrement());
            classes.add(entity);
            return entity;
        });
        when(methodRepository.save(any(JavaMethod.class))).thenAnswer(invocation -> {
            JavaMethod entity = invocation.getArgument(0);
            entity.setId(ids.getAndIncrement());
            methods.add(entity);
            return entity;
        });
        when(endpointRepository.save(any(Endpoint.class))).thenAnswer(invocation -> {
            Endpoint entity = invocation.getArgument(0);
            entity.setId(ids.getAndIncrement());
            endpoints.add(entity);
            return entity;
        });
        when(relationRepository.save(any(ServiceRepositoryRelation.class))).thenAnswer(invocation -> {
            ServiceRepositoryRelation entity = invocation.getArgument(0);
            entity.setId(ids.getAndIncrement());
            relations.add(entity);
            return entity;
        });
        when(classRepository.findByProjectId(1L)).thenAnswer(ignored -> classes);
        when(classRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE))
                .thenAnswer(ignored -> classes.stream().filter(c -> c.getClassType() == ClassType.SERVICE).toList());
        when(methodRepository.findByClassId(anyLong())).thenAnswer(invocation -> {
            Long classId = invocation.getArgument(0);
            return methods.stream().filter(m -> m.getClassId().equals(classId)).toList();
        });
        when(endpointRepository.findByMethodId(anyLong())).thenAnswer(invocation -> {
            Long methodId = invocation.getArgument(0);
            return endpoints.stream().filter(e -> e.getMethodId().equals(methodId)).toList();
        });
        when(relationRepository.findByServiceClassId(anyLong())).thenAnswer(invocation -> {
            Long serviceId = invocation.getArgument(0);
            return relations.stream().filter(r -> r.getServiceClassId().equals(serviceId)).toList();
        });

        AnalysisService service = new AnalysisService(
                projectRepository,
                classRepository,
                methodRepository,
                endpointRepository,
                relationRepository,
                new JavaParserHelper(),
                new AnalysisResultBuilder(
                        classRepository,
                        methodRepository,
                        endpointRepository,
                        relationRepository,
                        new AnalysisMapper()));

        service.analyze(1L);

        JavaClass importedRepository = classes.stream()
                .filter(c -> c.getPackageName().equals("demo.b"))
                .findFirst()
                .orElseThrow();
        assertThat(relations).singleElement()
                .extracting(ServiceRepositoryRelation::getRepositoryClassId)
                .isEqualTo(importedRepository.getId());

        Endpoint byId = endpoints.stream().filter(e -> e.getPath().equals("/by-id")).findFirst().orElseThrow();
        JavaMethod linkedMethod = methods.stream()
                .filter(m -> m.getId().equals(byId.getMethodId()))
                .findFirst()
                .orElseThrow();
        assertThat(linkedMethod.getParameters()).singleElement()
                .satisfies(param -> assertThat(param.type()).isEqualTo("Long"));
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ANALYZED);
    }

    private Project project(Path sourceDir) {
        Project project = new Project();
        project.setId(1L);
        project.setName("demo");
        project.setStoragePath(sourceDir.toString());
        project.setStatus(ProjectStatus.UPLOADED);
        return project;
    }

    private void writeSources(Path root) throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("b"));
        Files.createDirectories(root.resolve("service"));
        Files.createDirectories(root.resolve("controller"));
        Files.writeString(root.resolve("a/UserRepository.java"), """
                package demo.a;
                interface UserRepository extends org.springframework.data.repository.Repository<Object, Long> {}
                """);
        Files.writeString(root.resolve("b/UserRepository.java"), """
                package demo.b;
                import org.springframework.stereotype.Repository;
                @Repository interface UserStore {}
                """);
        Files.writeString(root.resolve("service/UserService.java"), """
                package demo.service;
                import demo.b.UserStore;
                import org.springframework.stereotype.Service;
                @Service class UserService {
                    private final UserStore repository = null;
                    private final UserStore duplicateReference = null;
                }
                """);
        Files.writeString(root.resolve("controller/UserController.java"), """
                package demo.controller;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController class UserController {
                    @GetMapping("/by-id") String find(Long id) { return id.toString(); }
                    @GetMapping("/by-name") String find(String name) { return name; }
                }
                """);
    }
}
