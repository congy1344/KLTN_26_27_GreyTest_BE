package com.greytest.service.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.greytest.entity.enums.HttpMethod;
import com.greytest.service.analysis.JavaParserHelper.ExtractedEndpoint;

import lombok.extern.slf4j.Slf4j;

/** Extract Spring MVC mapping chuẩn, gồm nhiều class path, method path và HTTP method. */
@Slf4j
class SpringEndpointExtractor {

    private static final Set<String> REQUEST_MAPPING_NAMES = Set.of(
            "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping");

    List<ExtractedEndpoint> extract(ClassOrInterfaceDeclaration controller) {
        List<String> classPaths = extractRequestMappingPaths(controller);
        List<ExtractedEndpoint> endpoints = new ArrayList<>();
        Set<String> endpointKeys = new HashSet<>();

        for (MethodDeclaration method : controller.getMethods()) {
            for (AnnotationExpr annotation : method.getAnnotations()) {
                String annotationName = annotation.getNameAsString();
                if (!REQUEST_MAPPING_NAMES.contains(annotationName)) continue;

                List<HttpMethod> httpMethods = mapHttpMethods(annotationName, annotation);
                List<String> methodPaths = extractAnnotationPaths(annotation);
                String consumes = extractAnnotationValue(annotation, "consumes");
                String produces = extractAnnotationValue(annotation, "produces");

                for (String classPath : classPaths) {
                    for (String methodPath : methodPaths) {
                        String fullPath = combinePaths(classPath, methodPath);
                        for (HttpMethod httpMethod : httpMethods) {
                            String key = methodKey(method) + "|" + httpMethod + "|" + fullPath;
                            if (endpointKeys.add(key)) {
                                endpoints.add(new ExtractedEndpoint(
                                        methodKey(method), httpMethod, fullPath, consumes, produces));
                            }
                        }
                    }
                }
            }
        }
        return endpoints;
    }

    private List<HttpMethod> mapHttpMethods(String annotationName, AnnotationExpr annotation) {
        return switch (annotationName) {
            case "GetMapping" -> List.of(HttpMethod.GET);
            case "PostMapping" -> List.of(HttpMethod.POST);
            case "PutMapping" -> List.of(HttpMethod.PUT);
            case "DeleteMapping" -> List.of(HttpMethod.DELETE);
            case "PatchMapping" -> List.of(HttpMethod.PATCH);
            case "RequestMapping" -> mapRequestMappingMethods(annotation);
            default -> List.of(HttpMethod.ANY);
        };
    }

    private List<HttpMethod> mapRequestMappingMethods(AnnotationExpr annotation) {
        Expression expression = extractAnnotationAttribute(annotation, "method");
        if (expression == null) return List.of(HttpMethod.ANY);

        List<HttpMethod> methods = new ArrayList<>();
        for (String value : extractExpressionValues(expression)) {
            String methodName = value.substring(value.lastIndexOf('.') + 1);
            try {
                methods.add(HttpMethod.valueOf(methodName));
            } catch (IllegalArgumentException exception) {
                log.warn("Bỏ qua HTTP method chưa hỗ trợ: {}", value);
            }
        }
        return methods.isEmpty() ? List.of(HttpMethod.ANY) : methods.stream().distinct().toList();
    }

    private List<String> extractRequestMappingPaths(ClassOrInterfaceDeclaration controller) {
        Optional<AnnotationExpr> mapping = controller.getAnnotationByName("RequestMapping");
        return mapping.map(this::extractAnnotationPaths).orElse(List.of(""));
    }

    private List<String> extractAnnotationPaths(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return extractExpressionValues(single.getMemberValue());
        }
        Expression expression = extractAnnotationAttribute(annotation, "value");
        if (expression == null) expression = extractAnnotationAttribute(annotation, "path");
        return expression == null ? List.of("") : extractExpressionValues(expression);
    }

    private String extractAnnotationValue(AnnotationExpr annotation, String attribute) {
        Expression expression = extractAnnotationAttribute(annotation, attribute);
        return expression == null ? null : String.join(",", extractExpressionValues(expression));
    }

    private Expression extractAnnotationAttribute(AnnotationExpr annotation, String attribute) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (attribute.equals(pair.getNameAsString())) return pair.getValue();
            }
        }
        return null;
    }

    private List<String> extractExpressionValues(Expression expression) {
        if (expression instanceof ArrayInitializerExpr array) {
            return array.getValues().stream()
                    .flatMap(value -> extractExpressionValues(value).stream())
                    .toList();
        }
        if (expression.isStringLiteralExpr()) return List.of(expression.asStringLiteralExpr().asString());
        return List.of(expression.toString().trim());
    }

    private String methodKey(MethodDeclaration method) {
        String parameters = method.getParameters().stream()
                .map(Parameter::getTypeAsString)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return method.getNameAsString() + "(" + parameters + ")";
    }

    private String combinePaths(String classPath, String methodPath) {
        if (classPath.isEmpty()) return methodPath.isEmpty() ? "/" : normalizePath(methodPath);
        if (methodPath.isEmpty()) return normalizePath(classPath);
        return normalizePath(classPath + "/" + methodPath);
    }

    private String normalizePath(String path) {
        String normalized = "/" + path.replaceAll("^/+", "").replaceAll("/+$", "");
        return normalized.replaceAll("/+", "/");
    }
}
