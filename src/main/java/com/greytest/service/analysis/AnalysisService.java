package com.greytest.service.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.Node;
import com.greytest.dto.AnalysisResultDto;
import com.greytest.entity.Endpoint;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.MethodParam;
import com.greytest.entity.Project;
import com.greytest.entity.ServiceRepositoryRelation;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.exception.InvalidProjectSourceException;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.EndpointRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.ServiceRepositoryRelationRepository;
import com.greytest.service.analysis.JavaParserHelper.ExtractedEndpoint;
import com.greytest.service.analysis.JavaParserHelper.ExtractedMethod;
import com.greytest.service.analysis.JavaParserHelper.ParsedFile;
import com.greytest.service.analysis.JavaParserHelper.SourceScanResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Service điều phối quy trình phân tích tĩnh source code Java Spring Boot (WORKFLOW bước 2).
 * Sử dụng JavaParserHelper để trích xuất cấu trúc, lưu kết quả vào database.
 */
@Slf4j
@Service
public class AnalysisService {

    /** Chỉ cho phép analyze khi project ở status này. */
    private static final Set<ProjectStatus> ANALYZABLE_STATUSES = Set.of(
            ProjectStatus.UPLOADED,
            ProjectStatus.ANALYZED
    );

    private final ProjectRepository projectRepository;
    private final JavaClassRepository classRepository;
    private final JavaMethodRepository methodRepository;
    private final EndpointRepository endpointRepository;
    private final ServiceRepositoryRelationRepository relationRepository;
    private final JavaParserHelper parserHelper;
    private final AnalysisResultBuilder resultBuilder;

    public AnalysisService(
            ProjectRepository projectRepository,
            JavaClassRepository classRepository,
            JavaMethodRepository methodRepository,
            EndpointRepository endpointRepository,
            ServiceRepositoryRelationRepository relationRepository,
            JavaParserHelper parserHelper,
            AnalysisResultBuilder resultBuilder) {
        this.projectRepository = projectRepository;
        this.classRepository = classRepository;
        this.methodRepository = methodRepository;
        this.endpointRepository = endpointRepository;
        this.relationRepository = relationRepository;
        this.parserHelper = parserHelper;
        this.resultBuilder = resultBuilder;
    }

