package com.greytest.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.greytest.entity.enums.ClassType;
import com.greytest.service.analysis.JavaParserHelper.ExtractedMethod;

/** Golden regression test cho snapshot Spring PetClinic đã khóa commit trong JSON. */
class PetClinicGoldenManifestTest {

    private final JavaParserHelper parser = new JavaParserHelper();

    @Test
    void matchesFrozenPetClinicManifest() throws IOException {
        String petClinicPath = System.getProperty("petclinic.path");
        Assumptions.assumeTrue(petClinicPath != null && !petClinicPath.isBlank(),
                "Set -Dpetclinic.path=<checkout> để chạy golden test PetClinic");

        GoldenManifest expected = readGoldenManifest();
        GoldenManifest actual = analyze(Path.of(petClinicPath), expected);

        assertThat(actual.classes()).containsExactlyElementsOf(expected.classes());
        assertThat(actual.methods()).containsExactlyElementsOf(expected.methods());
        assertThat(actual.endpoints()).containsExactlyElementsOf(expected.endpoints());
        assertThat(actual.serviceRepositoryRelations())
                .containsExactlyElementsOf(expected.serviceRepositoryRelations());
    }

    private GoldenManifest analyze(Path projectDir, GoldenManifest metadata) {
        Set<String> classes = new TreeSet<>();
        Set<String> methods = new TreeSet<>();
        Set<String> endpoints = new TreeSet<>();
        Set<String> relations = new TreeSet<>();

        for (JavaParserHelper.ParsedFile parsedFile : parser.scanProject(projectDir).productionFiles()) {
            String packageName = parser.getPackageName(parsedFile.compilationUnit());
            for (TypeDeclaration<?> type : parser.findTypes(parsedFile.compilationUnit())) {
                String qualifiedName = qualifiedName(packageName, type);
                classes.add(qualifiedName);
                List<ExtractedMethod> extractedMethods = parser.extractMethods(type);
                for (ExtractedMethod method : extractedMethods) {
                    methods.add(methodSignature(qualifiedName, method));
                }
                if (parser.determineClassType(type) == ClassType.CONTROLLER
                        && type instanceof ClassOrInterfaceDeclaration controller) {
                    for (JavaParserHelper.ExtractedEndpoint endpoint : parser.extractEndpoints(controller)) {
                        String signature = extractedMethods.stream()
                                .filter(method -> method.key().equals(endpoint.javaMethodKey()))
                                .findFirst()
                                .map(method -> methodSignature(qualifiedName, method))
                                .orElseThrow();
                        endpoints.add(endpoint.httpMethod() + " " + endpoint.path() + " -> " + signature);
                    }
                }
            }
        }

        return new GoldenManifest(
                metadata.sourceRepository(),
                metadata.sourceCommit(),
                metadata.manifestVersion(),
                List.copyOf(classes),
                List.copyOf(methods),
                List.copyOf(endpoints),
                List.copyOf(relations));
    }

    private GoldenManifest readGoldenManifest() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/golden/petclinic-analysis.json")) {
            if (input == null) throw new IllegalStateException("Thiếu golden/petclinic-analysis.json");
            return new ObjectMapper().readValue(input, GoldenManifest.class);
        }
    }

    private String qualifiedName(String packageName, TypeDeclaration<?> type) {
        List<String> names = new ArrayList<>();
        names.add(type.getNameAsString());
        Node parent = type.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof TypeDeclaration<?> enclosingType) {
                names.add(0, enclosingType.getNameAsString());
            }
            parent = parent.getParentNode().orElse(null);
        }
        String typeName = String.join(".", names);
        return packageName.isBlank() ? typeName : packageName + "." + typeName;
    }

    private String methodSignature(String qualifiedName, ExtractedMethod method) {
        String parameters = method.parameters().stream()
                .map(JavaParserHelper.ParamInfo::type)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return qualifiedName + "#" + method.name() + "(" + parameters + "):" + method.returnType();
    }

    private record GoldenManifest(
            String sourceRepository,
            String sourceCommit,
            String manifestVersion,
            List<String> classes,
            List<String> methods,
            List<String> endpoints,
            List<String> serviceRepositoryRelations
    ) {
    }
}
