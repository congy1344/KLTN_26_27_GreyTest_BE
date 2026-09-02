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
            String testClassName = tests.get(0).getTestClassName();
            TypeDeclaration<?> baseType = typeByName(base, testClassName);
            Set<String> methodSignatures = baseType.getMethods().stream()
                    .map(this::methodSignature).collect(Collectors.toCollection(HashSet::new));
            Set<String> fieldNames = baseType.getFields().stream()
                    .flatMap(field -> field.getVariables().stream().map(VariableDeclarator::getNameAsString))
                    .collect(Collectors.toCollection(HashSet::new));
            for (int i = 1; i < tests.size(); i++) {
                appendMembers(base, baseType, parse(tests.get(i).getSourceCode()), testClassName,
                        methodSignatures, fieldNames);
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
            String testClassName, Set<String> methodSignatures, Set<String> fieldNames) {
        next.getImports().forEach(imp -> {
            if (!base.getImports().contains(imp)) {
                base.addImport(imp.clone());
            }
        });
        TypeDeclaration<?> nextType = typeByName(next, testClassName);
        for (BodyDeclaration<?> member : nextType.getMembers()) {
            if (member instanceof MethodDeclaration method
                    && !methodSignatures.add(methodSignature(method))) {
                continue; // method cùng signature (setup/helper) đã có ở file gốc
            }
            if (member instanceof FieldDeclaration field) {
                FieldDeclaration uniqueFields = field.clone();
                uniqueFields.getVariables().removeIf(
                        variable -> !fieldNames.add(variable.getNameAsString()));
                if (!uniqueFields.getVariables().isEmpty()) {
                    baseType.addMember(uniqueFields);
                }
                continue;
            }
            baseType.addMember(member.clone());
        }
    }

    private TypeDeclaration<?> typeByName(CompilationUnit unit, String testClassName) {
        return unit.getTypes().stream()
                .filter(type -> type.getNameAsString().equals(testClassName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Khong tim thay test class " + testClassName));
    }

    private String methodSignature(MethodDeclaration method) {
        Map<String, String> typeVariables = method.getTypeParameters().stream()
                .collect(Collectors.toMap(
                        parameter -> parameter.getNameAsString(),
                        parameter -> parameter.getTypeBound().isEmpty()
                                ? "Object"
                                : erasedType(parameter.getTypeBound().get(0))));
        return method.getNameAsString() + "(" + method.getParameters().stream()
                .map(parameter -> eraseTypeVariables(erasedType(parameter.getType()), typeVariables)
                        + (parameter.isVarArgs() ? "[]" : ""))
                .collect(Collectors.joining(",")) + ")";
    }

    private String eraseTypeVariables(String type, Map<String, String> typeVariables) {
        String erased = type;
        for (var entry : typeVariables.entrySet()) {
            erased = erased.replaceAll("(?<![A-Za-z0-9_$])" + java.util.regex.Pattern.quote(entry.getKey())
                    + "(?![A-Za-z0-9_$])", java.util.regex.Matcher.quoteReplacement(entry.getValue()));
        }
        return erased;
    }

    private String erasedType(com.github.javaparser.ast.type.Type type) {
        var erased = type.clone();
        erased.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class)
                .forEach(classType -> classType.removeTypeArguments());
        return erased.asString();
    }

    private CompilationUnit parse(String source) {
        ParseResult<CompilationUnit> result = new JavaParser().parse(source == null ? "" : source);
        return result.getResult().filter(unit -> result.isSuccessful())
                .orElseThrow(() -> new IllegalStateException("Parse loi: "
                        + result.getProblems().stream().findFirst().map(Object::toString).orElse("unknown")));
    }
}
