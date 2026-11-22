package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Luồng rút gọn "Áp dụng source mới": chỉ artifact của method thay đổi bị xóa để sinh lại. */
@SpringBootTest(properties = "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes")
@Transactional
class SourceUpdateApplySourceIntegrationTest {
    private static final String SERVICE_SOURCE = "package demo; @org.springframework.stereotype.Service public class MailService { "
            + "public int send() { return 1; } public int keep() { return 2; } }";

    @MockBean private com.greytest.service.generation.IncrementalCheckpointCoordinator generationCoordinator;
    @Autowired private SourceUpdateService service;
    @Autowired private ServicePipelineStatusService pipelineStatus;
    @Autowired private ServiceScopeResolver scopeResolver;
    @Autowired private ProjectRepository projects;
    @Autowired private SourceRevisionRepository revisions;
    @Autowired private SourceUpdateRepository updates;
    @Autowired private SourceUpdateItemRepository items;
    @Autowired private BusinessRuleRepository rules;
    @Autowired private TestPlanRepository plans;
    @Autowired private TestCaseRepository testCases;
    @Autowired private UnitTestRepository unitTests;
    @Autowired private JavaClassRepository classes;
    @Autowired private JavaMethodRepository methods;
    @Autowired private com.greytest.service.analysis.AnalysisService analysis;

    @Test
    void deletesOnlyArtifactsOfChangedMethodAndPromotesSnapshot(@TempDir Path root) throws Exception {
        Fixture fixture = prepare(root, SERVICE_SOURCE.replace("return 1", "return 3"));
        SourceUpdate update = update(fixture, SourceUpdateAction.UPDATE, "public int send() { return 3; }");
        var scope = scopeResolver.resolve(fixture.projectId(), null);
        assertThat(pipelineStatus.status(fixture.projectId(), scope)).isEqualTo(ProjectStatus.TEST_GENERATED);

        service.applySourceChanges(fixture.projectId(), update.getId(), admin());
        rules.flush();

        assertDeleted(fixture.changed());
        assertThat(rules.findById(fixture.unchanged().rule().getId())).isPresent();
        assertThat(plans.findById(fixture.unchanged().plan().getId())).isPresent();
        assertThat(testCases.findById(fixture.unchanged().testCase().getId())).isPresent();
        assertThat(unitTests.findById(fixture.unchanged().unitTest().getId())).isPresent();
        assertThat(methods.findById(fixture.send().getId()).orElseThrow().getSourceCode()).contains("return 3");
        Project after = projects.findById(fixture.projectId()).orElseThrow();
        assertThat(after.getStoragePath()).isEqualTo(fixture.candidate().toString());
        assertThat(after.getActiveSourceRevisionId()).isEqualTo(update.getCandidateRevisionId());
        assertThat(after.getStatus()).isEqualTo(ProjectStatus.BR_PENDING_REVIEW);
        // Trạng thái suy ra từ artifact cũng phải báo bước BR chưa xong vì send() đã mất BR.
        assertThat(pipelineStatus.status(fixture.projectId(), scope)).isEqualTo(ProjectStatus.BR_PENDING_REVIEW);
        assertThat(updates.findById(update.getId()).orElseThrow().getStatus()).isEqualTo(SourceUpdateStatus.APPLIED);
        // Mỗi artifact bị xóa có một mục REMOVE kèm mã để giao diện hiện phần "đã xóa".
        var removed = items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(update.getId()).stream()
                .filter(i -> i.getAction() == SourceUpdateAction.REMOVE && i.getTargetType() != SourceUpdateTargetType.METHOD).toList();
        assertThat(removed).extracting(SourceUpdateItem::getTargetId).containsExactlyInAnyOrder(
                fixture.changed().rule().getId(), fixture.changed().plan().getId(),
                fixture.changed().testCase().getId(), fixture.changed().unitTest().getId());
        assertThat(removed).filteredOn(i -> i.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE)
                .singleElement().extracting(SourceUpdateItem::getBeforeData).asString().contains("\"ruleCode\":\"BR-001\"");
    }

    @Test
    void deletingAMethodKeepsTheCurrentPipelineStep(@TempDir Path root) throws Exception {
        Fixture fixture = prepare(root, SERVICE_SOURCE.replace("public int send() { return 1; } ", ""));
        SourceUpdate update = update(fixture, SourceUpdateAction.REMOVE, null);

        service.applySourceChanges(fixture.projectId(), update.getId(), admin());
        rules.flush();

        assertDeleted(fixture.changed());
        assertThat(methods.findById(fixture.send().getId())).isEmpty();
        assertThat(unitTests.findById(fixture.unchanged().unitTest().getId())).isPresent();
        assertThat(projects.findById(fixture.projectId()).orElseThrow().getStatus()).isEqualTo(ProjectStatus.TEST_GENERATED);
        assertThat(pipelineStatus.status(fixture.projectId(), scopeResolver.resolve(fixture.projectId(), null)))
                .isEqualTo(ProjectStatus.TEST_GENERATED);
    }

