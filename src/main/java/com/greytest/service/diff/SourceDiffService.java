package com.greytest.service.diff;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.service.generation.GenerationFingerprintService;
import com.greytest.service.generation.GenerationFingerprintService.Fingerprint;
import com.greytest.service.agent.PersistentSourceAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.enums.ClassType;
import com.greytest.service.analysis.JavaParserHelper;
import com.greytest.service.analysis.JavaParserHelper.ExtractedMethod;
import com.greytest.service.analysis.JavaParserHelper.ParsedFile;
import com.greytest.service.analysis.JavaParserHelper.SourceScanResult;

import com.greytest.service.ServiceScopeResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Service so sánh AST giữa 2 phiên bản source code (Baseline vs Candidate snapshot).
 * Phân loại từng method: ADDED, MODIFIED, DELETED, UNCHANGED mà không làm thay đổi DB.
 */
@Slf4j
@Service
public class SourceDiffService {

    private final JavaParserHelper parserHelper;
    @Autowired(required = false)
    private PersistentSourceAnalysisService persistentSourceAnalysis;

    public SourceDiffService(JavaParserHelper parserHelper) {
        this.parserHelper = parserHelper;
    }

    public record MethodSnapshotInfo(
            String packageName,
            String className,
            String qualifiedClassName,
            String methodName,
            String returnType,
            String signature,
            String methodKey,
            String sourceCode,
            String normalizedBodyHash,
            List<String> annotations,
            ClassType classType,
            List<String> invokedMethods,
            String relativePath) {
    }

    /**
     * So sánh 2 thư mục snapshot source code.
     */
    public List<MethodDiffItem> compareSnapshots(Path baseDir, Path candidateDir) {
        String candidateDigest = persistentSourceAnalysis == null ? null : PersistentSourceAnalysisService.contentDigest(candidateDir);
        SourceScanResult candidateScan = parserHelper.scanProject(candidateDir);
        if (!candidateScan.failedParseFiles().isEmpty()) {
            throw new IllegalArgumentException(
                    "Candidate source contains files that cannot be parsed: "
                            + candidateScan.failedParseFiles());
        }
        if (persistentSourceAnalysis != null) persistentSourceAnalysis.rememberScan(candidateDir, candidateScan, candidateDigest);
        SourceScanResult baseScan = baseDir != null && java.nio.file.Files.isDirectory(baseDir)
                ? parserHelper.scanProject(baseDir) : null;
        Map<String, MethodSnapshotInfo> baseMethods = baseScan == null ? Map.of() : extractMethodSnapshots(baseScan);
        Map<String, MethodSnapshotInfo> candidateMethods = extractMethodSnapshots(candidateScan);
        Map<String, Fingerprint> candidateEvidence = dependencyEvidence(candidateDir, candidateMethods, candidateScan);
        Map<String, Fingerprint> baseEvidence = baseScan == null ? Map.of() : dependencyEvidence(baseDir, baseMethods, baseScan);
        SnapshotModuleAliases aliases = new SnapshotModuleAliases(baseMethods, candidateMethods);
        candidateMethods = aliases.keys(candidateMethods);
        candidateEvidence = aliases.evidence(candidateEvidence);
        Map<String, List<String>> callersMap = buildCallerGraph(candidateMethods, candidateEvidence);
        Map<String, List<String>> oldCallers = buildCallerGraph(baseMethods, baseEvidence);

        List<MethodDiffItem> diffItems = new ArrayList<>();
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(baseMethods.keySet());
        allKeys.addAll(candidateMethods.keySet());

        for (String key : allKeys) {
            MethodSnapshotInfo base = baseMethods.get(key);
            MethodSnapshotInfo cand = candidateMethods.get(key);
            Set<String> callersSet = new java.util.TreeSet<>(callersMap.getOrDefault(key, List.of()));
            callersSet.addAll(oldCallers.getOrDefault(key, List.of()));
            List<String> callers = List.copyOf(callersSet);
            MethodSnapshotInfo primary = cand != null ? cand : base;
            String relPath = primary != null ? primary.relativePath() : null;
            String servicePath = relPath != null ? ServiceScopeResolver.modulePath(relPath) : null;

            if (base == null && cand != null) {
                // Method mới
                diffItems.add(new MethodDiffItem(
                        cand.className(),
                        cand.qualifiedClassName(),
                        cand.methodName(),
                        cand.signature(),
                        cand.methodKey(),
                        MethodDiffType.ADDED,
                        "Method mới được thêm vào",
                        null,
                        cand.sourceCode(),
                        callers,
                        cand.classType() == ClassType.SERVICE,
                        servicePath
                ));
            } else if (base != null && cand == null) {
                // Method bị xóa
                diffItems.add(new MethodDiffItem(
                        base.className(),
                        base.qualifiedClassName(),
                        base.methodName(),
                        base.signature(),
                        base.methodKey(),
                        MethodDiffType.DELETED,
                        "Method đã bị xóa khỏi source code",
                        base.sourceCode(),
                        null,
                        callers,
                        base.classType() == ClassType.SERVICE,
                        servicePath
                ));
            } else if (base != null && cand != null) {
                // Có ở cả 2 phiên bản -> so sánh AST hash / annotations / return type
                String changeReason = detectChangeReason(base, cand);
                Fingerprint before = baseEvidence.get(key);
                Fingerprint after = candidateEvidence.get(key);
                if (changeReason == null && before != null && after != null && !before.value().equals(after.value())) {
                    if (!after.reusable() && after.provenance().contains("unresolved dynamic dispatch")) {
                        throw new IllegalArgumentException("Cannot analyze changed dynamic dispatch for " + cand.methodKey()
                                + "; resolve the source implementation ambiguity before continuing");
                    }
                    changeReason = "Source dependency hoặc build/resource contract đã thay đổi";
                }
                if (changeReason == null && after != null && !after.reusable()) {
                    // Giữ chẩn đoán rõ ràng; không suy đoán caller khi resolver thiếu bằng chứng.
                    log.debug("Dependency evidence unresolved for {}", key);
                }
                boolean isModified = changeReason != null;

                diffItems.add(new MethodDiffItem(
                        cand.className(),
                        cand.qualifiedClassName(),
                        cand.methodName(),
                        cand.signature(),
                        cand.methodKey(),
                        isModified ? MethodDiffType.MODIFIED : MethodDiffType.UNCHANGED,
                        isModified ? changeReason : "Method không thay đổi",
                        base.sourceCode(),
                        cand.sourceCode(),
                        callers,
                        cand.classType() == ClassType.SERVICE,
                        servicePath
                ));
            }
        }

        return diffItems;
    }

