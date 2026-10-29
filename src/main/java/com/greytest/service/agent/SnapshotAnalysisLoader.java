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
public final class SnapshotAnalysisLoader {

    private SnapshotAnalysisLoader() {}

    private record SourceType(ParsedFile file, TypeDeclaration<?> type, String qualifiedName) {}

    public record ParsedSnapshot(List<JavaClassDto> classes, int existingTestFiles,
            int totalProductionFiles, int parsedProductionFiles, List<String> failedParseFiles) { }

    public static AnalysisResultDto load(AnalysisResultDto stored, Path snapshot) {
        return load(stored, snapshot, true);
    }

    static AnalysisResultDto loadActive(AnalysisResultDto stored, Path snapshot) {
        return load(stored, snapshot, false);
    }

    private static AnalysisResultDto load(AnalysisResultDto stored, Path snapshot, boolean requireCompleteParse) {
        return remap(stored, parse(snapshot, requireCompleteParse));
    }

    /** Snapshot chỉ chứa dữ liệu source; không lưu DB ID hoặc artifact đang được sửa. */
    public static ParsedSnapshot parse(Path snapshot, boolean requireCompleteParse) {
        if (snapshot == null || !Files.isDirectory(snapshot)) {
            throw new LlmResponseException("Candidate source snapshot is missing; upload the updated source again.");
        }
        JavaParserHelper parser = new JavaParserHelper();
        com.greytest.service.GenerationJobContext.increment("analysisParseEvents");
        JavaParserHelper.SourceScanResult scan;
        try {
            scan = parser.scanProject(snapshot);
        } catch (com.greytest.exception.SourceAnalysisException exception) {
            throw new LlmResponseException("Cannot parse source snapshot: " + exception.getMessage(), exception);
        }
        return fromScan(scan,requireCompleteParse);
    }

    /** Dùng lại AST của bước diff, giữ payload source không chứa ID trong database. */
    public static ParsedSnapshot fromScan(JavaParserHelper.SourceScanResult scan,boolean requireCompleteParse) {
        JavaParserHelper parser=new JavaParserHelper();
        if (requireCompleteParse && !scan.failedParseFiles().isEmpty()) {
            throw new LlmResponseException("Cannot parse candidate source snapshot: " + scan.failedParseFiles());
        }
        List<SourceType> sources = scan.productionFiles().stream().flatMap(file -> parser.findTypes(file.compilationUnit())
                .stream().map(type -> new SourceType(file, type, type.getFullyQualifiedName().orElseThrow()))).toList();
        List<JavaClassDto> classes = new ArrayList<>();
        var classTypes = parser.determineClassTypes(scan.productionFiles());
        for (SourceType source : sources) {
            List<JavaMethodDto> methods = new ArrayList<>();
            for (var method : parser.extractMethods(source.type())) {
                methods.add(new JavaMethodDto(null, method.name(), method.returnType(),
                        method.parameters().stream().map(p -> new MethodParamDto(p.name(), p.type())).toList(),
                        method.throwsList(), method.visibility().name(), method.sourceCode(), method.lineStart(), method.lineEnd(),
                        List.of(), List.of(), MethodBranchAnalyzer.analyze(method.sourceCode(), method.lineStart())));
            }
            TypeDeclaration<?> owner = source.type();
            while (owner.getParentNode().orElse(null) instanceof TypeDeclaration<?> enclosing) owner = enclosing;
            var unit = source.file().compilationUnit().clone();
            unit.setTypes(new NodeList<>(owner.clone()));
            classes.add(new JavaClassDto(null, parser.getPackageName(unit), source.type().getNameAsString(),
                    source.qualifiedName(), classTypes.get(source.type()).name(), source.file().relativePath(),
                    unit.toString(), List.of(), List.copyOf(methods)));
        }
        return new ParsedSnapshot(List.copyOf(classes), scan.existingTestFileCount(), scan.totalProductionFileCount(),
                scan.productionFiles().size(), List.copyOf(scan.failedParseFiles()));
    }

    /** Remap identity theo analysis hiện tại, không dùng ID của một lần phân tích cũ. */
    public static AnalysisResultDto remap(AnalysisResultDto stored, ParsedSnapshot snapshot) {
        var sources = snapshot.classes();
        Map<String, List<JavaClassDto>> sourcesByName = sources.stream().collect(Collectors.groupingBy(JavaClassDto::qualifiedName));
        for (var entry : sourcesByName.entrySet()) {
            if (entry.getValue().size() > 1 && stored.classes().stream()
                    .noneMatch(c -> Objects.equals(c.qualifiedName(), entry.getKey()))) {
                throw new LlmResponseException("Ambiguous candidate source for " + entry.getKey());
            }
        }
        List<JavaClassDto> classes = new ArrayList<>();
        for (JavaClassDto source : sources) {
            List<JavaClassDto> sameName = stored.classes().stream()
                    .filter(c -> Objects.equals(c.qualifiedName(), source.qualifiedName())).toList();
            List<JavaClassDto> exactPath = sameName.stream()
                    .filter(c -> Objects.equals(normalizePath(c.filePath()), source.filePath())).toList();
            JavaClassDto previous = null;
            if (exactPath.size() == 1) {
                previous = exactPath.get(0);
            } else if (!sameName.isEmpty()) {
                // Không gán ID của file lỗi parse sang class cùng FQN ở module khác.
                if (sameName.stream().filter(c -> c.filePath() != null)
                        .anyMatch(c -> snapshot.failedParseFiles().contains(normalizePath(c.filePath())))) {
                    throw new LlmResponseException("Cannot parse source snapshot declaration " + source.qualifiedName());
                }
                if (sameName.size() != 1 || sourcesByName.get(source.qualifiedName()).size() != 1) {
                    throw new LlmResponseException("Ambiguous candidate source for " + source.qualifiedName());
                }
                previous = sameName.get(0);
            }
            List<JavaMethodDto> methods = new ArrayList<>();
            for (var method : source.methods()) {
                JavaMethodDto old = previous == null ? null : previous.methods().stream()
                        .filter(m -> Objects.equals(signature(m), signature(method))).findFirst().orElse(null);
                if (old == null) continue;
                methods.add(new JavaMethodDto(old.id(), method.methodName(), method.returnType(), method.parameters(),
                        method.throwsList(), method.visibility(), method.sourceCode(), method.lineStart(), method.lineEnd(),
                        old.annotations(), old.endpoints(), method.branches()));
            }
            classes.add(new JavaClassDto(previous == null ? null : previous.id(), source.packageName(),
                    source.className(), source.qualifiedName(), source.classType(), source.filePath(), source.sourceCode(),
                    previous == null ? List.of() : previous.annotations(), List.copyOf(methods)));
        }
        return new AnalysisResultDto(stored.projectId(), stored.projectName(), stored.status(), classes.size(),
                classes.stream().mapToInt(c -> c.methods().size()).sum(), stored.totalEndpoints(), stored.totalRelations(),
                stored.totalControllerServiceRelations(), snapshot.existingTestFiles(), snapshot.totalProductionFiles(),
                snapshot.parsedProductionFiles(), snapshot.failedParseFiles().size(), snapshot.failedParseFiles(), List.copyOf(classes),
                stored.relations(), stored.controllerServiceRelations());
    }

    private static String signature(JavaMethodDto method) {
        return method.methodName() + "(" + method.parameters().stream().map(MethodParamDto::type).collect(Collectors.joining(",")) + ")";
    }

    private static String normalizePath(String path) {
        return path == null ? null : path.replace('\\', '/');
    }
}
