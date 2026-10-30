package com.greytest.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;
import com.greytest.entity.*;
import com.greytest.entity.enums.*;
import com.greytest.repository.*;

@SpringBootTest(properties = "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes")
@Transactional
class SourceUpdateApplyIntegrationTest {
    // Các fixture này chỉ kiểm tra thao tác Apply; gate checkpoint thật có suite protection riêng.
    @MockBean private com.greytest.service.generation.IncrementalCheckpointCoordinator generationCoordinator;
    @Autowired private SourceUpdateService service;
    @Autowired private ProjectRepository projects;
    @Autowired private SourceRevisionRepository revisions;
    @Autowired private SourceUpdateRepository updates;
    @Autowired private SourceUpdateItemRepository items;
    @Autowired private BusinessRuleRepository rules;
    @Autowired private TestPlanRepository plans;
    @Autowired private TestPlanCoveredRuleRepository coverage;
    @Autowired private JavaClassRepository classes;
    @Autowired private JavaMethodRepository methods;
    @Autowired private com.greytest.service.analysis.AnalysisService analysis;
    @Autowired private EndpointRepository endpoints;
    @Autowired private TestCaseRepository testCases;
    @Autowired private UnitTestService unitTestService;
    @Autowired private UnitTestRepository unitTests;

    @Test
    void applying13GeneratedDraftsMakesAll38UnitTestsVisibleAndPreserves25OldTests(@TempDir Path snapshot) throws Exception {
        SourceUpdate update = draft(snapshot);
        BusinessRule rule = rule(update.getProjectId(), "BR-001");
        TestPlan plan = new TestPlan(); plan.setProjectId(update.getProjectId()); plan.setBusinessRuleId(rule.getId());
        plan.setPlanCode("TP-001"); plan.setTitle("Plan"); plan.setDescription("Approved plan");
        plan.setTestType(TestType.HAPPY_PATH); plan.setStatus(ReviewStatus.APPROVED); plan = plans.saveAndFlush(plan);
        var oldSources = new java.util.LinkedHashMap<Long, String>();
        var allCaseIds = new java.util.ArrayList<Long>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int i = 1; i <= 38; i++) {
            TestCase testCase = approvedCase(plan.getId(), String.format("TC-%03d", i)); allCaseIds.add(testCase.getId());
            String source = "package demo; class Case" + i + "Test { @org.junit.jupiter.api.Test void testCase() {} }";
            if (i <= 25) {
                UnitTest unit = new UnitTest(); unit.setTestCaseId(testCase.getId()); unit.setTestClassName("Case" + i + "Test");
                unit.setTestMethodName("testCase"); unit.setPackageName("demo"); unit.setGenerationType("NEW_TEST");
                unit.setSourceCode(source); unit.setFilePath("src/test/java/demo/Case" + i + "Test.java");
                unit = unitTests.saveAndFlush(unit); oldSources.put(unit.getId(), source);
            } else {
                var output = new com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto(testCase.getId(),
                        "Case" + i + "Test", "testCase", "demo", "NEW_TEST", source);
                item(update, SourceUpdateTargetType.UNIT_TEST, null, "case:" + testCase.getId(), json.writeValueAsString(output));
            }
        }
        assertThat(unitTestService.list(update.getProjectId())).hasSize(25);
        assertThat(unitTests.findByTestCaseIdIn(allCaseIds)).hasSize(25);

        service.applyUpdate(update.getProjectId(), update.getId(), admin());