    private String detectChangeReason(MethodSnapshotInfo base, MethodSnapshotInfo cand) {
        if (!base.returnType().equals(cand.returnType())) {
            return "Kiểu trả về thay đổi: " + base.returnType() + " -> " + cand.returnType();
        }
        if (!base.annotations().equals(cand.annotations())) {
            return "Annotations thay đổi: " + cand.annotations();
        }
        if (!base.normalizedBodyHash().equals(cand.normalizedBodyHash())) {
            return "Nội dung logic AST của method đã thay đổi";
        }
        return null;
    }

    private Map<String, MethodSnapshotInfo> extractMethodSnapshots(SourceScanResult scanResult) {
        Map<String, MethodSnapshotInfo> resultMap = new HashMap<>();

        for (ParsedFile pf : scanResult.productionFiles()) {
            String packageName = parserHelper.getPackageName(pf.compilationUnit());
            List<TypeDeclaration<?>> typeDecls = parserHelper.findTypes(pf.compilationUnit());

            for (TypeDeclaration<?> typeDecl : typeDecls) {
                ClassType classType = parserHelper.determineClassType(typeDecl);
                String className = typeDecl.getNameAsString();
                String qualifiedClassName = typeDecl.getFullyQualifiedName().orElse(packageName.isBlank() ? className : packageName + "." + className);

                List<ExtractedMethod> methods = parserHelper.extractMethods(typeDecl);
                for (ExtractedMethod em : methods) {
                    String paramTypes = em.parameters().stream()
                            .map(JavaParserHelper.ParamInfo::type)
                            .collect(Collectors.joining(","));
                    String signature = em.name() + "(" + paramTypes + ")";
                    String methodKey = qualifiedClassName + "#" + signature;

                    var declaration = typeDecl.getMethodsByName(em.name()).stream().filter(method ->
                            method.getParameters().stream().map(parameter -> parameter.getType().asString())
                                    .collect(Collectors.joining(",")).equals(paramTypes)).findFirst().orElse(null);
                    List<String> annotations = declaration == null ? List.of() : declaration.getAnnotations().stream()
                            .map(Object::toString).sorted().toList();
                    String bodyHash = declaration == null ? hashNormalizedCode(em.sourceCode()) : hashDeclaration(declaration);

                    MethodSnapshotInfo info = new MethodSnapshotInfo(
                            packageName,
                            className,
                            qualifiedClassName,
                            em.name(),
                            em.returnType(),
                            signature,
                            methodKey,
                            em.sourceCode(),
                            bodyHash,
                            annotations,
                            classType,
                            List.of(),
                            pf.relativePath()
                    );
                    resultMap.put(ServiceScopeResolver.modulePath(pf.relativePath()) + ":" + methodKey, info);
                }
            }
        }
        return resultMap;
    }