    /**
     * Phân tích toàn bộ source code của project.
     * Nếu đã analyze trước đó (status = ANALYZED), xóa data cũ và chạy lại.
     */
    @Transactional
    public AnalysisResultDto analyze(Long projectId) {
        Project project = findOrThrow(projectId);
        validateStatus(project);
        Path sourceDir = resolveSourceDir(project);

        log.info("Bắt đầu phân tích project: {} (id={})", project.getName(), projectId);

        // Xóa data cũ nếu re-analyze
        if (project.getStatus() == ProjectStatus.ANALYZED) {
            log.info("Re-analyze: xóa dữ liệu phân tích cũ của project {}", projectId);
            cleanupOldData(projectId);
        }

        SourceScanResult sourceScan = parserHelper.scanProject(sourceDir);
        List<ParsedFile> parsedFiles = sourceScan.productionFiles();
        log.info("Đã parse {} production file .java, loại {} existing test files",
                parsedFiles.size(), sourceScan.existingTestFileCount());

        // Dùng tên đầy đủ để không liên kết nhầm các class trùng tên ở package khác nhau.
        Map<String, List<Long>> qualifiedNameToIds = new HashMap<>();
        Map<String, List<Long>> repositoryQualifiedNameToIds = new HashMap<>();
        Map<String, List<Long>> repositorySimpleNameToIds = new HashMap<>();
        int totalMethods = 0;
        int totalEndpoints = 0;

        // 1. Extract classes, methods, endpoints
        for (ParsedFile pf : parsedFiles) {
            String packageName = parserHelper.getPackageName(pf.compilationUnit());
            List<TypeDeclaration<?>> typeDecls = parserHelper.findTypes(pf.compilationUnit());

            for (TypeDeclaration<?> typeDecl : typeDecls) {
                ClassType classType = parserHelper.determineClassType(typeDecl);
                String qualifiedName = qualifiedTypeName(packageName, typeDecl);

                // Lưu Java type (class/interface/enum/record)
                JavaClass javaClass = new JavaClass();
                javaClass.setProjectId(projectId);
                javaClass.setPackageName(packageName);
                javaClass.setClassName(typeDecl.getNameAsString());
                javaClass.setQualifiedName(qualifiedName);
                javaClass.setFilePath(pf.relativePath());
                javaClass.setClassType(classType);
                javaClass = classRepository.save(javaClass);

                qualifiedNameToIds.computeIfAbsent(qualifiedName, ignored -> new ArrayList<>())
                        .add(javaClass.getId());
                if (classType == ClassType.REPOSITORY) {
                    repositoryQualifiedNameToIds.computeIfAbsent(qualifiedName, ignored -> new ArrayList<>())
                            .add(javaClass.getId());
                    repositorySimpleNameToIds.computeIfAbsent(
                            typeDecl.getNameAsString(), ignored -> new ArrayList<>()).add(javaClass.getId());
                }

                // Extract và lưu methods
                List<ExtractedMethod> extractedMethods = parserHelper.extractMethods(typeDecl);
                Map<String, Long> methodKeyToIdMap = new HashMap<>();

                for (ExtractedMethod em : extractedMethods) {
                    JavaMethod method = new JavaMethod();
                    method.setClassId(javaClass.getId());
                    method.setMethodName(em.name());
                    method.setReturnType(em.returnType());
                    method.setParameters(em.parameters().stream()
                            .map(p -> new MethodParam(p.name(), p.type()))
                            .toList());
                    method.setThrowsList(em.throwsList());
                    method.setVisibility(em.visibility());
                    method.setSourceCode(em.sourceCode());
                    method.setLineStart(em.lineStart());
                    method.setLineEnd(em.lineEnd());
                    method = methodRepository.save(method);

                    methodKeyToIdMap.put(em.key(), method.getId());
                    totalMethods++;
                }

                // Extract và lưu endpoints (chỉ cho Controller)
                if (classType == ClassType.CONTROLLER
                        && typeDecl instanceof ClassOrInterfaceDeclaration classDecl) {
                    List<ExtractedEndpoint> extractedEndpoints = parserHelper.extractEndpoints(classDecl);
                    for (ExtractedEndpoint ee : extractedEndpoints) {
                        Long methodId = methodKeyToIdMap.get(ee.javaMethodKey());
                        if (methodId == null) continue;

                        Endpoint endpoint = new Endpoint();
                        endpoint.setMethodId(methodId);
                        endpoint.setHttpMethod(ee.httpMethod());
                        endpoint.setPath(ee.path());
                        endpoint.setConsumes(ee.consumes());
                        endpoint.setProduces(ee.produces());
                        endpointRepository.save(endpoint);
                        totalEndpoints++;
                    }
                }

            }
        }

        // 2. Extract Service→Repository relations (second pass — cần tất cả class IDs)
        int totalRelations = 0;
        Set<String> relationKeys = new java.util.HashSet<>();
        for (ParsedFile pf : parsedFiles) {
            List<ClassOrInterfaceDeclaration> classDecls = parserHelper.findClasses(pf.compilationUnit());
            for (ClassOrInterfaceDeclaration classDecl : classDecls) {
                ClassType classType = parserHelper.determineClassType(classDecl);
                if (classType != ClassType.SERVICE) continue;

                String packageName = parserHelper.getPackageName(pf.compilationUnit());
                Long serviceClassId = RepositoryTypeResolver.uniqueId(
                        qualifiedNameToIds.get(qualifiedTypeName(packageName, classDecl)));
                if (serviceClassId == null) continue;

                List<String> repoDeps = parserHelper.extractRepositoryDependencies(classDecl);
                for (String repoName : repoDeps) {
                    Long repoClassId = RepositoryTypeResolver.resolve(
                            repoName,
                            packageName,
                            pf,
                            repositoryQualifiedNameToIds,
                            repositorySimpleNameToIds);
                    if (repoClassId == null) {
                        if (repoName.endsWith("Repository")) {
                            log.warn("Không thể resolve repository {} của service {}",
                                    repoName, classDecl.getNameAsString());
                        }
                        continue;
                    }

                    String relationKey = serviceClassId + "->" + repoClassId;
                    if (relationKeys.add(relationKey)) {
                        ServiceRepositoryRelation relation = new ServiceRepositoryRelation();
                        relation.setServiceClassId(serviceClassId);
                        relation.setRepositoryClassId(repoClassId);
                        relationRepository.save(relation);
                        totalRelations++;
                    }
                }
            }
        }

        // 3. Cập nhật status project
        project.setStatus(ProjectStatus.ANALYZED);
        projectRepository.save(project);

        log.info("Phân tích hoàn tất project {}: {} classes, {} methods, {} endpoints, {} relations",
                projectId, qualifiedNameToIds.values().stream().mapToInt(List::size).sum(),
                totalMethods, totalEndpoints, totalRelations);

        return resultBuilder.build(project, sourceScan.existingTestFileCount());
    }

