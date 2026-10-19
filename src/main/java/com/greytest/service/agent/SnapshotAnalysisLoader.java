package com.greytest.service.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.MethodParamDto;
import com.greytest.service.analysis.JavaParserHelper;
import com.greytest.service.analysis.JavaParserHelper.ParsedFile;
import com.greytest.service.analysis.MethodBranchAnalyzer;

/** Đọc source snapshot vào context trong bộ nhớ, giữ ID analysis và không ghi lại artifact. */
final class SnapshotAnalysisLoader {

    private SnapshotAnalysisLoader() {}

    private record SourceType(ParsedFile file, TypeDeclaration<?> type, String qualifiedName) {}

    static AnalysisResultDto load(AnalysisResultDto stored, Path snapshot) {
        if (snapshot == null || !Files.isDirectory(snapshot)) {
            throw new LlmResponseException("Candidate source snapshot is missing; upload the updated source again.");
        }
        JavaParserHelper parser = new JavaParserHelper();
        var scan = parser.scanProject(snapshot);
        if (!scan.failedParseFiles().isEmpty()) {
            throw new LlmResponseException("Cannot parse candidate source snapshot: " + scan.failedParseFiles());
        }
        List<SourceType> sources = scan.productionFiles().stream().flatMap(file -> parser.findTypes(file.compilationUnit())
                .stream().map(type -> new SourceType(file, type, type.getFullyQualifiedName().orElseThrow()))).toList();
        Map<String, List<SourceType>> sourcesByName = sources.stream().collect(Collectors.groupingBy(SourceType::qualifiedName));
        for (var entry : sourcesByName.entrySet()) {
            if (entry.getValue().size() > 1 && stored.classes().stream()
                    .noneMatch(c -> Objects.equals(c.qualifiedName(), entry.getKey()))) {
                throw new LlmResponseException("Ambiguous candidate source for " + entry.getKey());
            }
        }
        List<JavaClassDto> classes = new ArrayList<>();
        for (SourceType source : sources) {
            List<JavaClassDto> sameName = stored.classes().stream()
                    .filter(c -> Objects.equals(c.qualifiedName(), source.qualifiedName())).toList();
            List<JavaClassDto> exactPath = sameName.stream()
                    .filter(c -> Objects.equals(normalizePath(c.filePath()), source.file().relativePath())).toList();
            JavaClassDto previous = null;
            if (exactPath.size() == 1) {
                previous = exactPath.get(0);
            } else if (!sameName.isEmpty()) {
                if (sameName.size() != 1 || sourcesByName.get(source.qualifiedName()).size() != 1) {
                    throw new LlmResponseException("Ambiguous candidate source for " + source.qualifiedName());
                }
                previous = sameName.get(0);
            }
            List<JavaMethodDto> methods = new ArrayList<>();
            for (var method : parser.extractMethods(source.type())) {
                JavaMethodDto old = previous == null ? null : previous.methods().stream()
                        .filter(m -> Objects.equals(signature(m), method.key())).findFirst().orElse(null);
                if (old == null) continue;
                methods.add(new JavaMethodDto(old.id(), method.name(), method.returnType(),
                        method.parameters().stream().map(p -> new MethodParamDto(p.name(), p.type())).toList(),
                        method.throwsList(), method.visibility().name(), method.sourceCode(), method.lineStart(), method.lineEnd(),
                        old.annotations(), old.endpoints(), MethodBranchAnalyzer.analyze(method.sourceCode(), method.lineStart())));
            }
            TypeDeclaration<?> owner = source.type();
            while (owner.getParentNode().orElse(null) instanceof TypeDeclaration<?> enclosing) owner = enclosing;
            var unit = source.file().compilationUnit().clone();
            unit.setTypes(new NodeList<>(owner.clone()));
            classes.add(new JavaClassDto(previous == null ? null : previous.id(), parser.getPackageName(unit),
                    source.type().getNameAsString(), source.qualifiedName(), parser.determineClassType(source.type()).name(),
                    source.file().relativePath(), unit.toString(), previous == null ? List.of() : previous.annotations(), methods));
        }
        return new AnalysisResultDto(stored.projectId(), stored.projectName(), stored.status(), classes.size(),
                classes.stream().mapToInt(c -> c.methods().size()).sum(), stored.totalEndpoints(), stored.totalRelations(),
                stored.totalControllerServiceRelations(), scan.existingTestFileCount(), scan.totalProductionFileCount(),
                scan.productionFiles().size(), 0, List.of(), classes, stored.relations(), stored.controllerServiceRelations());
    }

    private static String signature(JavaMethodDto method) {
        return method.methodName() + "(" + method.parameters().stream().map(MethodParamDto::type).collect(Collectors.joining(",")) + ")";
    }

    private static String normalizePath(String path) {
        return path == null ? null : path.replace('\\', '/');
    }
}
