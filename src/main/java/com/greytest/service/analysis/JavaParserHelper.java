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
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.HttpMethod;
import com.greytest.entity.enums.AnnotationCategory;
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
    private static final Set<String> COMPONENT_ANNOTATIONS = Set.of(
            "Service", "Component", "Controller", "RestController", "Repository",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.stereotype.Repository");
    private static final Set<String> ENDPOINT_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping");
    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "Valid", "Validated", "NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max",
            "Positive", "PositiveOrZero", "Negative", "NegativeOrZero", "Email", "Pattern",
            "jakarta.validation.Valid", "jakarta.validation.constraints.NotNull",
            "jakarta.validation.constraints.NotBlank", "jakarta.validation.constraints.NotEmpty",
            "jakarta.validation.constraints.Size", "jakarta.validation.constraints.Min",
            "jakarta.validation.constraints.Max", "jakarta.validation.constraints.Positive",
            "jakarta.validation.constraints.PositiveOrZero", "jakarta.validation.constraints.Negative",
            "jakarta.validation.constraints.NegativeOrZero", "jakarta.validation.constraints.Email",
            "jakarta.validation.constraints.Pattern");
    private static final Set<String> SECURITY_ANNOTATIONS = Set.of(
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed",
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed");
    private static final Set<String> TRANSACTION_ANNOTATIONS = Set.of(
            "Transactional", "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");
    private static final Set<String> PERSISTENCE_ANNOTATIONS = Set.of(
            "Entity", "Table", "Id", "Column", "GeneratedValue", "ManyToOne", "OneToMany",
            "OneToOne", "ManyToMany", "Embedded", "Embeddable",
            "jakarta.persistence.Entity", "jakarta.persistence.Table", "jakarta.persistence.Id",
            "jakarta.persistence.Column", "jakarta.persistence.GeneratedValue");
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
            "Autowired", "Qualifier", "Value", "Resource",
            "org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.beans.factory.annotation.Qualifier",
            "org.springframework.beans.factory.annotation.Value",
            "jakarta.annotation.Resource");

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
        return extractFieldDependencies(classDecl).stream()
                .map(FieldDependency::type)
                .distinct()
                .toList();
    }

    /** Tráº£ vá» field dependency káº»m tÃªn field Ä‘á»ƒ resolve Controllerâ†’Service calls. */
    public List<FieldDependency> extractFieldDependencies(ClassOrInterfaceDeclaration classDecl) {
        List<FieldDependency> dependencies = new ArrayList<>();
        for (FieldDeclaration field : classDecl.getFields()) {
            String typeName = normalizeTypeName(field.getElementType().asString());
            for (VariableDeclarator variable : field.getVariables()) {
                dependencies.add(new FieldDependency(variable.getNameAsString(), typeName));
            }
        }
        return dependencies;
    }

    /** Tráº£ cÃ¡c call trá»±c tiáº¿p dáº¡ng field.method(...) trong controller method. */
    public List<ControllerServiceCall> extractControllerServiceCalls(MethodDeclaration method) {
        List<ControllerServiceCall> calls = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String scopeName = call.getScope()
                    .flatMap(this::fieldScopeName)
                    .orElse(null);
            if (scopeName != null) {
                calls.add(new ControllerServiceCall(
                        methodKey(method),
                        scopeName,
                        call.getNameAsString(),
                        call.getArguments().size()));
            }
        }
        return calls;
    }

    /** Chỉ giữ annotation liên quan tới phân loại, endpoint, validation, security và test context. */
    public List<ExtractedAnnotation> extractRelevantAnnotations(TypeDeclaration<?> typeDecl) {
        return extractRelevantAnnotations(typeDecl.getAnnotations());
    }

    public List<ExtractedAnnotation> extractRelevantAnnotations(MethodDeclaration method) {
        List<ExtractedAnnotation> annotations = new ArrayList<>(extractRelevantAnnotations(method.getAnnotations()));
        for (Parameter parameter : method.getParameters()) {
            annotations.addAll(extractRelevantAnnotations(parameter.getAnnotations()));
        }
        return annotations.stream().distinct().toList();
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

    private List<ExtractedAnnotation> extractRelevantAnnotations(List<AnnotationExpr> annotations) {
        return annotations.stream()
                .map(this::toExtractedAnnotation)
                .flatMap(java.util.Optional::stream)
                .distinct()
                .toList();
    }

    private java.util.Optional<ExtractedAnnotation> toExtractedAnnotation(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        AnnotationCategory category = annotationCategory(name);
        if (category == null) return java.util.Optional.empty();
        return java.util.Optional.of(new ExtractedAnnotation(category, name, annotation.toString()));
    }

    private AnnotationCategory annotationCategory(String name) {
        if (COMPONENT_ANNOTATIONS.contains(name)) return AnnotationCategory.COMPONENT;
        if (ENDPOINT_ANNOTATIONS.contains(name)) return AnnotationCategory.ENDPOINT;
        if (VALIDATION_ANNOTATIONS.contains(name)) return AnnotationCategory.VALIDATION;
        if (SECURITY_ANNOTATIONS.contains(name)) return AnnotationCategory.SECURITY;
        if (TRANSACTION_ANNOTATIONS.contains(name)) return AnnotationCategory.TRANSACTION;
        if (PERSISTENCE_ANNOTATIONS.contains(name)) return AnnotationCategory.PERSISTENCE;
        if (INJECTION_ANNOTATIONS.contains(name)) return AnnotationCategory.INJECTION;
        return null;
    }

    private java.util.Optional<String> fieldScopeName(Expression expression) {
        if (expression instanceof NameExpr nameExpr) {
            return java.util.Optional.of(nameExpr.getNameAsString());
        }
        if (expression instanceof FieldAccessExpr fieldAccess
                && fieldAccess.getScope().isThisExpr()) {
            return java.util.Optional.of(fieldAccess.getNameAsString());
        }
        return java.util.Optional.empty();
    }

    private String normalizeTypeName(String typeName) {
        int genericStart = typeName.indexOf('<');
        return genericStart >= 0 ? typeName.substring(0, genericStart) : typeName;
    }

    // ─── Record types cho kết quả extract ──────────────────────

    public record ParsedFile(CompilationUnit compilationUnit, String relativePath) {
    }

    public record SourceScanResult(
            List<ParsedFile> productionFiles,
            int existingTestFileCount,
            int totalProductionFileCount,
            List<String> failedParseFiles) {
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

    public record FieldDependency(String name, String type) {
    }

    public record ControllerServiceCall(
            String controllerMethodKey,
            String fieldName,
            String calledMethodName,
            int argumentCount) {
    }

    public record ExtractedAnnotation(
            AnnotationCategory category,
            String name,
            String attributes) {
    }

    public record ExtractedEndpoint(
            String javaMethodKey,
            HttpMethod httpMethod,
            String path,
            String consumes,
            String produces) {
    }
}