    /**
     * Truy vấn kết quả analysis đã lưu.
     */
    @Transactional(readOnly = true)
    public AnalysisResultDto getAnalysisResult(Long projectId) {
        Project project = findOrThrow(projectId);
        int existingTestFileCount = countExistingTestFilesIfSourceExists(project);
        return resultBuilder.build(project, existingTestFileCount);
    }

    // ─── Private helpers ───────────────────────────────────────

    private Project findOrThrow(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private void validateStatus(Project project) {
        if (!ANALYZABLE_STATUSES.contains(project.getStatus())) {
            throw new InvalidProjectStatusException(
                    "Project '" + project.getName() + "' đang ở trạng thái " + project.getStatus()
                            + ". Chỉ có thể phân tích khi status là UPLOADED hoặc ANALYZED."
            );
        }
    }

    private Path resolveSourceDir(Project project) {
        if (project.getStoragePath() == null || project.getStoragePath().isBlank()) {
            throw new InvalidProjectSourceException(
                    "Project khong con duong dan source. Hay upload ZIP hoac clone GitHub lai de phan tich.");
        }
        Path sourceDir = Path.of(project.getStoragePath());
        if (!Files.isDirectory(sourceDir)) {
            throw new InvalidProjectSourceException(
                    "Thu muc source khong con ton tai: " + sourceDir
                            + ". Hay upload ZIP hoac clone GitHub lai de phan tich lai.");
        }
        return sourceDir;
    }

    private void cleanupOldData(Long projectId) {
        // Cascade sẽ xóa methods, endpoints, relations liên quan tại DB level
        classRepository.deleteByProjectId(projectId);
        classRepository.flush();
    }

    private int countExistingTestFilesIfSourceExists(Project project) {
        if (project.getStoragePath() == null || project.getStoragePath().isBlank()) {
            return 0;
        }
        Path sourceDir = Path.of(project.getStoragePath());
        if (!Files.isDirectory(sourceDir)) {
            log.warn("Bo qua dem existing tests vi khong con thu muc source cua project {} tai {}",
                    project.getId(), sourceDir);
            return 0;
        }
        return parserHelper.countExistingTestFiles(sourceDir);
    }

    private String qualifiedTypeName(String packageName, TypeDeclaration<?> typeDecl) {
        List<String> names = new ArrayList<>();
        names.add(typeDecl.getNameAsString());
        Node parent = typeDecl.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof TypeDeclaration<?> enclosingType) {
                names.add(0, enclosingType.getNameAsString());
            }
            parent = parent.getParentNode().orElse(null);
        }
        String className = String.join(".", names);
        return packageName.isBlank() ? className : packageName + "." + className;
    }

}
