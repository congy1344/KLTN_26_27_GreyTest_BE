package com.greytest.service.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

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

    /**
     * Cắt context prompt theo method đích (mọi stage có source); DTO dùng cho validation không bị đổi.
     * Class đích giữ field, constructor, method đích và helper cùng class được gọi tới (bắc cầu); thân các method khác
     * bị lược nhưng giữ nguyên số dòng để lineStart của branch vẫn khớp. Class phụ được code đã giữ nhắc tên thì giữ đủ
     * (bắc cầu qua source vừa giữ), chỉ xuất hiện trong khung class đích thì còn khai báo, không liên quan thì bỏ.
     * Source class đích không parse được hoặc không tìm thấy method đích thì giữ nguyên toàn bộ context.
     * keepDataTypes (Test Case/Unit Test): entity/enum/record còn được giữ thì giữ đủ thân để dựng fixture.
     */
    static void sliceToTargets(ObjectNode context, boolean keepDataTypes) {
        if (!(context.path("classes") instanceof com.fasterxml.jackson.databind.node.ArrayNode classes)) return;
        StringBuilder reached = new StringBuilder();
        StringBuilder skeleton = new StringBuilder();
        Map<ObjectNode, String> sliced = new LinkedHashMap<>();
        for (JsonNode owner : classes) {
            if (owner.path("methods").isEmpty() || !owner.path("sourceCode").isTextual()) continue;
            String source = owner.path("sourceCode").textValue();
            var parsed = javaParser().parse(source);
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return;
            Set<String> targets = new HashSet<>();
            owner.path("methods").forEach(method -> targets.add(method.path("methodName").asText()));
            // Chỉ xét method là thành viên class (không nằm trong thân method khác) để vùng lược không chồng nhau.
            List<MethodDeclaration> members = parsed.getResult().orElseThrow().findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.findAncestor(MethodDeclaration.class).isEmpty() && m.findAncestor(ObjectCreationExpr.class).isEmpty())
                    .toList();
            Set<MethodDeclaration> keep = new HashSet<>();
            String self = simpleName(owner);
            var todo = new ArrayDeque<>(members.stream().filter(m -> targets.contains(m.getNameAsString())).toList());
            for (boolean grown = true; grown; ) {
                while (!todo.isEmpty()) {
                    MethodDeclaration method = todo.pop();
                    if (!keep.add(method)) continue;
                    // ponytail: helper khớp theo tên (giữ mọi overload cùng tên) thay vì resolve chữ ký; dư chứ không thiếu.
                    selfCalls(method, self).forEach(name -> members.stream().filter(m -> m.getNameAsString().equals(name)).forEach(todo::push));
                }
                // Method đích/helper không public chỉ test được qua caller public: giữ cả các method gọi tới nó (bắc cầu).
                Set<String> hidden = new HashSet<>();
                keep.stream().filter(m -> !m.isPublic()).forEach(m -> hidden.add(m.getNameAsString()));
                members.stream().filter(m -> !keep.contains(m) && selfCalls(m, self).stream().anyMatch(hidden::contains)).forEach(todo::push);
                grown = !todo.isEmpty();
            }
            if (keep.isEmpty()) return;
            keep.forEach(method -> reached.append(method).append('\n'));
            // Class cha/interface và static import luôn chạy cùng method đích nên giữ đủ thân như phần đã đi qua.
            var unit = parsed.getResult().orElseThrow();
            unit.getImports().stream().filter(com.github.javaparser.ast.ImportDeclaration::isStatic)
                    .forEach(declaration -> reached.append(declaration).append('\n'));
            // Collaborator do class đích tự tạo (new X() trong constructor/khởi tạo field) chạy thật khi dựng SUT;
            // dependency được inject qua tham số chỉ cần khai báo để mock.
            unit.findAll(ObjectCreationExpr.class).stream()
                    .filter(creation -> creation.findAncestor(MethodDeclaration.class).isEmpty())
                    .forEach(creation -> reached.append(creation).append('\n'));
            unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(type -> {
                type.getExtendedTypes().forEach(parent -> reached.append(parent).append('\n'));
                type.getImplementedTypes().forEach(parent -> reached.append(parent).append('\n'));
            });
            String result = omitBodies(source, members.stream().filter(m -> !keep.contains(m)).toList());
            sliced.put((ObjectNode) owner, result);
            skeleton.append(result).append('\n');
        }
        if (sliced.isEmpty()) return;
        sliced.forEach((owner, source) -> owner.put("sourceCode", source));
        List<ObjectNode> supporting = new ArrayList<>();
        for (JsonNode owner : classes) {
            if (owner.path("methods").isEmpty() && owner.path("sourceCode").isTextual() && owner instanceof ObjectNode object) supporting.add(object);
        }
        Set<ObjectNode> full = new HashSet<>();
        Set<ObjectNode> declared = new HashSet<>();
        for (boolean changed = true; changed; ) {
            changed = false;
            for (ObjectNode owner : supporting) {
                if (full.contains(owner)) continue;
                String name = simpleName(owner);
                String source = owner.path("sourceCode").textValue();
                // TC/UT cần thân entity/enum/record để dựng fixture, kể cả khi chỉ tới qua kiểu trả về của collaborator.
                boolean dataType = keepDataTypes && java.util.Set.of("ENTITY", "ENUM", "RECORD").contains(owner.path("classType").asText());
                if (mentions(name, reached) || dataType && (declared.contains(owner) || mentions(name, skeleton))) {
                    full.add(owner);
                    declared.remove(owner);
                    reached.append(source).append('\n');
                    changed = true;
                } else if (!declared.contains(owner) && mentions(name, skeleton)) {
                    declared.add(owner);
                    skeleton.append(declarationsOnly(source)).append('\n');
                    changed = true;
                }
            }
        }
        for (var iterator = classes.elements(); iterator.hasNext(); ) {
            JsonNode owner = iterator.next();
            if (full.contains(owner)) {
                ((ObjectNode) owner).put("sourceCode", collapseAccessors(owner.path("sourceCode").textValue()));
                continue;
            }
            if (!supporting.contains(owner)) continue;
            if (declared.contains(owner)) ((ObjectNode) owner).put("sourceCode", declarationsOnly(owner.path("sourceCode").textValue()));
            else iterator.remove();
        }
    }

    /**
     * Bỏ metadata class mà source đã mang (id class, package, tên ngắn, annotation; đường dẫn file của class phụ;
     * classQualifiedName và mảng rỗng của method). Chỉ trên JSON prompt; gọi sau extract.
     */
    static void pruneMetadata(ObjectNode context) {
        for (JsonNode owner : context.path("classes")) {
            if (!(owner instanceof ObjectNode object)) continue;
            boolean hasSource = object.path("sourceCode").isTextual() || object.path("sourceCodeRef").isTextual();
            object.remove(List.of("id", "packageName", "className"));
            if (hasSource) object.remove("annotations");
            if (object.path("methods").isEmpty()) object.remove("filePath");
            for (JsonNode method : object.path("methods")) {
                if (!(method instanceof ObjectNode m)) continue;
                m.remove("classQualifiedName");
                for (String field : List.of("throwsList", "annotations", "endpoints")) {
                    if (m.path(field).isArray() && m.path(field).isEmpty()) m.remove(field);
                }
            }
        }
    }

    /**
     * Getter/setter tầm thường (thân đúng một lệnh {@code return f;}/{@code return this.f;} hoặc {@code this.f = p;}
     * với f là field của class) chỉ giữ chữ ký: AI vẫn biết accessor tồn tại, không tốn token cho thân lặp lại.
     * Method có logic giữ nguyên; source không parse được thì trả nguyên.
     */
    static String collapseAccessors(String source) {
        var parsed = javaParser().parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return source;
        var unit = parsed.getResult().get();
        List<MethodDeclaration> plain = new ArrayList<>();
        for (var type : unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)) {
            Set<String> fields = new HashSet<>();
            type.getFields().forEach(field -> field.getVariables().forEach(v -> fields.add(v.getNameAsString())));
            for (MethodDeclaration method : type.getMethods()) {
                var body = method.getBody().orElse(null);
                if (body == null || body.getStatements().size() != 1) continue;
                var statement = body.getStatement(0);
                boolean getter = method.getParameters().isEmpty() && statement.isReturnStmt()
                        && statement.asReturnStmt().getExpression().map(e -> fieldName(e, fields)).isPresent();
                boolean setter = method.getParameters().size() == 1 && statement.isExpressionStmt()
                        && statement.asExpressionStmt().getExpression().isAssignExpr()
                        && fieldName(statement.asExpressionStmt().getExpression().asAssignExpr().getTarget(), fields) != null
                        && statement.asExpressionStmt().getExpression().asAssignExpr().getValue().isNameExpr()
                        && statement.asExpressionStmt().getExpression().asAssignExpr().getValue().asNameExpr().getNameAsString()
                                .equals(method.getParameter(0).getNameAsString());
                if (getter || setter) plain.add(method);
            }
        }
        if (plain.isEmpty()) return source;
        com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter.setup(unit);
        plain.forEach(MethodDeclaration::removeBody);
        return com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter.print(unit);
    }

    /** Tên field nếu biểu thức là {@code f} hoặc {@code this.f} với f là field đã khai báo; ngược lại null. */
    private static String fieldName(com.github.javaparser.ast.expr.Expression expression, Set<String> fields) {
        String name = expression.isNameExpr() ? expression.asNameExpr().getNameAsString()
                : expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()
                        ? expression.asFieldAccessExpr().getNameAsString() : null;
        return name != null && fields.contains(name) ? name : null;
    }

    /** Tên method cùng class được gọi: helper(), this.helper(), Svc.helper(), this::helper, Svc::helper. */
    private static Set<String> selfCalls(MethodDeclaration method, String self) {
        Set<String> names = new HashSet<>();
        method.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope().map(scope -> isSelf(scope, self)).orElse(true))
                .forEach(call -> names.add(call.getNameAsString()));
        method.findAll(com.github.javaparser.ast.expr.MethodReferenceExpr.class).stream()
                .filter(reference -> isSelf(reference.getScope(), self))
                .forEach(reference -> names.add(reference.getIdentifier()));
        return names;
    }

    private static boolean isSelf(com.github.javaparser.ast.expr.Expression scope, String self) {
        return scope.isThisExpr() || scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals(self)
                || scope.isTypeExpr() && scope.asTypeExpr().getType().asString().equals(self);
    }

    private static boolean mentions(String name, CharSequence text) {
        return !name.isBlank() && Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find();
    }

    private static JavaParser javaParser() {
        return new JavaParser(new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21));
    }

    /** Thay thân method bằng chú thích, chèn đủ dòng trống để các dòng phía sau giữ nguyên số dòng. */
    private static String omitBodies(String source, List<MethodDeclaration> methods) {
        List<Integer> lineStarts = new ArrayList<>(List.of(0));
        for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n') lineStarts.add(i + 1);
        StringBuilder text = new StringBuilder(source);
        methods.stream().map(m -> m.getBody().orElse(null))
                .filter(body -> body != null && body.getRange().isPresent())
                .sorted(Comparator.comparing((BlockStmt body) -> body.getRange().get().begin).reversed())
                .forEach(body -> {
                    var range = body.getRange().get();
                    int start = lineStarts.get(range.begin.line - 1) + range.begin.column - 1;
                    int end = lineStarts.get(range.end.line - 1) + range.end.column;
                    text.replace(start, end, "{ /* body omitted: not reached by the target methods */"
                            + "\n".repeat(range.end.line - range.begin.line) + "}");
                });
        return text.toString();
    }
}
