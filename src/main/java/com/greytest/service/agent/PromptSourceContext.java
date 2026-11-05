package com.greytest.service.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;

/** Khử source lặp trên bản JSON dành riêng cho prompt, không thay đổi context dùng để validation. */
final class PromptSourceContext {
    private PromptSourceContext() { }

    /**
     * Bước Business Rule: class phụ chỉ là bằng chứng kiểu nên gửi chữ ký. Giữ nguyên class mà logic của target
     * chạy qua trực tiếp (class cha, helper gọi tĩnh) và class exception, vì rule cần điều kiện và message của chúng.
     */
    static void compactSupportingClasses(ObjectNode context) {
        StringBuilder reached = new StringBuilder();
        java.util.List<ObjectNode> supporting = new java.util.ArrayList<>();
        for (JsonNode owner : context.path("classes")) {
            if (!owner.path("sourceCode").isTextual()) continue;
            if (owner.path("methods").isEmpty() && owner instanceof ObjectNode object) supporting.add(object);
            else reached.append(owner.path("sourceCode").textValue()).append('\n');
        }
        // Helper giữ lại có thể gọi tiếp helper khác hoặc có class cha, nên duyệt tới khi không còn class mới.
        for (boolean changed = true; changed; ) {
            changed = false;
            for (var iterator = supporting.iterator(); iterator.hasNext(); ) {
                ObjectNode owner = iterator.next();
                if (reachedDirectly(simpleName(owner), reached)) {
                    reached.append(owner.path("sourceCode").textValue()).append('\n');
                    iterator.remove();
                    changed = true;
                }
            }
        }
        supporting.forEach(owner -> owner.put("sourceCode", declarationsOnly(owner.path("sourceCode").textValue())));
    }

    private static String simpleName(JsonNode owner) {
        String name = owner.path("className").asText(owner.path("qualifiedName").asText(""));
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static boolean reachedDirectly(String name, CharSequence source) {
        if (name.isBlank()) return false;
        String type = java.util.regex.Pattern.quote(name);
        return java.util.regex.Pattern.compile("\\b(?:extends|implements)\\s[^{]*\\b" + type + "\\b"
                + "|\\b" + type + "\\s*\\.\\s*\\w+\\s*\\(|\\bimport\\s+static\\s+[\\w.$]*\\b" + type + "\\.").matcher(source).find();
    }

    static String declarationsOnly(String source) {
        var parsed = new JavaParser(new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return source;
        var unit = parsed.getResult().orElseThrow();
        boolean exceptionType = unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream().anyMatch(type ->
                type.getNameAsString().matches(".*(?:Exception|Error)") || type.getExtendedTypes().stream()
                        .anyMatch(parent -> parent.getNameAsString().matches(".*(?:Exception|Error|Throwable)")));
        if (exceptionType) return source;
        unit.getAllComments().forEach(com.github.javaparser.ast.comments.Comment::remove);
        unit.findAll(MethodDeclaration.class).forEach(MethodDeclaration::removeBody);
        unit.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class)
                .forEach(constructor -> constructor.setBody(new com.github.javaparser.ast.stmt.BlockStmt()));
        return unit.toString();
    }

    static ObjectNode extract(ObjectNode context) {
        var sources = new TreeSet<String>();
        context.path("classes").forEach(owner -> addSource(sources, owner.path("sourceCode")));
        context.path("dependencyCalls").forEach(call -> addSource(sources, call.path("calleeServiceSourceCode")));
        var shared = context.objectNode();
        var bank = shared.putArray("sources");
        var references = new HashMap<String, String>();
        for (String source : sources) {
            references.put(source, "#/sources/" + bank.size());
            bank.add(source);
        }
        for (JsonNode owner : context.path("classes")) {
            String source = owner.path("sourceCode").asText("");
            if (!references.containsKey(source) || !(owner instanceof ObjectNode object)) continue;
            replace(object, "sourceCode", references);
            // Chỉ bỏ method khi parser xác nhận toàn bộ khai báo đã có trong class được giữ lại.
            var parser = new JavaParser();
            var parsed = parser.parse(source);
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) continue;
            var methods = parsed.getResult().orElseThrow().findAll(MethodDeclaration.class);
            for (JsonNode method : owner.path("methods")) {
                if (!(method instanceof ObjectNode methodObject) || !method.path("sourceCode").isTextual()) continue;
                var declaration = parser.parseBodyDeclaration(method.path("sourceCode").textValue());
                if (declaration.isSuccessful() && declaration.getResult().isPresent()
                        && declaration.getResult().orElseThrow() instanceof MethodDeclaration candidate
                        && methods.contains(candidate)) {
                    methodObject.remove("sourceCode");
                    methodObject.put("sourceCodeRef", references.get(source));
                }
            }
        }
        for (JsonNode call : context.path("dependencyCalls")) {
            if (call instanceof ObjectNode object) replace(object, "calleeServiceSourceCode", references);
        }
        return shared;
    }

    private static void addSource(TreeSet<String> sources, JsonNode value) {
        if (value.isTextual() && !value.textValue().isBlank()) sources.add(value.textValue());
    }

    private static void replace(ObjectNode object, String field, Map<String, String> references) {
        String reference = references.get(object.path(field).asText(""));
        if (reference != null) {
            object.remove(field);
            object.put(field + "Ref", reference);
        }
    }
}