    private Map<String, List<String>> buildCallerGraph(Map<String, MethodSnapshotInfo> methods,
            Map<String, Fingerprint> evidence) {
        Map<String, List<String>> callers = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> normalizedKeys = new HashMap<>();
        methods.keySet().forEach(key -> normalizedKeys.put(key.replace(" ", ""), key));
        for (var entry : evidence.entrySet()) {
            MethodSnapshotInfo caller = methods.get(entry.getKey());
            if (caller == null || caller.classType() != ClassType.SERVICE) continue;
            try {
                var dependencies = mapper.readTree(entry.getValue().provenance()).path("sourceDependencies");
                var names = dependencies.fieldNames();
                while (names.hasNext()) {
                    String dependency = normalizedKeys.get(names.next().replace(" ", ""));
                    if (dependency != null && !dependency.equals(entry.getKey())
                            && ServiceScopeResolver.modulePath(methods.get(dependency).relativePath())
                                .equals(ServiceScopeResolver.modulePath(caller.relativePath()))) {
                        callers.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(caller.methodKey());
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("Invalid server-generated dependency provenance", exception);
            }
        }
        return callers;
    }

    private Map<String, Fingerprint> dependencyEvidence(Path root, Map<String, MethodSnapshotInfo> methods,
            SourceScanResult scan) {
        List<ClassContextDto> targets = new ArrayList<>();
        Map<String, CompilationUnit> parsed = new HashMap<>();
        for (ParsedFile file : scan.productionFiles()) parsed.put(file.relativePath().replace('\\', '/'), file.compilationUnit());
        for (var info : methods.values()) {
            CompilationUnit unit = parsed.get(info.relativePath().replace('\\', '/'));
            if (unit == null) continue;
            var declaration = unit.findAll(MethodDeclaration.class).stream().filter(method ->
                    method.findAncestor(TypeDeclaration.class).flatMap(type -> type.getFullyQualifiedName())
                            .filter(info.qualifiedClassName()::equals).isPresent()
                            && method.getNameAsString().equals(info.methodName())
                            && (method.getNameAsString() + "(" + method.getParameters().stream()
                                .map(parameter -> parameter.getType().asString()).collect(Collectors.joining(",")) + ")").equals(info.signature()))
                    .findFirst().orElse(null);
            if (declaration == null) continue;
            var parameters = declaration.getParameters().stream().map(parameter -> new MethodParamDto(parameter.getNameAsString(), parameter.getType().asString())).toList();
            var method = new MethodContextDto(null, info.qualifiedClassName(), info.methodName(), info.returnType(),
                    parameters, List.of(), null, null, null, null, List.of(), List.of(), List.of());
            targets.add(new ClassContextDto(null, info.packageName(), info.className(), info.qualifiedClassName(),
                    info.classType().name(), info.relativePath(), null, List.of(), List.of(method)));
        }
        return GenerationFingerprintService.sourceFingerprints(targets, root, parsed);
    }

    private String hashDeclaration(MethodDeclaration declaration) {
        MethodDeclaration copy = declaration.clone();
        copy.getAllContainedComments().forEach(comment -> comment.remove());
        copy.removeComment();
        return hashNormalizedCode(copy.toString());
    }

    private String hashNormalizedCode(String code) {
        if (code == null) return "";
        // AST đã chuẩn hóa format; không sửa literal vì whitespace/comment marker có thể là dữ liệu.
        String normalized = code.replace("\r\n", "\n").replace('\r', '\n');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(normalized.hashCode());
        }
    }
}
