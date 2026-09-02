package com.greytest.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.entity.JavaClass;
import com.greytest.entity.enums.ClassType;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;

/**
 * Nhận diện và kiểm tra module source từ đường dẫn production class đã phân tích.
 */
@Service
public class ServiceScopeResolver {

    private static final String SOURCE_ROOT = "src/main/java/";

    private final ProjectRepository projects;
    private final JavaClassRepository classes;
    private final JavaMethodRepository methods;

    public ServiceScopeResolver(
            ProjectRepository projects,
            JavaClassRepository classes,
            JavaMethodRepository methods) {
        this.projects = projects;
        this.classes = classes;
        this.methods = methods;
    }

    @Transactional(readOnly = true)
    public ServiceScope resolve(Long projectId, String requestedServicePath) {
        List<ServiceScope> scopes = listScopes(projectId);
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Project chua co module production nao. Hay phan tich project truoc.");
        }
        if (requestedServicePath == null || requestedServicePath.isBlank()) {
            if (scopes.size() == 1) return scopes.get(0);
            throw new IllegalArgumentException(
                    "Project co nhieu module; can truyen query param servicePath. Gia tri hop le: "
                            + scopes.stream().map(ServiceScope::servicePath).toList());
        }
        String normalized = normalizeServicePath(requestedServicePath);
        return scopes.stream()
                .filter(scope -> scope.servicePath().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "servicePath khong thuoc project: " + normalized + ". Gia tri hop le: "
                                + scopes.stream().map(ServiceScope::servicePath).toList()));
    }

    @Transactional(readOnly = true)
    public List<ServiceScope> listScopes(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ProjectNotFoundException(projectId);
        }
        LinkedHashMap<String, List<JavaClass>> classesByPath = new LinkedHashMap<>();
        List<JavaClass> projectClasses = classes.findByProjectId(projectId);
        projectClasses.stream()
                .sorted(Comparator.comparing(JavaClass::getFilePath, Comparator.nullsLast(String::compareTo)))
                .forEach(javaClass -> classesByPath
                        .computeIfAbsent(modulePath(javaClass.getFilePath()), ignored -> new ArrayList<>())
                        .add(javaClass));
        Set<String> servicePaths = projectClasses.stream()
                .filter(javaClass -> javaClass.getClassType() == ClassType.SERVICE)
                .map(javaClass -> modulePath(javaClass.getFilePath()))
                .collect(java.util.stream.Collectors.toSet());
        return classesByPath.entrySet().stream()
                .filter(entry -> servicePaths.contains(entry.getKey()))
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> scope(entry.getKey(), entry.getValue()))
                .toList();
    }

    static String modulePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalStateException("Production class khong co filePath de nhan dien module.");
        }
        String normalized = filePath.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        int sourceRootIndex = normalized.indexOf(SOURCE_ROOT);
        if (sourceRootIndex < 0 || sourceRootIndex > 0 && normalized.charAt(sourceRootIndex - 1) != '/') {
            throw new IllegalStateException("filePath khong nam trong src/main/java: " + filePath);
        }
        String prefix = normalized.substring(0, sourceRootIndex);
        while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        return prefix.isBlank() ? "." : normalizeServicePath(prefix);
    }

    static String normalizeServicePath(String servicePath) {
        String normalized = servicePath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) return ".";
        if (normalized.startsWith("/") || normalized.contains(":")
                || java.util.Arrays.asList(normalized.split("/")).contains("..")) {
            throw new IllegalArgumentException("servicePath khong hop le: " + servicePath);
        }
        return normalized;
    }

    private ServiceScope scope(String servicePath, List<JavaClass> moduleClasses) {
        Set<Long> classIds = moduleClasses.stream()
                .map(JavaClass::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> methodIds = classIds.isEmpty() ? Set.of()
                : methods.findByClassIdIn(List.copyOf(classIds)).stream()
                        .map(com.greytest.entity.JavaMethod::getId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new ServiceScope(servicePath, Set.copyOf(classIds), Set.copyOf(methodIds));
    }

    public record ServiceScope(String servicePath, Set<Long> classIds, Set<Long> methodIds) {
    }
}
