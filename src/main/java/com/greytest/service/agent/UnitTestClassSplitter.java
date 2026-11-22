package com.greytest.service.agent;

import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * Chuyển output Unit Test dạng gộp ({@code test_classes}: một class chung import/mock/setup cho cả batch, nhiều
 * {@code @Test}) về dạng {@code unit_tests} mỗi case một class độc lập mà validator, lưu trữ và export đang dùng.
 * AI chỉ viết phần chung một lần nên giảm mạnh output token; việc tách làm ở local, không tốn lượt gọi AI.
 */
final class UnitTestClassSplitter {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory");

    private UnitTestClassSplitter() { }

    /** Payload không có test_classes (dạng cũ, generation_error...) được trả nguyên văn. */
    static String expand(String payload, ObjectMapper mapper) throws JsonProcessingException {
        JsonNode root = mapper.readTree(payload);
        if (root == null || !root.path("test_classes").isArray() || root.has("unit_tests")) return payload;
        ObjectNode result = mapper.createObjectNode();
        var tests = result.putArray("unit_tests");
        for (JsonNode group : root.path("test_classes")) {
            String source = group.path("source_code").asText("");
            for (JsonNode item : group.path("cases")) {
                String method = item.path("test_method_name").asText("");
                tests.addObject()
                        .put("case_id", item.path("case_id").asLong())
                        .put("test_class_name", group.path("test_class_name").asText(""))
                        .put("test_method_name", method)
                        .put("package_name", group.path("package_name").asText(""))
                        .put("generation_type", group.path("generation_type").asText("NEW_TEST"))
                        .put("source_code", keepOnly(source, group.path("test_class_name").asText(""), method));
            }
        }
        return mapper.writeValueAsString(result);
    }

    /**
     * Giữ đúng một method test của case, bỏ các method test khác; import, field, setup, helper giữ nguyên định dạng.
     * Không tách được (parse lỗi, không thấy method) thì trả nguyên class để validator báo lỗi cho lượt sửa.
     */
    static String keepOnly(String source, String className, String method) {
        var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return source;
        var unit = parsed.getResult().get();
        var type = unit.getClassByName(className)
                .or(() -> unit.getTypes().stream().filter(ClassOrInterfaceDeclaration.class::isInstance)
                        .map(ClassOrInterfaceDeclaration.class::cast).findFirst())
                .orElse(null);
        if (type == null) return source;
        var tests = type.getMethods().stream()
                .filter(m -> m.getAnnotations().stream().anyMatch(a -> TEST_ANNOTATIONS.contains(a.getName().getIdentifier())))
                .toList();
        if (tests.stream().noneMatch(m -> m.getNameAsString().equals(method))) return source;
        LexicalPreservingPrinter.setup(unit);
        tests.stream().filter(m -> !m.getNameAsString().equals(method)).forEach(MethodDeclaration::remove);
        return LexicalPreservingPrinter.print(unit);
    }
}
