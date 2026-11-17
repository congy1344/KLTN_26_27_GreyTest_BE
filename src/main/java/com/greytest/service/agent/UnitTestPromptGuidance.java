package com.greytest.service.agent;

import java.util.List;
import java.util.regex.Pattern;
import java.util.function.Function;
import com.fasterxml.jackson.databind.JsonNode;

/** Chọn hướng dẫn chuyên biệt từ toàn bộ bằng chứng, không lọc hay sửa dữ liệu context. */
final class UnitTestPromptGuidance {
    record Topic(String name, Pattern trigger) {
        Topic(String name, String trigger) { this(name, Pattern.compile(trigger, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)); }
    }

    private static final List<Topic> TOPICS = List.of(
            new Topic("spring-wiring", "org\\.springframework|@(?:Value|Autowired|Resource)\\b|ApplicationContext"),
            new Topic("mybatis", "com\\.baomidou\\.mybatisplus"),
            new Topic("enum", "\\benum\\b|\\bEnum\\s*[<.(]"),
            new Topic("decimal", "\\bBigDecimal\\b|\\bsetScale\\b"),
            new Topic("pagination", "\\b(?:Page|Pageable|PageRequest|PageImpl|IPage)\\b"),
            new Topic("grpc", "\\b(?:grpc|protobuf|StreamObserver|GeneratedMessage)\\b"),
            new Topic("jwt", "\\b(?:jwt|Jwts|JwtTokenUtil|Claims)\\b|jsonwebtoken"),
            new Topic("mail", "(?:javax|jakarta)\\.mail|\\b(?:JavaMailSender|MimeMessage|MimeMultipart|MimeBodyPart|MIME)\\b"),
            new Topic("format", "String\\.format|\\bregex\\b|\\bPattern\\b|\\bmatches\\("),
            new Topic("collections", "\\b(?:List|Set|Map|Collection|Collections|ArrayList|HashSet|Collectors|Stream)\\b|\\.stream\\("),
            new Topic("mapping", "mapper|mapping|mapstruct|copyProperties|\\btoDto\\b|\\btoEntity\\b"),
            new Topic("null-patch", "\\bpatch\\b|\\bupdate\\w*\\b|\\bnull\\b"),
            new Topic("transaction", "\\bTransactionTemplate\\b|executeWithoutResult|\\btransactionTemplate\\b"),
            new Topic("random", "Math\\.random|\\bRandom\\b|\\bThreadLocalRandom\\b"),
            new Topic("messaging", "\\b(?:RabbitTemplate|KafkaTemplate|AMQP|convertAndSend)\\b"),
            new Topic("identity", "\\bUUID\\b|\\bgetId\\b|\\bsetId\\b|\\bidentity\\b"),
            new Topic("datetime", "\\b(?:LocalDate|LocalTime|LocalDateTime|cancelledAt)\\b|atStartOfDay|atDate\\("));

    private UnitTestPromptGuidance() { }

