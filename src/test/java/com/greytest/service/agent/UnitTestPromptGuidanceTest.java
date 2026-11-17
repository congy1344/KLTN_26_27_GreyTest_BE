package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class UnitTestPromptGuidanceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptManager manager = new PromptManager(mapper);

    @ParameterizedTest
    @CsvSource({"enum,enum Status", "decimal,BigDecimal", "pagination,Pageable", "grpc,StreamObserver",
            "jwt,Jwts", "mail,jakarta.mail", "format,String.format", "collections,ArrayList",
            "mapping,mapper", "null-patch,null", "transaction,TransactionTemplate", "random,Math.random",
            "messaging,RabbitTemplate", "identity,UUID", "datetime,LocalDateTime",
            "spring-wiring,ApplicationContext", "mybatis,com.baomidou.mybatisplus"})
    void activatesRelevantRulesEvenWhenTheFeatureExistsOnlyInApprovedData(String topic, String feature) {
        ObjectNode context = context();
        ((ObjectNode) context.path("approvedTestCases").get(0)).put("expectedResult", feature);
        assertThat(UnitTestPromptGuidance.selectedTopics(context)).contains(topic);
        String prompt = manager.render("unit-test", Map.of("context_json", context));
        assertThat(prompt).contains(manager.load("unit-test-" + topic).strip());
    }

    @Test void checksSupportingAndReferenceEvidenceWithoutChangingAnyInput() {
        ObjectNode context = context();
        context.putArray("previousGeneratedUnitTests").addObject().put("sourceCode", "jakarta.mail.MimeMessage prior;");
        context.putArray("existingTests").addObject().put("sourceCode", "StreamObserver<String> observer;");
        context.withArray("classes").addObject().put("sourceCode", "enum State { READY }");
        ObjectNode before = context.deepCopy();
        String prompt = manager.render("unit-test", Map.of("context_json", context));
        assertThat(prompt).contains("# Relevant policy: mail", "# Relevant policy: grpc", "# Relevant policy: enum");
        assertThat(context).isEqualTo(before);
        assertThat(prompt).contains("deliberately invalid approved inputs", "NEVER stub the actual method under test/Act");
    }

    @Test void retainsEveryPolicyWhenContextIsUnknownOrLacksSourceEvidence() {
        for (Object input : List.of(Map.of(), Map.of("classes", List.of(Map.of("sourceCode", ""))), "{}")) {
            String prompt = manager.render("unit-test", Map.of("context_json", input));
            assertThat(prompt).contains("RANDOM-01", "TX-01", "For gRPC/protobuf context");
        }
    }

    @Test void keepsSharedPrefixesStableWhenOnlyBatchSpecificTopicsDiffer() {
        ObjectNode first = context();
        ObjectNode second = context();
        ((ObjectNode) second.path("approvedTestCases").get(0)).put("expectedResult", "Use StreamObserver");
        var one = manager.renderPrompt("unit-test", Map.of("context_json", first));
        var two = manager.renderPrompt("unit-test", Map.of("context_json", second));
        assertThat(one.cacheablePrefixes()).hasSize(2).isEqualTo(two.cacheablePrefixes());
        assertThat(one.text()).doesNotContain("# Relevant policy: grpc");
        assertThat(two.text()).contains("# Relevant policy: grpc");
        assertThat(one.text().length()).isLessThan(manager.load("unit-test-full").length() / 2);
    }

    @Test void includesConditionalPoliciesInCacheIdentity() {
        var changed = new PromptManager(mapper) {
            @Override public String load(String name) {
                return super.load(name) + (name.equals("unit-test-grpc") ? "\nchanged-policy" : "");
            }
        };
        assertThat(changed.promptPolicyIdentity("unit-test")).isNotEqualTo(manager.promptPolicyIdentity("unit-test"));
        assertThat(changed.promptPolicyIdentity("test-plan")).isEmpty();
    }

    @Test void inventoryOmitsDeclarationsAlreadyInSharedSourceAndKeepsDerivedDependencies() {
        ObjectNode context = context();
        var target = (ObjectNode) context.path("classes").get(0);
        target.put("qualifiedName", "sample.Service")
                .put("sourceCode", "package sample; class Service { @org.springframework.beans.factory.annotation.Value(\"${expiry}\") "
                        + "private Long expiry; @org.springframework.beans.factory.annotation.Autowired private Notifier notifier; "
                        + "public String username(){return \"user\";} private void helper(){} }");
        target.putArray("methods").addObject().put("id", 7).put("methodName", "username");
        context.withArray("classes").addObject().put("qualifiedName", "sample.Notifier")
                .put("sourceCode", "package sample; class Notifier { public void send(String text){} }").putArray("methods");
        ObjectNode before = context.deepCopy();
        String prompt = manager.render("unit-test", Map.of("context_json", context));
        String inventory = prompt.substring(prompt.indexOf("# Source-confirmed fixture/API inventory"));
        inventory = inventory.substring(0, inventory.indexOf("\n\nContext:"));
        // Khai báo đã có nguyên văn trong Shared source evidence nên inventory chỉ giữ dependency do backend phân giải.
        assertThat(inventory).contains("[{\"qualifiedName\":\"sample.Service\",\"requiredTestDependencies\":[\"sample.Notifier\"]}]")
                .doesNotContain("declaredFields", "declaredMethods", "declaredConstructors", "\"qualifiedName\":\"sample.Notifier\"",
                        "public void send", "getUsername()", "getExpiry()");
        String shared = prompt.substring(prompt.indexOf("# Shared source evidence"), prompt.indexOf("# Source-confirmed fixture/API inventory"));
        assertThat(shared).contains("private Long expiry;", "public String username()", "private void helper()", "public void send(String text)");
        assertThat(context).isEqualTo(before);
    }

    @Test void omitsInventoryWhenEveryClassIsSupportingSource() {
        ObjectNode context = context();
        ((ObjectNode) context.path("classes").get(0)).put("qualifiedName", "sample.Service");
        String prompt = manager.render("unit-test", Map.of("context_json", context));
        assertThat(prompt).contains("int run()").doesNotContain("# Source-confirmed fixture/API inventory");
    }

    @Test void listsEveryDependencyTheValidatorRequiresOnlyForTargetClasses() {
        ObjectNode context = context();
        var classes = context.withArray("classes");
        var target = (ObjectNode) classes.get(0);
        target.put("qualifiedName", "sample.Service").put("sourceCode", """
                package sample;
                import lombok.RequiredArgsConstructor;
                import sample.data.OrderRepository;
                import java.util.List;
                @RequiredArgsConstructor
                class Service {
                    private static final String PREFIX = "x";
                    private final OrderRepository orders;
                    private final AuditClient audit;
                    private final Runnable callback;
                    private final List<String> names = new java.util.ArrayList<>();
                    @org.springframework.beans.factory.annotation.Value("${limit}") private final Integer limit = 1;
                    @org.springframework.beans.factory.annotation.Autowired private Notifier notifier;
                    int run() { return 1; }
                }
                """);
        target.putArray("methods").addObject().put("id", 7).put("methodName", "run");
        classes.addObject().put("qualifiedName", "sample.AuditClient")
                .put("sourceCode", "package sample; @lombok.RequiredArgsConstructor class AuditClient { private final Helper helper; }")
                .putArray("methods");

        String prompt = manager.render("unit-test", Map.of("context_json", context));

        // Có import dùng import, có trong context dùng tên đầy đủ, còn lại không đoán package.
        assertThat(prompt).contains("\"requiredTestDependencies\":[\"sample.data.OrderRepository\",\"sample.AuditClient\",\"Runnable\",\"Notifier\"]");
        assertThat(prompt).contains("declare one class-level @Mock").doesNotContain("Helper\"]");
        assertThat(manager.load("unit-test")).contains("requiredTestDependencies", "not a signature", "A final class cannot be mocked");
    }

    private String targetWith(String source, String... otherClasses) {
        ObjectNode context = context();
        var classes = context.withArray("classes");
        var target = (ObjectNode) classes.get(0);
        target.put("qualifiedName", "sample.Service").put("sourceCode", source);
        target.putArray("methods").addObject().put("id", 7).put("methodName", "run");
        for (int index = 0; index < otherClasses.length; index += 2) {
            classes.addObject().put("qualifiedName", otherClasses[index]).put("sourceCode", otherClasses[index + 1]).putArray("methods");
        }
        return manager.render("unit-test", Map.of("context_json", context));
    }

    @Test void neverGuessesAPackageFromASameNamedContextClass() {
        String prompt = targetWith("package sample; import org.lib.*; class Service { @org.springframework.beans.factory.annotation.Autowired Client client; int run() { return 1; } }",
                "com.app.other.Client", "package com.app.other; class Client { }");
        assertThat(prompt).contains("\"requiredTestDependencies\":[\"Client\"]");
    }

    @Test void keepsEnclosingTypeAndFullyQualifiedDependencyNames() {
        String prompt = targetWith("package sample; import lib.Clients; class Service { "
                + "@org.springframework.beans.factory.annotation.Autowired Clients.Mail mail; "
                + "@org.springframework.beans.factory.annotation.Autowired java.time.Clock clock; int run() { return 1; } }");
        assertThat(prompt).contains("\"requiredTestDependencies\":[\"lib.Clients.Mail\",\"java.time.Clock\"]");
    }

    @Test void listsDependenciesInheritedFromParentsInContext() {
        String prompt = targetWith("package sample; class Service extends Base { int run() { return 1; } }",
                "sample.Base", "package sample; import lib.Audit; class Base extends Root { @org.springframework.beans.factory.annotation.Autowired Audit audit; }",
                "sample.Root", "package sample; class Root extends Base { @org.springframework.beans.factory.annotation.Autowired Store store; }");
        assertThat(prompt).contains("\"inheritedTestDependencies\":[\"lib.Audit\",\"Store\"]");
    }

    private ObjectNode context() {
        ObjectNode context = mapper.createObjectNode();
        context.putArray("classes").addObject().put("sourceCode", "package sample; class Service { int run(){return 1;} }");
        context.putArray("approvedTestCases").addObject().put("id", 1).put("expectedResult", "return 1");
        return context;
    }
}
