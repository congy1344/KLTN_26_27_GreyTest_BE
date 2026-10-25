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

    @Test
    void directlyAppliesParameterizedMethodWithoutOverwritingAnOverload(@TempDir Path snapshot) throws Exception {
        SourceUpdate update = draft(snapshot);
        Path file = snapshot.resolve("src/main/java/demo/MailService.java"); Files.createDirectories(file.getParent());
        Files.writeString(file, "package demo;\nclass MailService {\n"
                + " int send(int code) { return code; }\n int send(String address) { return address.length(); }\n}\n");
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
            assertThat(method.getLineEnd()).isEqualTo(4);
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