    private record Chain(BusinessRule rule, TestPlan plan, TestCase testCase, UnitTest unitTest) { }

    private record Fixture(Long projectId, Path candidate, JavaMethod send, Chain changed, Chain unchanged) { }

    private Fixture prepare(Path root, String candidateSource) throws Exception {
        Path baseline = root.resolve("baseline");
        Path candidate = root.resolve("candidate");
        for (Path dir : List.of(baseline, candidate)) {
            Files.createDirectories(dir.resolve("src/main/java/demo"));
            Files.writeString(dir.resolve("src/main/java/demo/MailService.java"), dir == baseline ? SERVICE_SOURCE : candidateSource);
        }
        Project project = new Project(); project.setName("apply-source-" + System.nanoTime());
        project.setSourceType(SourceType.ZIP); project.setStatus(ProjectStatus.ANALYZED);
        project.setStoragePath(baseline.toString()); project = projects.save(project);
        analysis.analyze(project.getId());
        project = projects.findById(project.getId()).orElseThrow();
        project.setStatus(ProjectStatus.TEST_GENERATED); projects.save(project);

        JavaClass owner = classes.findByProjectId(project.getId()).stream()
                .filter(type -> type.getClassName().equals("MailService")).findFirst().orElseThrow();
        JavaMethod send = method(owner, "send");
        Chain changed = chain(project.getId(), send, "1");
        Chain unchanged = chain(project.getId(), method(owner, "keep"), "2");
        return new Fixture(project.getId(), candidate, send, changed, unchanged);
    }

    private SourceUpdate update(Fixture fixture, SourceUpdateAction action, String afterSource) {
        SourceRevision revision = new SourceRevision(); revision.setProjectId(fixture.projectId());
        revision.setSourceType(SourceType.ZIP); revision.setStoragePath(fixture.candidate().toString()); revision = revisions.save(revision);
        SourceUpdate update = new SourceUpdate(); update.setProjectId(fixture.projectId());
        update.setCandidateRevisionId(revision.getId()); update.setStatus(SourceUpdateStatus.ANALYZED); update = updates.save(update);
        SourceUpdateItem item = new SourceUpdateItem(); item.setSourceUpdateId(update.getId());
        item.setTargetType(SourceUpdateTargetType.METHOD); item.setTargetId(fixture.send().getId());
        item.setTargetKey("demo.MailService#send()"); item.setAction(action);
        item.setAfterData(afterSource); item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
        items.saveAndFlush(item);
        return update;
    }

    private void assertDeleted(Chain chain) {
        assertThat(rules.findById(chain.rule().getId())).isEmpty();
        assertThat(plans.findById(chain.plan().getId())).isEmpty();
        assertThat(testCases.findById(chain.testCase().getId())).isEmpty();
        assertThat(unitTests.findById(chain.unitTest().getId())).isEmpty();
    }

    private JavaMethod method(JavaClass owner, String name) {
        return methods.findByClassId(owner.getId()).stream().filter(m -> m.getMethodName().equals(name)).findFirst().orElseThrow();
    }

    private Chain chain(Long projectId, JavaMethod method, String suffix) {
        BusinessRule rule = new BusinessRule(); rule.setProjectId(projectId); rule.setMethodId(method.getId());
        rule.setRuleCode("BR-00" + suffix); rule.setDescription("rule " + suffix);
        rule.setSource(RuleSource.AI_GENERATED); rule.setStatus(ReviewStatus.APPROVED); rule = rules.save(rule);
        TestPlan plan = new TestPlan(); plan.setProjectId(projectId); plan.setBusinessRuleId(rule.getId());
        plan.setPlanCode("TP-00" + suffix); plan.setTitle("plan"); plan.setDescription("plan");
        plan.setTestType(TestType.HAPPY_PATH); plan.setStatus(ReviewStatus.APPROVED); plan = plans.save(plan);
        TestCase testCase = new TestCase(); testCase.setTestPlanId(plan.getId()); testCase.setCaseCode("TC-00" + suffix);
        testCase.setTestType(TestType.HAPPY_PATH); testCase.setDescription("case"); testCase.setPreconditions("Ready");
        testCase.setExpectedResult("ok"); testCase.setTestData(java.util.Map.of("input", java.util.Map.of()));
        testCase.setPriority(Priority.HIGH); testCase.setStatus(ReviewStatus.APPROVED); testCase = testCases.save(testCase);
        UnitTest unitTest = new UnitTest(); unitTest.setTestCaseId(testCase.getId()); unitTest.setTestClassName("MailServiceTest");
        unitTest.setTestMethodName("test" + suffix); unitTest.setPackageName("demo"); unitTest.setGenerationType("NEW_TEST");
        unitTest.setSourceCode("class MailServiceTest {}"); unitTest = unitTests.saveAndFlush(unitTest);
        return new Chain(rule, plan, testCase, unitTest);
    }

    private AuthUser admin() { AuthUser admin = new AuthUser(); admin.setRole(UserRole.ADMIN); return admin; }
}
