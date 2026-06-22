package com.greytest.service.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.HttpMethod;
import com.greytest.entity.enums.Visibility;

/**
 * Helper wrap JavaParser để phân tích source code Java.
 * Trích xuất class, method, endpoint, và quan hệ Service→Repository.
 */
@Component
public class JavaParserHelper {

    /** Annotation names xác định loại class Spring Boot. */
    private static final Set<String> SERVICE_ANNOTATIONS = Set.of(
            "Service", "org.springframework.stereotype.Service");
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "Controller", "RestController",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController");
    private static final Set<String> REPOSITORY_ANNOTATIONS = Set.of(
            "Repository", "org.springframework.stereotype.Repository");
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of(
            "Entity", "jakarta.persistence.Entity", "javax.persistence.Entity");

    private final JavaSourceScanner sourceScanner = new JavaSourceScanner();
    private final SpringEndpointExtractor endpointExtractor = new SpringEndpointExtractor();

    // ─── Public API ────────────────────────────────────────────

    /**
     * Quét toàn bộ file .java trong thư mục (recursive), parse thành CompilationUnit.
     * Dừng analysis nếu bất kỳ file nào không parse được.
     */
    public List<ParsedFile> parseDirectory(Path dir) {
        return sourceScanner.parseDirectory(dir);
    }

    /**
     * Phát hiện production source trong project Maven/Gradle, kể cả multi-module.
     * Existing tests chỉ được đếm, không đưa vào static-analysis context.
     */
    public SourceScanResult scanProject(Path projectDir) {
        return sourceScanner.scanProject(projectDir);
    }

    /** Đếm existing test files mà không parse lại production source. */
    public int countExistingTestFiles(Path projectDir) {
        return sourceScanner.countExistingTestFiles(projectDir);
    }

    /**
     * Xác định ClassType dựa trên annotation của class.
     */
    public ClassType determineClassType(TypeDeclaration<?> typeDecl) {
        if (typeDecl instanceof EnumDeclaration) return ClassType.ENUM;
        if (typeDecl instanceof RecordDeclaration) return ClassType.RECORD;
        if (!(typeDecl instanceof ClassOrInterfaceDeclaration classDecl)) return ClassType.OTHER;

        for (AnnotationExpr ann : classDecl.getAnnotations()) {
            String name = ann.getNameAsString();
            if (SERVICE_ANNOTATIONS.contains(name)) return ClassType.SERVICE;
            if (CONTROLLER_ANNOTATIONS.contains(name)) return ClassType.CONTROLLER;
            if (REPOSITORY_ANNOTATIONS.contains(name)) return ClassType.REPOSITORY;
            if (ENTITY_ANNOTATIONS.contains(name)) return ClassType.ENTITY;
        }
        // Kiểm tra extend interface kết thúc bằng "Repository" (Spring Data)
        if (classDecl.isInterface()) {
            boolean extendsRepo = classDecl.getExtendedTypes().stream()
                    .anyMatch(t -> t.getNameAsString().contains("Repository"));
            if (extendsRepo) return ClassType.REPOSITORY;
        }
        return ClassType.OTHER;
    }

    /**
     * Trích xuất thông tin method từ class declaration.
     */
    public List<ExtractedMethod> extractMethods(TypeDeclaration<?> typeDecl) {
        List<ExtractedMethod> methods = new ArrayList<>();
        for (MethodDeclaration md : typeDecl.getMethods()) {
            List<ParamInfo> params = md.getParameters().stream()
                    .map(p -> new ParamInfo(p.getNameAsString(), p.getTypeAsString()))
                    .toList();

            List<String> throwsList = md.getThrownExceptions().stream()
                    .map(Object::toString)
                    .toList();

            Visibility visibility = mapVisibility(md);

            // Lấy source code body — nếu method có body, trả full method text
            String sourceCode = md.toString();

            int lineStart = md.getRange().map(r -> r.begin.line).orElse(0);
            int lineEnd = md.getRange().map(r -> r.end.line).orElse(0);

            methods.add(new ExtractedMethod(
                    methodKey(md),
                    md.getNameAsString(),
                    md.getTypeAsString(),
                    params,
                    throwsList,
                    visibility,
                    sourceCode,
                    lineStart,
                    lineEnd
            ));
        }
        return methods;
    }

