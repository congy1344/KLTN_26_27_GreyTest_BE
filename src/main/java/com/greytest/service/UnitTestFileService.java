package com.greytest.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.greytest.entity.UnitTest;

import lombok.extern.slf4j.Slf4j;

/**
 * Gộp các unit test record cùng test class thành một file Java hoàn chỉnh
 * (dedupe import/field, cộng dồn @Test method) — giống file test trong project thật.
 * DB vẫn giữ 1 record cho mỗi test case để phục vụ traceability.
 */
@Slf4j
@Service
public class UnitTestFileService {

    public record MergedFile(String filePath, String testClassName, String packageName,
            List<Long> caseIds, String sourceCode) {
    }

    public List<MergedFile> mergeByClass(List<UnitTest> tests) {
        Map<String, List<UnitTest>> byFile = new LinkedHashMap<>();
        for (UnitTest test : tests) {
            byFile.computeIfAbsent(test.getFilePath(), key -> new ArrayList<>()).add(test);
        }
        return byFile.values().stream().map(this::merge).toList();
    }

    private MergedFile merge(List<UnitTest> tests) {
        UnitTest first = tests.get(0);
        List<Long> caseIds = tests.stream().map(UnitTest::getTestCaseId).toList();
        String source = tests.size() == 1 ? first.getSourceCode() : mergeSources(tests);
        return new MergedFile(first.getFilePath(), first.getTestClassName(), first.getPackageName(), caseIds, source);
    }

    private String mergeSources(List<UnitTest> tests) {
        try {
            CompilationUnit base = parse(tests.get(0).getSourceCode());
            TypeDeclaration<?> baseType = firstType(base);
            Set<String> methodNames = baseType.getMethods().stream()
                    .map(MethodDeclaration::getNameAsString).collect(Collectors.toCollection(HashSet::new));
            Set<String> fieldNames = baseType.getFields().stream()
                    .flatMap(field -> field.getVariables().stream().map(VariableDeclarator::getNameAsString))
                    .collect(Collectors.toCollection(HashSet::new));
            for (int i = 1; i < tests.size(); i++) {
                appendMembers(base, baseType, parse(tests.get(i).getSourceCode()), methodNames, fieldNames);
            }
            return base.toString();
        } catch (Exception exception) {
            log.warn("Khong merge duoc unit test bang JavaParser, noi tho cac file: {}", exception.getMessage());
            // Fallback: nối thô để user vẫn xem/copy được, có thể cần chỉnh tay
            return tests.stream().map(UnitTest::getSourceCode)
                    .collect(Collectors.joining("\n\n// ==== GreyTest: khong tu merge duoc, gop thu cong ====\n\n"));
        }
    }

    private void appendMembers(CompilationUnit base, TypeDeclaration<?> baseType, CompilationUnit next,
            Set<String> methodNames, Set<String> fieldNames) {
        next.getImports().forEach(imp -> {
            if (!base.getImports().contains(imp)) {
                base.addImport(imp.clone());
            }
        });
        TypeDeclaration<?> nextType = firstType(next);
        for (BodyDeclaration<?> member : nextType.getMembers()) {
            if (member instanceof MethodDeclaration method && !methodNames.add(method.getNameAsString())) {
                continue; // method trùng tên (setup/helper) đã có ở file gốc
            }
            if (member instanceof FieldDeclaration field && field.getVariables().stream()
                    .anyMatch(variable -> !fieldNames.add(variable.getNameAsString()))) {
                continue; // field mock/service đã khai báo
            }
            baseType.addMember(member.clone());
        }
    }

    /** getPrimaryType() không dùng được khi parse từ string (không có tên file) nên lấy type đầu tiên. */
    private TypeDeclaration<?> firstType(CompilationUnit unit) {
        return unit.getTypes().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("File khong co class"));
    }

    private CompilationUnit parse(String source) {
        ParseResult<CompilationUnit> result = new JavaParser().parse(source == null ? "" : source);
        return result.getResult().filter(unit -> result.isSuccessful())
                .orElseThrow(() -> new IllegalStateException("Parse loi: "
                        + result.getProblems().stream().findFirst().map(Object::toString).orElse("unknown")));
    }
}
