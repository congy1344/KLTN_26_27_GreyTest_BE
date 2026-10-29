package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.entity.*;
import com.greytest.entity.enums.*;
import com.greytest.repository.*;

@SpringBootTest(properties = "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes")
@Transactional
class ActiveSourceUnitTestContextIntegrationTest {
    private static final String SERVICE_PATH = "piggymetrics/account-service/src/main/java/com/piggymetrics/account/service/AccountServiceImpl.java";
    private static final String ACCOUNT_PATH = "piggymetrics/account-service/src/main/java/com/piggymetrics/account/domain/Account.java";
    private static final String STATISTICS_PATH = "piggymetrics/statistics-service/src/main/java/com/piggymetrics/statistics/domain/Account.java";
    private static final String LEGACY_SOURCE = "public class AccountServiceImpl { public Account findByName(String name) { return null; } }";

    @Autowired private GenerationContextBuilder builder;
    @Autowired private ProjectRepository projects;
    @Autowired private JavaClassRepository classes;
    @Autowired private JavaMethodRepository methods;
    @Autowired private BusinessRuleRepository rules;
    @Autowired private TestPlanRepository plans;
    @Autowired private TestCaseRepository cases;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesDuplicateAccountUsingActiveSnapshotWithoutReplacingArtifacts(boolean targeted, @TempDir Path snapshot) throws Exception {
        Fixture fixture = fixture(snapshot);

        var context = targeted
                ? builder.buildUnitTestContext(fixture.projectId(), java.util.Set.of(fixture.caseId()))
                : builder.buildUnitTestContext(fixture.projectId());

        assertThat(context.classes()).extracting("qualifiedName").containsExactlyInAnyOrder(
                "com.piggymetrics.account.service.AccountServiceImpl", "com.piggymetrics.account.domain.Account");
        assertThat(context.classes()).filteredOn(c -> c.id().equals(fixture.classId())).singleElement().satisfies(owner -> {
            assertThat(owner.sourceCode()).contains("package com.piggymetrics.account.service;",
                    "import com.piggymetrics.account.domain.Account;");
            assertThat(owner.methods()).extracting("id").containsExactly(fixture.methodId());
        });
        assertThat(context.approvedBusinessRules()).extracting("id").containsExactly(fixture.ruleId());
        assertThat(context.approvedTestPlans()).extracting("id").containsExactly(fixture.planId());
        assertThat(context.approvedTestCases()).extracting("id").containsExactly(fixture.caseId());
        assertThat(classes.findById(fixture.classId()).orElseThrow().getSourceCode()).isEqualTo(LEGACY_SOURCE);
        assertThat(classes.findByProjectId(fixture.projectId())).hasSize(3);
        assertThat(rules.findByProjectId(fixture.projectId())).hasSize(1);
        assertThat(plans.findByProjectId(fixture.projectId())).hasSize(1);
        assertThat(cases.findByTestPlanId(fixture.planId())).hasSize(1);
    }

    @Test
    void refusesMissingActiveTargetInsteadOfFallingBackToLegacySource(@TempDir Path snapshot) throws Exception {
        Fixture fixture = fixture(snapshot);
        Files.delete(snapshot.resolve(SERVICE_PATH));

        assertThatThrownBy(() -> builder.buildUnitTestContext(fixture.projectId(), java.util.Set.of(fixture.caseId())))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("target method");
    }

    @Test
    void explicitCandidateContextUsesCandidateImportsInsteadOfActiveSnapshot(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root.resolve("active"));
        Path candidate = root.resolve("candidate");
        writeSources(candidate, "com.piggymetrics.statistics.domain.Account");

        var context = builder.buildSnapshotUnitTestContext(fixture.projectId(), java.util.Set.of(fixture.caseId()), candidate);