    /**
     * Trích xuất REST endpoints từ class declaration (chỉ áp dụng cho Controller).
     * Xử lý class-level @RequestMapping prefix.
     */
    public List<ExtractedEndpoint> extractEndpoints(ClassOrInterfaceDeclaration classDecl) {
        return endpointExtractor.extract(classDecl);
    }

    /**
     * Trích xuất quan hệ Service → Repository.
     * Trả các field dependency để AnalysisService resolve tới repository type thực tế.
     */
    public List<String> extractRepositoryDependencies(ClassOrInterfaceDeclaration classDecl) {
        Set<String> repoNames = new java.util.LinkedHashSet<>();
        for (FieldDeclaration field : classDecl.getFields()) {
            String typeName = field.getElementType().asString();
            repoNames.add(typeName);
        }
        return new ArrayList<>(repoNames);
    }

    /**
     * Trích xuất tất cả class declarations từ một CompilationUnit.
     */
    public List<ClassOrInterfaceDeclaration> findClasses(CompilationUnit cu) {
        return cu.findAll(ClassOrInterfaceDeclaration.class);
    }

    /** Trích xuất class/interface/enum/record; annotation declaration nằm ngoài phạm vi Phase 3. */
    public List<TypeDeclaration<?>> findTypes(CompilationUnit cu) {
        List<TypeDeclaration<?>> types = new ArrayList<>();
        types.addAll(cu.findAll(ClassOrInterfaceDeclaration.class));
        types.addAll(cu.findAll(EnumDeclaration.class));
        types.addAll(cu.findAll(RecordDeclaration.class));
        types.removeIf(type -> type.getParentNode()
                .map(parent -> !(parent instanceof CompilationUnit)
                        && !(parent instanceof TypeDeclaration<?>))
                .orElse(true));
        types.sort((left, right) -> Integer.compare(
                left.getRange().map(range -> range.begin.line).orElse(Integer.MAX_VALUE),
                right.getRange().map(range -> range.begin.line).orElse(Integer.MAX_VALUE)));
        return types;
    }

    /**
     * Lấy package name từ CompilationUnit.
     */
    public String getPackageName(CompilationUnit cu) {
        return cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
    }

    // ─── Private helpers ───────────────────────────────────────

    private Visibility mapVisibility(MethodDeclaration md) {
        if (md.isPublic()) return Visibility.PUBLIC;
        if (md.isPrivate()) return Visibility.PRIVATE;
        if (md.isProtected()) return Visibility.PROTECTED;
        return Visibility.PACKAGE_PRIVATE;
    }

    private String methodKey(MethodDeclaration method) {
        String parameterTypes = method.getParameters().stream()
                .map(Parameter::getTypeAsString)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return method.getNameAsString() + "(" + parameterTypes + ")";
    }

    // ─── Record types cho kết quả extract ──────────────────────

    public record ParsedFile(CompilationUnit compilationUnit, String relativePath) {
    }

    public record SourceScanResult(List<ParsedFile> productionFiles, int existingTestFileCount) {
    }

    public record ExtractedMethod(
            String key,
            String name,
            String returnType,
            List<ParamInfo> parameters,
            List<String> throwsList,
            Visibility visibility,
            String sourceCode,
            int lineStart,
            int lineEnd) {
    }

    public record ParamInfo(String name, String type) {
    }

    public record ExtractedEndpoint(
            String javaMethodKey,
            HttpMethod httpMethod,
            String path,
            String consumes,
            String produces) {
    }
}