    static List<String> policyResources() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of("unit-test-full"),
                TOPICS.stream().map(topic -> "unit-test-" + topic.name())).toList();
    }

    static boolean hasEvidence(JsonNode context) {
        if (context == null || !context.isObject() || !context.path("classes").isArray()
                || context.path("classes").isEmpty() || !context.path("approvedTestCases").isArray()
                || context.path("approvedTestCases").isEmpty()) return false;
        // Context rút gọn/không có source không đủ để loại chính sách chuyên biệt một cách an toàn.
        for (JsonNode owner : context.path("classes")) {
            if (!owner.path("sourceCode").isTextual() || owner.path("sourceCode").asText().isBlank()) return false;
        }
        return true;
    }

    static List<String> selectedTopics(JsonNode context) {
        String evidence = context.toString();
        return TOPICS.stream().filter(topic -> topic.trigger().matcher(evidence).find()).map(Topic::name).toList();
    }

    static String render(JsonNode context, Function<String, String> load) {
        return selectedTopics(context).stream().map(topic -> load.apply("unit-test-" + topic))
                .collect(java.util.stream.Collectors.joining("\n")) + sourceFacts(context);
    }

    /**
     * Nhấn rõ dependency cần mock mà backend phân giải từ AST (import, cùng package, class cha) để AI không phải đoán lại.
     * Khai báo field/method/constructor đọc nguyên văn từ chính sourceCode này, mà sourceCode luôn nằm trong
     * Shared source evidence của cùng prompt, nên không lặp lại ở đây; class phụ không có dependency phân giải thì bỏ qua.
     */
    private static String sourceFacts(JsonNode context) {
        var facts = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        var parser = new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21));
        for (JsonNode item : context.path("classes")) {
            if (item.path("methods").isEmpty()) continue;
            var source = parser.parse(item.path("sourceCode").asText("")).getResult();
            if (source.isEmpty()) continue;
            for (var type : source.get().findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)) {
                if (!type.getFullyQualifiedName().filter(item.path("qualifiedName").asText()::equals).isPresent()) continue;
                var fact = facts.addObject();
                fact.put("qualifiedName", item.path("qualifiedName").asText());
                var required = fact.putArray("requiredTestDependencies");
                GeneratedUnitTestSemanticValidator.requiredTestDependencies(type).stream()
                        .map(reference -> dependencyName(reference, source.get(), context)).distinct().forEach(required::add);
                var inherited = new java.util.LinkedHashSet<String>();
                collectInherited(type, context, parser, inherited, new java.util.HashSet<>());
                if (!inherited.isEmpty()) inherited.forEach(fact.putArray("inheritedTestDependencies")::add);
            }
        }
        if (facts.isEmpty()) return "";
        return "\n\n# Source-confirmed fixture/API inventory\n"
                + "Backend-resolved test dependencies of each target class only; its fields, methods, constructors and annotations "
                + "are in its full source in Shared source evidence, not repeated here. Resolve inheritance and Lombok using the full source/build. "
                + "Wire every reached dependency on the exact Act instance. A lookup method is not an injectable field. "
                + "For classes without getter-generating annotations, do not invent accessors absent from declared methods or verified parents. "
                + "Check that scalar configuration is initialized, a real SUT is not stubbed as a mock, and all helper paths use the configured collaborator. "
                + facts;
    }

    /** Dependency của class cha có trong context; validator không bắt buộc nhưng path chạy qua cha vẫn cần fixture. */
    private static void collectInherited(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration type, JsonNode context,
            com.github.javaparser.JavaParser parser, java.util.Set<String> inherited, java.util.Set<String> visited) {
        for (var parent : type.getExtendedTypes()) {
            String name = parent.getNameAsString();
            if (!visited.add(name)) continue;
            var matches = new java.util.ArrayList<JsonNode>();
            for (JsonNode item : context.path("classes")) {
                if (item.path("qualifiedName").asText().endsWith("." + name)) matches.add(item);
            }
            if (matches.size() != 1) continue;
            var unit = parser.parse(matches.get(0).path("sourceCode").asText("")).getResult().orElse(null);
            if (unit == null) continue;
            for (var declaration : unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)) {
                if (!declaration.getNameAsString().equals(name)) continue;
                GeneratedUnitTestSemanticValidator.requiredTestDependencies(declaration)
                        .forEach(reference -> inherited.add(dependencyName(reference, unit, context)));
                collectInherited(declaration, context, parser, inherited, visited);
            }
        }
    }

    /**
     * Theo đúng thứ tự phân giải của Java: import cụ thể rồi đến cùng package; import dạng * không xác định được
     * class nào nên giữ tên ngắn, không đoán package từ một class trùng tên trong context.
     */
    private static String dependencyName(com.github.javaparser.ast.type.ClassOrInterfaceType reference,
            com.github.javaparser.ast.CompilationUnit unit, JsonNode context) {
        String declared = reference.getNameWithScope();
        String head = declared.contains(".") ? declared.substring(0, declared.indexOf('.')) : declared;
        // Tên viết thường ở đầu là package, tức source đã ghi tên đầy đủ.
        if (Character.isLowerCase(head.charAt(0))) return declared;
        String suffix = declared.substring(head.length());
        var imported = unit.getImports().stream().filter(item -> !item.isAsterisk() && !item.isStatic())
                .map(com.github.javaparser.ast.ImportDeclaration::getNameAsString)
                .filter(item -> item.endsWith("." + head)).findFirst();
        if (imported.isPresent()) return imported.get() + suffix;
        String samePackage = unit.getPackageDeclaration().map(item -> item.getNameAsString() + "." + head).orElse(head);
        for (JsonNode item : context.path("classes")) {
            if (samePackage.equals(item.path("qualifiedName").asText())) return samePackage + suffix;
        }
        return declared;
    }
}