        assertThat(context.classes()).extracting("qualifiedName").containsExactlyInAnyOrder(
                "com.piggymetrics.account.service.AccountServiceImpl", "com.piggymetrics.statistics.domain.Account");
        assertThat(context.approvedTestCases()).extracting("id").containsExactly(fixture.caseId());
        assertThat(projects.findById(fixture.projectId()).orElseThrow().getStoragePath()).isEqualTo(root.resolve("active").toString());
    }

    @Test
    void unrelatedUnparseableModuleDoesNotBlockActiveTarget(@TempDir Path snapshot) throws Exception {
        Fixture fixture = fixture(snapshot);
        write(snapshot.resolve("other-service/src/main/java/other/Broken.java"), "package other; public class Broken { !!! }");

        var context = builder.buildUnitTestContext(fixture.projectId(), java.util.Set.of(fixture.caseId()));

        assertThat(context.classes()).extracting("qualifiedName").containsExactlyInAnyOrder(
                "com.piggymetrics.account.service.AccountServiceImpl", "com.piggymetrics.account.domain.Account");
        assertThat(context.analysis().failedParseFiles()).isEqualTo(1);
    }

    @Test
    void unparseableReferencedAccountMustNotBeSilentlyOmitted(@TempDir Path snapshot) throws Exception {
        Fixture fixture = fixture(snapshot);
        write(snapshot.resolve(ACCOUNT_PATH), "package com.piggymetrics.account.domain; public class Account { !!! }");

        assertThatThrownBy(() -> builder.buildUnitTestContext(fixture.projectId(), java.util.Set.of(fixture.caseId())))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("Account.java");
    }

    @Test
    void brokenSameFqnInAnotherModuleDoesNotHideTheValidDependency(@TempDir Path snapshot) throws Exception {
        Fixture fixture = fixture(snapshot);
        String unrelated = "other-service/src/main/java/com/piggymetrics/account/domain/Account.java";
        write(snapshot.resolve(unrelated), "package com.piggymetrics.account.domain; public class Account { !!! }");
        javaClass(fixture.projectId(), "com.piggymetrics.account.domain.Account", unrelated, "public class Account {}", ClassType.ENTITY);

        var context = builder.buildUnitTestContext(fixture.projectId(), java.util.Set.of(fixture.caseId()));

        assertThat(context.classes()).filteredOn(c -> c.qualifiedName().equals("com.piggymetrics.account.domain.Account"))
                .singleElement().satisfies(value -> assertThat(value.filePath()).isEqualTo(ACCOUNT_PATH));
    }

    @Test
    void unparseableNestedDependencyReportsItsDeclaringFile(@TempDir Path snapshot) throws Exception {
        Fixture fixture = fixture(snapshot);
        write(snapshot.resolve(SERVICE_PATH), "package com.piggymetrics.account.service;\n"
                + "import com.piggymetrics.account.domain.Account;\nimport com.piggymetrics.account.domain.Outer.Inner;\n"
                + "public class AccountServiceImpl { private Inner inner; public Account findByName(String name) { return null; } }");
        write(snapshot.resolve("piggymetrics/account-service/src/main/java/com/piggymetrics/account/domain/Outer.java"),
                "package com.piggymetrics.account.domain; public class Outer { !!! }");

        assertThatThrownBy(() -> builder.buildUnitTestContext(fixture.projectId(), java.util.Set.of(fixture.caseId())))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("Outer.java");
    }

    @Test
    void unparseableActiveTargetCannotUseSameFqnFromAnotherModule(@TempDir Path snapshot) throws Exception {
        Fixture fixture = fixture(snapshot);
        write(snapshot.resolve("other-service/src/main/java/com/piggymetrics/account/service/AccountServiceImpl.java"),
                Files.readString(snapshot.resolve(SERVICE_PATH)));
        write(snapshot.resolve(SERVICE_PATH), "package com.piggymetrics.account.service; public class AccountServiceImpl { !!! }");

        assertThatThrownBy(() -> builder.buildUnitTestContext(fixture.projectId(), java.util.Set.of(fixture.caseId())))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("AccountServiceImpl");
    }

    @Test
    void brokenLocalDependencyCannotUseParsedSameFqnFromAnotherModule(@TempDir Path snapshot) throws Exception {
        Fixture fixture = fixture(snapshot);
        String unrelated = "other-service/src/main/java/com/piggymetrics/account/domain/Account.java";
        write(snapshot.resolve(unrelated), "package com.piggymetrics.account.domain; public class Account { public int unrelated; }");
        javaClass(fixture.projectId(), "com.piggymetrics.account.domain.Account", unrelated, "public class Account {}", ClassType.ENTITY);
        write(snapshot.resolve(ACCOUNT_PATH), "package com.piggymetrics.account.domain; public class Account { !!! }");

        assertThatThrownBy(() -> builder.buildUnitTestContext(fixture.projectId(), java.util.Set.of(fixture.caseId())))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("Account.java");
    }

    private Fixture fixture(Path snapshot) throws Exception {
        writeSources(snapshot, "com.piggymetrics.account.domain.Account");
        Project project = new Project(); project.setName("active-source-context-" + System.nanoTime());
        project.setSourceType(SourceType.ZIP); project.setStatus(ProjectStatus.ANALYZED); project.setStoragePath(snapshot.toString());
        project = projects.save(project);
        JavaClass owner = javaClass(project.getId(), "com.piggymetrics.account.service.AccountServiceImpl", SERVICE_PATH, LEGACY_SOURCE, ClassType.SERVICE);
        javaClass(project.getId(), "com.piggymetrics.account.domain.Account", ACCOUNT_PATH, "public class Account {}", ClassType.ENTITY);
        javaClass(project.getId(), "com.piggymetrics.statistics.domain.Account", STATISTICS_PATH, "public class Account {}", ClassType.ENTITY);
        JavaMethod method = new JavaMethod(); method.setClassId(owner.getId()); method.setMethodName("findByName");
        method.setReturnType("Account"); method.setParameters(List.of(new MethodParam("name", "String")));
        method.setVisibility(Visibility.PUBLIC); method.setSourceCode("public Account findByName(String name) { return null; }");
        method.setLineStart(1); method.setLineEnd(1); method = methods.save(method);
        BusinessRule rule = new BusinessRule(); rule.setProjectId(project.getId()); rule.setMethodId(method.getId());
        rule.setRuleCode("BR-001"); rule.setDescription("Return the matching account"); rule.setSource(RuleSource.AI_GENERATED);
        rule.setStatus(ReviewStatus.APPROVED); rule = rules.save(rule);
        TestPlan plan = new TestPlan(); plan.setProjectId(project.getId()); plan.setBusinessRuleId(rule.getId());
        plan.setPlanCode("TP-001"); plan.setTitle("Find account"); plan.setDescription("Verify account lookup");
        plan.setTestType(TestType.HAPPY_PATH); plan.setStatus(ReviewStatus.APPROVED); plan = plans.save(plan);
        TestCase testCase = new TestCase(); testCase.setTestPlanId(plan.getId()); testCase.setCaseCode("TC-001");
        testCase.setDescription("Lookup an existing account"); testCase.setExpectedResult("Matching account");
        testCase.setPriority(Priority.MEDIUM); testCase.setTestType(TestType.HAPPY_PATH); testCase.setStatus(ReviewStatus.APPROVED);
        testCase = cases.saveAndFlush(testCase);
        return new Fixture(project.getId(), owner.getId(), method.getId(), rule.getId(), plan.getId(), testCase.getId());
    }

    private JavaClass javaClass(Long projectId, String qualifiedName, String path, String source, ClassType type) {
        JavaClass value = new JavaClass(); value.setProjectId(projectId); value.setQualifiedName(qualifiedName);
        value.setClassName(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
        value.setPackageName(qualifiedName.substring(0, qualifiedName.lastIndexOf('.')));
        value.setFilePath(path); value.setSourceCode(source); value.setClassType(type); return classes.save(value);
    }

    private void writeSources(Path root, String accountImport) throws Exception {
        write(root.resolve(SERVICE_PATH), "package com.piggymetrics.account.service;\nimport " + accountImport + ";\n"
                + "public class AccountServiceImpl { public Account findByName(String name) { return null; } }");
        write(root.resolve(ACCOUNT_PATH), "package com.piggymetrics.account.domain; public class Account {}");
        write(root.resolve(STATISTICS_PATH), "package com.piggymetrics.statistics.domain; public class Account {}");
    }

    private void write(Path file, String source) throws Exception {
        Files.createDirectories(file.getParent()); Files.writeString(file, source);
    }

    private record Fixture(Long projectId, Long classId, Long methodId, Long ruleId, Long planId, Long caseId) {}
}