        assertThat(unitTests.findByTestCaseIdIn(allCaseIds)).hasSize(38);
        assertThat(unitTestService.list(update.getProjectId())).hasSize(38);
        oldSources.forEach((id, source) -> assertThat(unitTests.findById(id).orElseThrow().getSourceCode()).isEqualTo(source));
        assertThat(updates.findById(update.getId()).orElseThrow().getStatus()).isEqualTo(SourceUpdateStatus.APPLIED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void keepsCheckpointReviewedOutputApprovedAndInvalidatesOnlyRetainedChildren(
            boolean retainedChild, @TempDir Path snapshot) throws Exception {
        SourceUpdate update = draft(snapshot);
        BusinessRule rule = rule(update.getProjectId(), "BR-001");
        TestPlan plan = new TestPlan(); plan.setProjectId(update.getProjectId()); plan.setBusinessRuleId(rule.getId());
        plan.setPlanCode("TP-001"); plan.setTitle("Original plan"); plan.setDescription("Original description");
        plan.setTestType(TestType.HAPPY_PATH); plan.setStatus(ReviewStatus.APPROVED); plan.setIsModified(false);
        plan = plans.saveAndFlush(plan);
        TestCase refreshed = approvedCase(plan.getId(), "TC-001");
        TestCase retained = retainedChild ? approvedCase(plan.getId(), "TC-002") : null;
        item(update, SourceUpdateTargetType.BUSINESS_RULE, rule.getId(), null,
                "{\"method_id\":1,\"description\":\"Updated rule\",\"category\":\"BUSINESS_LOGIC\"}");
        item(update, SourceUpdateTargetType.TEST_PLAN, plan.getId(), null,
                "{\"method_id\":1,\"rule_id\":" + rule.getId() + ",\"covered_rule_ids\":[" + rule.getId()
                        + "],\"title\":\"Reviewed plan\",\"description\":\"Updated description\",\"test_type\":\"BOUNDARY\"}");
        item(update, SourceUpdateTargetType.TEST_CASE, refreshed.getId(), null,
                "{\"plan_id\":" + plan.getId() + ",\"test_type\":\"EXCEPTION\",\"description\":\"Reviewed case\","
                        + "\"preconditions\":\"Ready\",\"test_data\":{\"input\":{}},\"expected_result\":\"Updated expectation\","
                        + "\"priority\":\"HIGH\",\"trace_source\":\"BR-001 -> TP-reviewed\"}");
        item(update, SourceUpdateTargetType.UNIT_TEST, null, "case:" + refreshed.getId(),
                "{\"case_id\":" + refreshed.getId() + ",\"test_class_name\":\"ReviewedTest\",\"test_method_name\":\"checksResult\","
                        + "\"package_name\":\"demo\",\"generation_type\":\"NEW_TEST\","
                        + "\"source_code\":\"package demo; class ReviewedTest { @org.junit.jupiter.api.Test void checksResult() {} }\"}");
        org.mockito.Mockito.when(generationCoordinator.isRecordedOutput(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    SourceUpdateItem proposal = call.getArgument(0);
                    return proposal.getTargetType() == SourceUpdateTargetType.TEST_PLAN
                            || proposal.getTargetType() == SourceUpdateTargetType.TEST_CASE;
                });

        service.applyUpdate(update.getProjectId(), update.getId(), admin());

        org.mockito.Mockito.verify(generationCoordinator).assertReadyForApply(update.getId());
        assertThat(plans.findById(plan.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(testCases.findById(refreshed.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.APPROVED);
        Long planId = plan.getId();
        org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(plans.findById(planId).orElseThrow().getTestType()).isEqualTo(TestType.BOUNDARY);
            softly.assertThat(testCases.findById(refreshed.getId()).orElseThrow().getTestType()).isEqualTo(TestType.EXCEPTION);
            softly.assertThat(testCases.findById(refreshed.getId()).orElseThrow().getTraceSource()).isEqualTo("BR-001 -> TP-reviewed");
        });
        assertThat(unitTestService.listFiles(update.getProjectId())).hasSize(1);
        if (retainedChild) assertThat(testCases.findById(retained.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
        assertThat(projects.findById(update.getProjectId()).orElseThrow().getStatus())
                .isEqualTo(retainedChild ? ProjectStatus.PLAN_PENDING_REVIEW : ProjectStatus.TEST_GENERATED);
    }

    private TestCase approvedCase(Long planId, String code) {
        TestCase testCase = new TestCase(); testCase.setTestPlanId(planId); testCase.setCaseCode(code);
        testCase.setTestType(TestType.HAPPY_PATH); testCase.setDescription("Original case"); testCase.setPreconditions("Ready");
        testCase.setExpectedResult("Original expectation"); testCase.setTestData(java.util.Map.of("input", java.util.Map.of()));
        testCase.setPriority(Priority.HIGH); testCase.setTraceSource("BR-001 -> TP-001");
        testCase.setStatus(ReviewStatus.APPROVED); testCase.setIsModified(false);
        return testCases.saveAndFlush(testCase);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "wrapper/"})
    void removesDeletedControllerEndpointAndKeepsSurvivingMethodIds(String wrapper, @TempDir Path snapshot) throws Exception {
        Path baseline = snapshot.resolve("baseline");
        Path candidate = snapshot.resolve("candidate");
        String originalService = "package demo; @org.springframework.stereotype.Service public class MailService { public int send() { return 1; } }";
        String originalController = "package demo; @org.springframework.web.bind.annotation.RestController public class MailController { "
                + "@org.springframework.web.bind.annotation.GetMapping(\"/search\") public int search() { return 1; } "
                + "@org.springframework.web.bind.annotation.GetMapping(\"/keep\") public int keep() { return 2; } }";
        for (Path root : List.of(baseline, candidate)) {
            String prefix = root == baseline ? "" : wrapper;
            Files.createDirectories(root.resolve(prefix + "a/src/main/java/demo"));
            Files.createDirectories(root.resolve(prefix + "b/src/main/java/demo"));
            Files.writeString(root.resolve(prefix + "a/src/main/java/demo/MailService.java"),
                    root == baseline ? originalService : originalService.replace("return 1", "return 3"));
            Files.writeString(root.resolve(prefix + "a/src/main/java/demo/MailController.java"), root == baseline ? originalController
                    : originalController.replace("@org.springframework.web.bind.annotation.GetMapping(\"/search\") public int search() { return 1; } ", ""));
            Files.writeString(root.resolve(prefix + "b/src/main/java/demo/MailController.java"),
                    originalController.replace("/search", "/other-search").replace("/keep", "/other-keep"));
        }
        SourceUpdate update = draft(candidate);
        Project project = projects.findById(update.getProjectId()).orElseThrow();
        project.setStoragePath(baseline.toString()); projects.save(project);
        analysis.analyze(project.getId());
        JavaClass controller = classes.findByProjectId(project.getId()).stream()
                .filter(owner -> owner.getClassName().equals("MailController") && owner.getFilePath().startsWith("a/"))
                .findFirst().orElseThrow();
        JavaClass otherController = classes.findByProjectId(project.getId()).stream()
                .filter(owner -> owner.getClassName().equals("MailController") && owner.getFilePath().startsWith("b/"))
                .findFirst().orElseThrow();
        List<Long> otherMethodIds = methods.findByClassId(otherController.getId()).stream().map(JavaMethod::getId).toList();
        JavaMethod kept = methods.findByClassId(controller.getId()).stream()
                .filter(method -> method.getMethodName().equals("keep")).findFirst().orElseThrow();
        JavaMethod removed = methods.findByClassId(controller.getId()).stream()
                .filter(method -> method.getMethodName().equals("search")).findFirst().orElseThrow();
        JavaClass owner = classes.findByProjectId(project.getId()).stream()
                .filter(type -> type.getClassName().equals("MailService")).findFirst().orElseThrow();
        JavaMethod changed = methods.findByClassId(owner.getId()).get(0);
        item(update, SourceUpdateTargetType.METHOD, changed.getId(), "demo.MailService#send()", "public int send() { return 3; }");
        SourceUpdateItem proposal = items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(update.getId()).get(0);
        proposal.setAction(SourceUpdateAction.UPDATE); items.saveAndFlush(proposal);

        service.applyUpdate(project.getId(), update.getId(), admin());
        methods.flush();

        assertThat(methods.findById(removed.getId())).isEmpty();
        assertThat(endpoints.findByMethodId(removed.getId())).isEmpty();
        assertThat(methods.findByClassId(controller.getId())).extracting(JavaMethod::getId).containsExactly(kept.getId());
        assertThat(classes.findById(controller.getId()).orElseThrow().getSourceCode()).doesNotContain("/search");
        assertThat(methods.findById(changed.getId())).isPresent();
        assertThat(methods.findByClassId(otherController.getId())).extracting(JavaMethod::getId)
                .containsExactlyInAnyOrderElementsOf(otherMethodIds);
    }

    @Test
    void replacesAlreadyStagedCoverageWithoutDuplicateConstraint(@TempDir Path snapshot) {
        SourceUpdate update = draft(snapshot);
        BusinessRule first = rule(update.getProjectId(), "BR-001");
        BusinessRule second = rule(update.getProjectId(), "BR-002");
        TestPlan plan = new TestPlan(); plan.setProjectId(update.getProjectId()); plan.setBusinessRuleId(first.getId());
        plan.setPlanCode("TP-001"); plan.setTitle("send"); plan.setDescription("send mail");
        plan.setStatus(ReviewStatus.APPROVED); plan.setTestType(TestType.HAPPY_PATH); plan = plans.save(plan);
        TestPlanCoveredRule old = new TestPlanCoveredRule(); old.setTestPlanId(plan.getId()); old.setBusinessRuleId(first.getId());
        coverage.saveAndFlush(old);
        item(update, SourceUpdateTargetType.TEST_PLAN, plan.getId(), null,
                "{\"method_id\":1,\"rule_id\":" + first.getId() + ",\"covered_rule_ids\":[" + first.getId() + "," + second.getId()
                        + "],\"title\":\"send\",\"description\":\"send mail\",\"test_type\":\"HAPPY_PATH\"}");

        service.applyUpdate(update.getProjectId(), update.getId(), admin());
        coverage.flush();

        assertThat(coverage.findByTestPlanId(plan.getId())).extracting(TestPlanCoveredRule::getBusinessRuleId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(plans.findByProjectId(update.getProjectId())).hasSize(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/* Keep the address contract. */", "// Keep the address contract.\n"})
    void directlyAppliesParameterizedMethodWithoutOverwritingAnOverload(String comment, @TempDir Path snapshot) throws Exception {
        SourceUpdate update = draft(snapshot);
        Path file = snapshot.resolve("src/main/java/demo/MailService.java"); Files.createDirectories(file.getParent());
        Files.writeString(file, "package demo;\nclass MailService {\n"
                + " int send(int code) { return code; }\n int send(String address) { " + comment + " return address.length(); }\n}\n");
        JavaClass owner = new JavaClass(); owner.setProjectId(update.getProjectId()); owner.setClassName("MailService");
        owner.setQualifiedName("demo.MailService"); owner.setClassType(ClassType.SERVICE);
        owner.setFilePath("src/main/java/demo/MailService.java"); owner = classes.save(owner);
        JavaMethod original = new JavaMethod(); original.setClassId(owner.getId()); original.setMethodName("send");
        original.setParameters(List.of(new MethodParam("code", "int"))); original.setSourceCode("int send(int code) { return code; }");
        original.setReturnType("int"); original.setVisibility(Visibility.PACKAGE_PRIVATE);
        original.setLineStart(3); original.setLineEnd(3); original = methods.save(original);
        item(update, SourceUpdateTargetType.METHOD, null, "demo.MailService#send(String)",
                "int send(String address) { return address.length(); }");

        service.applyUpdate(update.getProjectId(), update.getId(), admin());

        assertThat(methods.findByClassId(owner.getId())).hasSize(2).anySatisfy(method -> {
            assertThat(method.getParameters()).containsExactly(new MethodParam("address", "String"));
            assertThat(method.getLineStart()).isEqualTo(4);
            assertThat(method.getLineEnd()).isEqualTo(comment.contains("\n") ? 5 : 4);
            assertThat(method.getReturnType()).isEqualTo("int");
        });
        assertThat(methods.findById(original.getId())).get().satisfies(method -> {
            assertThat(method.getParameters()).containsExactly(new MethodParam("code", "int"));
            assertThat(method.getSourceCode()).contains("return code;");
        });
    }

    @Test
    void refusesSnapshotPromotionWhenAMethodChangeWasRejected(@TempDir Path snapshot) throws Exception {
        SourceUpdate update = draft(snapshot);
        Path file = snapshot.resolve("src/main/java/demo/MailService.java"); Files.createDirectories(file.getParent());
        Files.writeString(file, "package demo; class MailService { int retry(int code) { return code * 2; } "
                + "int send(String address) { return address.length(); } }");
        JavaClass owner = new JavaClass(); owner.setProjectId(update.getProjectId()); owner.setClassName("MailService");
        owner.setQualifiedName("demo.MailService"); owner.setClassType(ClassType.SERVICE);
        owner.setFilePath("src/main/java/demo/MailService.java"); owner = classes.save(owner);
        JavaMethod original = new JavaMethod(); original.setClassId(owner.getId()); original.setMethodName("retry");
        original.setParameters(List.of(new MethodParam("code", "int"))); original.setSourceCode("int retry(int code) { return code; }");
        original.setReturnType("int"); original.setVisibility(Visibility.PACKAGE_PRIVATE);
        original.setLineStart(1); original.setLineEnd(1); original = methods.save(original);
        item(update, SourceUpdateTargetType.METHOD, null, "demo.MailService#send(String)",
                "int send(String address) { return address.length(); }");
        SourceUpdateItem rejected = new SourceUpdateItem(); rejected.setSourceUpdateId(update.getId());
        rejected.setTargetType(SourceUpdateTargetType.METHOD); rejected.setTargetKey("demo.MailService#retry(int)");
        rejected.setAction(SourceUpdateAction.UPDATE); rejected.setReviewStatus(SourceUpdateReviewStatus.REJECTED);
        rejected.setAfterData("int retry(int code) { return code * 2; }"); items.saveAndFlush(rejected);

        assertThatThrownBy(() -> service.applyUpdate(update.getProjectId(), update.getId(), admin()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("từ chối");
        assertThat(original.getSourceCode()).contains("return code;");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "@Deprecated String label() { return \"https://clinic.example/a/b\"; }",
            "String label() { return \"https://clinic.example/a//b\"; }"})
    void rejectsMethodLiteralOrAnnotationChangesEvenWhenCommentsDiffer(String reviewed, @TempDir Path snapshot) throws Exception {
        SourceUpdate update = draft(snapshot);
        Path file = snapshot.resolve("src/main/java/demo/MailService.java"); Files.createDirectories(file.getParent());
        String source = "package demo; class MailService { @Deprecated String label() { /* Source comment. */ return \"https://clinic.example/a//b\"; } }";
        Files.writeString(file, source);
        item(update, SourceUpdateTargetType.METHOD, null, "demo.MailService#label()", reviewed);
        assertThatThrownBy(() -> service.applyUpdate(update.getProjectId(), update.getId(), admin()))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file)).isEqualTo(source);
    }

    @Test
    void refusesApplyWhenGeneratedTargetWasDeleted(@TempDir Path snapshot) {
        SourceUpdate update = draft(snapshot);
        item(update, SourceUpdateTargetType.BUSINESS_RULE, Long.MAX_VALUE, null,
                "{\"method_id\":1,\"branch_id\":\"IF-1\",\"description\":\"required\",\"category\":\"VALIDATION\"}");
        assertThatThrownBy(() -> service.applyUpdate(update.getProjectId(), update.getId(), admin()))
                .isInstanceOf(IllegalStateException.class);
    }

    private SourceUpdate draft(Path snapshot) {
        Project project = new Project(); project.setName("apply-test-" + System.nanoTime());
        project.setSourceType(SourceType.ZIP); project.setStatus(ProjectStatus.ANALYZED); project = projects.save(project);
        SourceRevision revision = new SourceRevision(); revision.setProjectId(project.getId());
        revision.setSourceType(SourceType.ZIP); revision.setStoragePath(snapshot.toString()); revision = revisions.save(revision);
        SourceUpdate update = new SourceUpdate(); update.setProjectId(project.getId());
        update.setCandidateRevisionId(revision.getId()); update.setStatus(SourceUpdateStatus.ANALYZED); return updates.save(update);
    }

    private BusinessRule rule(Long projectId, String code) {
        BusinessRule rule = new BusinessRule(); rule.setProjectId(projectId); rule.setRuleCode(code);
        rule.setDescription("mail rule"); rule.setSource(RuleSource.AI_GENERATED); rule.setStatus(ReviewStatus.APPROVED);
        return rules.save(rule);
    }

    private void item(SourceUpdate update, SourceUpdateTargetType type, Long targetId, String key, String data) {
        SourceUpdateItem item = new SourceUpdateItem(); item.setSourceUpdateId(update.getId()); item.setTargetType(type);
        item.setTargetId(targetId); item.setTargetKey(key); item.setAfterData(data);
        item.setAction(SourceUpdateAction.CREATE); item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED); items.saveAndFlush(item);
    }

    private AuthUser admin() { AuthUser admin = new AuthUser(); admin.setRole(UserRole.ADMIN); return admin; }
}
