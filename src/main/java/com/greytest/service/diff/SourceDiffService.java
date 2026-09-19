package com.greytest.service.diff;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
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
        SourceScanResult candidateScan = parserHelper.scanProject(candidateDir);
        if (!candidateScan.failedParseFiles().isEmpty()) {
            throw new IllegalArgumentException(
                    "Candidate source contains files that cannot be parsed: "
                            + candidateScan.failedParseFiles());
        }
        Map<String, MethodSnapshotInfo> baseMethods = extractMethodSnapshots(baseDir);
        Map<String, MethodSnapshotInfo> candidateMethods = extractMethodSnapshots(candidateDir);

        // Xây dựng Caller Graph trên candidate snapshot
        Map<String, List<String>> callersMap = buildCallerGraph(candidateMethods);

        List<MethodDiffItem> diffItems = new ArrayList<>();
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(baseMethods.keySet());
        allKeys.addAll(candidateMethods.keySet());

        for (String key : allKeys) {
            MethodSnapshotInfo base = baseMethods.get(key);
            MethodSnapshotInfo cand = candidateMethods.get(key);
            List<String> callers = callersMap.getOrDefault(key, List.of());
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

    private Map<String, MethodSnapshotInfo> extractMethodSnapshots(Path sourceDir) {
        if (sourceDir == null || !java.nio.file.Files.isDirectory(sourceDir)) {
            return Collections.emptyMap();
        }

        SourceScanResult scanResult = parserHelper.scanProject(sourceDir);
        Map<String, MethodSnapshotInfo> resultMap = new HashMap<>();

        for (ParsedFile pf : scanResult.productionFiles()) {
            String packageName = parserHelper.getPackageName(pf.compilationUnit());
            List<TypeDeclaration<?>> typeDecls = parserHelper.findTypes(pf.compilationUnit());

            for (TypeDeclaration<?> typeDecl : typeDecls) {
                ClassType classType = parserHelper.determineClassType(typeDecl);
                String className = typeDecl.getNameAsString();
                String qualifiedClassName = packageName.isBlank() ? className : packageName + "." + className;

                List<ExtractedMethod> methods = parserHelper.extractMethods(typeDecl);
                for (ExtractedMethod em : methods) {
                    String paramTypes = em.parameters().stream()
                            .map(JavaParserHelper.ParamInfo::type)
                            .collect(Collectors.joining(","));
                    String signature = em.name() + "(" + paramTypes + ")";
                    String methodKey = qualifiedClassName + "#" + signature;

                    List<String> annotations = typeDecl instanceof ClassOrInterfaceDeclaration classDecl
                            ? extractMethodAnnotationNames(classDecl, em.name())
                            : List.of();

                    List<String> invokedMethods = typeDecl instanceof ClassOrInterfaceDeclaration classDecl
                            ? extractInvokedMethodNames(classDecl, em.name())
                            : List.of();

                    String bodyHash = hashNormalizedCode(em.sourceCode());

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
                            invokedMethods,
                            pf.relativePath()
                    );
                    resultMap.put(methodKey, info);
                }
            }
        }
        return resultMap;
    }

    private List<String> extractMethodAnnotationNames(ClassOrInterfaceDeclaration classDecl, String methodName) {
        return classDecl.getMethodsByName(methodName).stream()
                .flatMap(m -> m.getAnnotations().stream())
                .map(a -> a.getNameAsString())
                .sorted()
                .toList();
    }

    private List<String> extractInvokedMethodNames(ClassOrInterfaceDeclaration classDecl, String methodName) {
        return classDecl.getMethodsByName(methodName).stream()
                .flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                .map(MethodCallExpr::getNameAsString)
                .distinct()
                .toList();
    }

    private Map<String, List<String>> buildCallerGraph(Map<String, MethodSnapshotInfo> methods) {
        Map<String, List<String>> callers = new HashMap<>();
        for (MethodSnapshotInfo caller : methods.values()) {
            for (String invokedName : caller.invokedMethods()) {
                for (MethodSnapshotInfo target : methods.values()) {
                    // Không thể suy luận kiểu của scope chỉ từ tên method; giới hạn
                    // lời gọi không có qualifier trong cùng class để tránh đánh dấu nhầm overload/class khác.
                    if (target.qualifiedClassName().equals(caller.qualifiedClassName())
                            && target.methodName().equals(invokedName)) {
                        callers.computeIfAbsent(target.methodKey(), k -> new ArrayList<>())
                                .add(caller.methodKey());
                    }
                }
            }
        }
        return callers;
    }

    private String hashNormalizedCode(String code) {
        if (code == null) return "";
        // Chuẩn hóa: xóa toàn bộ whitespace thừa và comment
        String normalized = code.replaceAll("//.*|/\\*.*?\\*/", "")
                .replaceAll("\\s+", " ")
                .trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(normalized.hashCode());
        }
    }
}
