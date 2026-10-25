package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;

class BusinessRuleResourceCollectorTest {
    @Test
    void collectsRelevantLombokConfigurationWithoutBubblingPastStop(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("lombok.config"), "lombok.getter.noIsPrefix = false\n");
        Path javaDirectory = Files.createDirectories(root.resolve("src/main/java/sample"));
        Files.writeString(javaDirectory.resolve("lombok.config"),
                "config.stopBubbling = true\nlombok.getter.noIsPrefix = true\nsecret.token = hidden\n");

        var result = BusinessRuleResourceCollector.collect(root, List.of(type("class Policy {}")));

        assertThat(result.resources()).hasSize(1);
        assertThat(result.resources().get(0).kind()).isEqualTo("LOMBOK_CONFIGURATION");
        assertThat(result.resources().get(0).filePath()).isEqualTo("src/main/java/sample/lombok.config");
        assertThat(result.resources().get(0).content()).contains("lombok.getter.noIsPrefix = true", "config.stopBubbling = true")
                .doesNotContain("hidden");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void followsExternalSqlFragmentsAndTerminatesCycles(@TempDir Path root) throws Exception {
        Path resource = resources(root);
        Files.writeString(resource.resolve("policy.xml"), "<mapper namespace=\"sample.Policy\">"
                + "<select id=\"find\"><include refid=\"sample.Shared.columns\"/></select>"
                + "<sql id=\"tail\">id</sql></mapper>");
        Files.writeString(resource.resolve("shared.xml"), "<mapper namespace=\"sample.Shared\">"
                + "<sql id=\"columns\"><include refid=\"sample.Policy.tail\"/></sql></mapper>");
        Files.writeString(resource.resolve("unrelated.xml"), "<mapper namespace=\"other.Unrelated\"><sql id=\"x\">secret</sql></mapper>");
        var result = BusinessRuleResourceCollector.collect(root, List.of(type("class Policy {}")));
        assertThat(result.resources()).extracting("filePath").containsExactlyInAnyOrder(
                "src/main/resources/policy.xml", "src/main/resources/shared.xml");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void resolvesLocalAndTransitiveFragmentsAndTerminatesRealCycles(@TempDir Path root) throws Exception {
        Path resource = resources(root);
        Files.writeString(resource.resolve("policy.xml"), "<mapper namespace=\"sample.Policy\">"
                + "<select id=\"find\"><include refid=\"sample.Shared.columns\"/></select></mapper>");
        Files.writeString(resource.resolve("shared.xml"), "<mapper namespace=\"sample.Shared\">"
                + "<sql id=\"columns\"><include refid=\"local\"/></sql>"
                + "<sql id=\"local\"><include refid=\"sample.Tail.end\"/></sql></mapper>");
        Files.writeString(resource.resolve("tail.xml"), "<mapper namespace=\"sample.Tail\">"
                + "<sql id=\"end\"><include refid=\"sample.Shared.columns\"/></sql></mapper>");

        var result = BusinessRuleResourceCollector.collect(root, List.of(type("class Policy {}")));

        assertThat(result.resources()).extracting("filePath").containsExactlyInAnyOrder(
                "src/main/resources/policy.xml", "src/main/resources/shared.xml", "src/main/resources/tail.xml");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void reportsMissingDynamicAndAmbiguousFragmentsWithoutSelectingOne(@TempDir Path root) throws Exception {
        Path resource = resources(root);
        Files.writeString(resource.resolve("policy.xml"), "<mapper namespace=\"sample.Policy\">"
                + "<select id=\"find\"><include refid=\"missing\"/><include refid=\"${namespace}.columns\"/>"
                + "<include refid=\"sample.Shared.columns\"/></select></mapper>");
        String duplicate = "<mapper namespace=\"sample.Shared\"><sql id=\"columns\">ambiguous</sql></mapper>";
        Files.writeString(resource.resolve("shared-a.xml"), duplicate);
        Files.writeString(resource.resolve("shared-b.xml"), duplicate);

        var result = BusinessRuleResourceCollector.collect(root, List.of(type("class Policy {}")));

        assertThat(result.resources()).extracting("filePath").containsExactly("src/main/resources/policy.xml");
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("missing"));
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("${namespace}.columns", "dynamic"));
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("sample.Shared.columns", "ambiguous"));
    }

    @Test
    void resolvesFragmentsWithinTheirSourceModuleEvenWhenOtherModulesAreSelected(@TempDir Path root) throws Exception {
        Path first = resources(root.resolve("module-a"));
        Path second = resources(root.resolve("module-b"));
        Files.writeString(first.resolve("policy.xml"), "<mapper namespace=\"sample.Policy\">"
                + "<select id=\"find\"><include refid=\"sample.Shared.columns\"/></select></mapper>");
        Files.writeString(second.resolve("wrong-policy.xml"), "<mapper namespace=\"sample.Policy\"><sql id=\"x\">wrong module</sql></mapper>");
        Files.writeString(second.resolve("shared.xml"), "<mapper namespace=\"sample.Shared\"><sql id=\"columns\">wrong fragment</sql></mapper>");
        var policy = new ClassContextDto(1L, "sample", "Policy", "sample.Policy", "OTHER",
                "module-a/src/main/java/sample/Policy.java", "package sample; class Policy {}", List.of(), List.of());
        var unrelated = new ClassContextDto(2L, "sample", "Other", "sample.Other", "OTHER",
                "module-b/src/main/java/sample/Other.java", "package sample; class Other {}", List.of(), List.of());

        var result = BusinessRuleResourceCollector.collect(root, List.of(policy, unrelated));

        assertThat(result.resources()).extracting("filePath").containsExactly("module-a/src/main/resources/policy.xml");
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("sample.Shared.columns", "missing"));
    }

    @Test
    void rejectsEntityDeclarationsInIncludedMappers(@TempDir Path root) throws Exception {
        Path resource = resources(root);
        Files.writeString(resource.resolve("policy.xml"), "<mapper namespace=\"sample.Policy\">"
                + "<select id=\"find\"><include refid=\"sample.Shared.columns\"/></select></mapper>");
        Files.writeString(resource.resolve("shared.xml"), "<!DOCTYPE mapper [<!ENTITY external SYSTEM \"file:///unreadable-secret\">]>"
                + "<mapper namespace=\"sample.Shared\"><sql id=\"columns\">&external;</sql></mapper>");

        var result = BusinessRuleResourceCollector.collect(root, List.of(type("class Policy {}")));

        assertThat(result.resources()).extracting("filePath").containsExactly("src/main/resources/policy.xml");
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("Cannot safely read resource", "shared.xml"));
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("sample.Shared.columns", "missing"));
    }

    @Test
    void keepsDiagnosticsBoundedWhenAnOmittedMapperHasManyExternalIncludes(@TempDir Path root) throws Exception {
        String includes = java.util.stream.IntStream.range(0, 1_500)
                .mapToObj(index -> "<include refid=\"other.Mapper.sql" + index + "\"/>").collect(java.util.stream.Collectors.joining());
        Files.writeString(resources(root).resolve("mapper.xml"), "<mapper namespace=\"sample.Policy\"><select id=\"find\">"
                + includes + "</select></mapper>");

        var result = BusinessRuleResourceCollector.collect(root, List.of(type("class Policy {}")));

        assertThat(result.resources()).isEmpty();
        assertThat(String.join("\n", result.warnings()).length()).isLessThan(4_500);
        assertThat(result.warnings()).anySatisfy(message -> assertThat(message).contains("limit"));
    }

    @Test
    void keepsPropertiesDocumentsAndTheirProfilesSeparate(@TempDir Path root) throws Exception {
        Files.writeString(resources(root).resolve("application.properties"),
                "spring.config.activate.on-profile=production\nrules.limit=20\n#---\n"
                        + "spring.config.activate.on-profile=development\nrules.timeout=5\n");

        var result = BusinessRuleResourceCollector.collect(root, List.of(type(
                "@org.springframework.boot.context.properties.ConfigurationProperties(\"rules\") class Policy {}")));

        assertThat(result.resources()).hasSize(2);
        assertThat(result.resources().get(0).content()).contains("production", "rules.limit = 20").doesNotContain("development", "rules.timeout");
        assertThat(result.resources().get(1).content()).contains("development", "rules.timeout = 5").doesNotContain("production", "rules.limit");
    }

    @Test
    void preservesProfileDocumentsAndReferencedConfigurationCollections(@TempDir Path root) throws Exception {
        var resource = resources(root);
        Files.writeString(resource.resolve("application.yml"), "rules:\n  allowed-statuses: [OPEN, READY]\n  api-key: hidden-value\n"
                + "---\nspring:\n  config:\n    activate:\n      on-profile: production\nrules:\n  allowed-statuses: [CLOSED]\n");

        var result = BusinessRuleResourceCollector.collect(root, List.of(type(
                "@org.springframework.boot.context.properties.ConfigurationProperties(prefix=\"rules\") class Policy {}")));

        assertThat(result.resources()).hasSize(2);
        assertThat(result.resources().get(0).content()).contains("OPEN", "READY").doesNotContain("hidden-value");
        assertThat(result.resources().get(1).content()).contains("production", "CLOSED");
    }

    @Test
    void cyclicYamlAliasesAreReportedWithoutRecursingForever(@TempDir Path root) throws Exception {
        Files.writeString(resources(root).resolve("application.yml"), "rules: &rules\n  child: *rules\n");

        var result = BusinessRuleResourceCollector.collect(root, List.of(type(
                "@org.springframework.boot.context.properties.ConfigurationProperties(\"rules\") class Policy {}")));

        assertThat(result.resources()).isEmpty();
        assertThat(result.warnings()).anySatisfy(message -> assertThat(message).contains("application.yml"));
    }

    @Test
    void reportsWholeResourcesOmittedBySizeAndContextLimits(@TempDir Path root) throws Exception {
        var resource = resources(root);
        Files.writeString(resource.resolve("large.xml"), "<mapper namespace=\"sample.Policy\"><sql id=\"query\">"
                + "x".repeat(129_000) + "</sql></mapper>");
        Files.writeString(resource.resolve("budget.xml"), "<mapper namespace=\"sample.Policy\"><sql id=\"query\">"
                + "x".repeat(33_000) + "</sql></mapper>");

        var result = BusinessRuleResourceCollector.collect(root, List.of(type("class Policy {}")));

        assertThat(result.resources()).isEmpty();
        assertThat(result.warnings()).anySatisfy(message -> assertThat(message).contains("large.xml", "too large"));
        assertThat(result.warnings()).anySatisfy(message -> assertThat(message).contains("budget.xml", "budget"));
    }

    @Test
    void flagsExternalSqlFragmentsInsteadOfPretendingTheMapperIsComplete(@TempDir Path root) throws Exception {
        Files.writeString(resources(root).resolve("mapper.xml"), "<mapper namespace=\"sample.Policy\"><select id=\"find\">"
                + "select <include refid=\"sample.Shared.columns\"/> from articles</select></mapper>");

        var result = BusinessRuleResourceCollector.collect(root, List.of(type("class Policy {}")));

        assertThat(result.resources()).hasSize(1);
        assertThat(result.warnings()).anySatisfy(message -> assertThat(message).contains("sample.Shared.columns"));
    }

    private Path resources(Path root) throws Exception {
        return Files.createDirectories(root.resolve("src/main/resources"));
    }

    private ClassContextDto type(String declaration) {
        return new ClassContextDto(1L, "sample", "Policy", "sample.Policy", "OTHER", "src/main/java/sample/Policy.java",
                "package sample; " + declaration, List.of(), List.of());
    }
}
