package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.entity.*;
import com.greytest.entity.enums.*;
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.*;
import com.greytest.service.diff.*;
import com.greytest.service.storage.SourceRevisionStorageService;

class SourceUpdateAppliedArtifactsTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SourceUpdateAction.class, names = {"REMOVE", "UPDATE"})
    void deletesOnlyTheMethodIdFromItsExactModuleWhenQualifiedNamesRepeat(SourceUpdateAction action, @TempDir Path snapshot) throws Exception {
        var projects = mock(ProjectRepository.class); var revisions = mock(SourceRevisionRepository.class);
        var updates = mock(SourceUpdateRepository.class); var items = mock(SourceUpdateItemRepository.class);
        var classes = mock(JavaClassRepository.class); var methods = mock(JavaMethodRepository.class);
        Project project = new Project(); project.setId(10L); project.setOwnerUserId(1L);
        AuthUser user = new AuthUser(); user.setId(1L); user.setRole(UserRole.USER);
        SourceUpdate update = new SourceUpdate(); update.setId(79L); update.setProjectId(10L);
        update.setCandidateRevisionId(89L); update.setStatus(SourceUpdateStatus.ANALYZED);
        SourceRevision candidate = new SourceRevision(); candidate.setStoragePath(snapshot.toString());
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(revisions.findById(89L)).thenReturn(Optional.of(candidate));
        when(updates.findByIdAndProjectId(79L, 10L)).thenReturn(Optional.of(update));
        when(updates.save(any())).thenAnswer(call -> call.getArgument(0));
        JavaClass first = new JavaClass(); first.setId(20L); first.setProjectId(10L); first.setClassType(ClassType.SERVICE);
        first.setQualifiedName("demo.Service"); first.setFilePath("module-a/src/main/java/demo/Service.java");
        first.setSourceCode("independent module metadata");
        JavaClass second = new JavaClass(); second.setId(21L); second.setProjectId(10L); second.setClassType(ClassType.SERVICE);
        second.setQualifiedName("demo.Service"); second.setFilePath("module-b/src/main/java/demo/Service.java");
        JavaMethod firstMethod = new JavaMethod(); firstMethod.setId(50L); firstMethod.setClassId(20L); firstMethod.setMethodName("send");
        firstMethod.setSourceCode("public int send() { return 7; }");
        JavaMethod secondMethod = new JavaMethod(); secondMethod.setId(51L); secondMethod.setClassId(21L); secondMethod.setMethodName("send");
        secondMethod.setSourceCode("public int send() { return 2; }");
        when(classes.findByProjectId(10L)).thenReturn(List.of(first, second));
        when(classes.findById(21L)).thenReturn(Optional.of(second));
        when(methods.findById(51L)).thenReturn(Optional.of(secondMethod));
        when(methods.findByClassId(20L)).thenReturn(List.of(firstMethod));
        when(methods.findByClassId(21L)).thenReturn(List.of(secondMethod));
        var removed = item(SourceUpdateTargetType.METHOD, 51L, null);
        removed.setTargetKey("demo.Service#send()"); removed.setAction(action);
        if (action == SourceUpdateAction.UPDATE) {
            removed.setAfterData("public int send() { return 3; }");
        }
        for (String module : List.of("module-a", "module-b")) {
            Path source = snapshot.resolve(module + "/src/main/java/demo/Service.java");
            Files.createDirectories(source.getParent());
            String method = module.equals("module-a") ? "public int send() { return 7; }"
                    : action == SourceUpdateAction.UPDATE ? "public int send() { return 3; }" : "";
            Files.writeString(source, "package demo; public class Service { " + method + " }");
        }
        when(items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(79L)).thenReturn(List.of(removed));
        var service = new SourceUpdateService(projects, revisions, updates, items,
                mock(SourceRevisionStorageService.class), new SourceUpdateMapper(), mock(SourceDiffService.class),
                mock(ImpactAnalysisService.class), mock(BusinessRuleRepository.class), mock(TestPlanRepository.class),
                mock(TestCaseRepository.class), mock(UnitTestRepository.class), classes, methods, new ObjectMapper(),
                mock(TestPlanCoveredRuleRepository.class));

        service.applyUpdate(10L, 79L, user);

        if (action == SourceUpdateAction.REMOVE) verify(methods).delete(secondMethod);
        else {
            assertThat(secondMethod.getSourceCode()).contains("return 3;");
        }
        assertThat(first.getSourceCode()).isEqualTo("independent module metadata");
        assertThat(firstMethod.getSourceCode()).isEqualTo("public int send() { return 7; }");
        verify(methods, never()).delete(firstMethod);
        verify(methods, never()).save(firstMethod);
        verify(classes, never()).save(first);
    }
    @Test
    void keepingReviewedArtifactsPreservesEveryValueAndModificationFlag(@TempDir Path snapshot) throws Exception {
        var projects = mock(ProjectRepository.class); var revisions = mock(SourceRevisionRepository.class);
        var updates = mock(SourceUpdateRepository.class); var items = mock(SourceUpdateItemRepository.class);
        var rules = mock(BusinessRuleRepository.class); var plans = mock(TestPlanRepository.class);
        var cases = mock(TestCaseRepository.class); var tests = mock(UnitTestRepository.class);
        var classes = mock(JavaClassRepository.class); var methods = mock(JavaMethodRepository.class);
        var covered = mock(TestPlanCoveredRuleRepository.class);
        Project project = new Project(); project.setId(10L); project.setOwnerUserId(1L);
        AuthUser user = new AuthUser(); user.setId(1L); user.setRole(UserRole.USER);
        SourceUpdate update = new SourceUpdate(); update.setId(79L); update.setProjectId(10L);
        update.setCandidateRevisionId(89L); update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        update.setImpactSummary("{\"affectedTestPlanIds\":[2,5]}");
        SourceRevision revision = new SourceRevision(); revision.setStoragePath(snapshot.toString());
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(revisions.findById(89L)).thenReturn(Optional.of(revision));
        when(updates.findByIdAndProjectId(79L, 10L)).thenReturn(Optional.of(update));
        when(updates.save(any())).thenAnswer(call -> call.getArgument(0));
        BusinessRule rule = new BusinessRule(); rule.setId(1L); rule.setProjectId(10L); rule.setDescription("user rule");
        rule.setStatus(ReviewStatus.APPROVED); rule.setIsModified(true);
        TestPlan plan = new TestPlan(); plan.setId(2L); plan.setProjectId(10L); plan.setBusinessRuleId(1L);
        plan.setTitle("user plan"); plan.setStatus(ReviewStatus.APPROVED); plan.setIsModified(true);
        TestPlan rejectedPlan = new TestPlan(); rejectedPlan.setId(5L); rejectedPlan.setProjectId(10L); rejectedPlan.setIsModified(true);
        TestCase testCase = new TestCase(); testCase.setId(3L); testCase.setTestPlanId(2L); testCase.setDescription("user case");
        testCase.setStatus(ReviewStatus.APPROVED); testCase.setIsModified(true);
        UnitTest unit = new UnitTest(); unit.setId(4L); unit.setTestCaseId(3L); unit.setSourceCode("class UserTest {}");
        when(rules.findById(1L)).thenReturn(Optional.of(rule));
        when(plans.findById(2L)).thenReturn(Optional.of(plan)); when(plans.findById(5L)).thenReturn(Optional.of(rejectedPlan));
        when(cases.findById(3L)).thenReturn(Optional.of(testCase)); when(tests.findById(4L)).thenReturn(Optional.of(unit));
        var keptRule = item(SourceUpdateTargetType.BUSINESS_RULE, 1L, "{\"method_id\":10,\"description\":\"AI rule\"}");
        var keptPlan = item(SourceUpdateTargetType.TEST_PLAN, 2L, "{\"rule_id\":1,\"title\":\"AI plan\",\"covered_rule_ids\":[1]}");
        var keptCase = item(SourceUpdateTargetType.TEST_CASE, 3L, "{\"plan_id\":2,\"description\":\"AI case\",\"priority\":\"HIGH\"}");
        var keptUnit = item(SourceUpdateTargetType.UNIT_TEST, 4L,
                "{\"case_id\":3,\"test_class_name\":\"AI\",\"source_code\":\"class AiTest {}\"}");
        List<SourceUpdateItem> proposals = List.of(keptRule, keptPlan, keptCase, keptUnit);
        proposals.forEach(i -> i.setAction(SourceUpdateAction.KEEP));
        var rejected = item(SourceUpdateTargetType.TEST_PLAN, 5L, null); rejected.setAction(SourceUpdateAction.UPDATE);
        rejected.setReviewStatus(SourceUpdateReviewStatus.REJECTED);
        var all = new java.util.ArrayList<>(proposals); all.add(rejected);
        when(items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(79L)).thenReturn(all);
        var json = new ObjectMapper();
        String before = json.writeValueAsString(List.of(rule, plan, testCase, unit, rejectedPlan));
        var service = new SourceUpdateService(projects, revisions, updates, items,
                mock(SourceRevisionStorageService.class), new SourceUpdateMapper(), mock(SourceDiffService.class),
                mock(ImpactAnalysisService.class), rules, plans, cases, tests, classes, methods, json, covered);

        service.applyUpdate(10L, 79L, user);

        assertThat(json.writeValueAsString(List.of(rule, plan, testCase, unit, rejectedPlan))).isEqualTo(before);
        verify(rules, never()).save(any()); verify(plans, never()).save(any());
        verify(cases, never()).save(any()); verify(tests, never()).save(any());
        verifyNoInteractions(covered);
    }
    @Test
    void refusesFailedCandidateBeforeChangingSourcePointersOrArtifacts() {
        var projects = mock(ProjectRepository.class);
        var revisions = mock(SourceRevisionRepository.class);
        var updates = mock(SourceUpdateRepository.class);
        var items = mock(SourceUpdateItemRepository.class);
        var rules = mock(BusinessRuleRepository.class);
        var plans = mock(TestPlanRepository.class);
        var cases = mock(TestCaseRepository.class);
        var tests = mock(UnitTestRepository.class);
        Project project = new Project(); project.setId(10L); project.setOwnerUserId(1L);
        project.setStoragePath("original"); project.setActiveSourceRevisionId(88L);
        AuthUser user = new AuthUser(); user.setId(1L); user.setRole(UserRole.USER);
        SourceUpdate update = new SourceUpdate(); update.setId(79L); update.setProjectId(10L);
        update.setCandidateRevisionId(89L); update.setStatus(SourceUpdateStatus.FAILED);
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(updates.findByIdAndProjectId(79L, 10L)).thenReturn(Optional.of(update));
        var service = new SourceUpdateService(projects, revisions, updates, items,
                mock(SourceRevisionStorageService.class), new SourceUpdateMapper(), mock(SourceDiffService.class),
                mock(ImpactAnalysisService.class), rules, plans, cases, tests, mock(JavaClassRepository.class),
                mock(JavaMethodRepository.class), new ObjectMapper(), mock(TestPlanCoveredRuleRepository.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.applyUpdate(10L, 79L, user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FAILED");

        assertThat(project.getStoragePath()).isEqualTo("original");
        assertThat(project.getActiveSourceRevisionId()).isEqualTo(88L);
        verify(projects, never()).save(any());
        verifyNoInteractions(revisions, items, rules, plans, cases, tests);
    }
    @Test
    void applyPreservesGeneratedIdsBranchLinksAndSourceRanges(@TempDir Path snapshot) throws Exception {
        var projects = mock(ProjectRepository.class);
        var revisions = mock(SourceRevisionRepository.class);
        var updates = mock(SourceUpdateRepository.class);
        var items = mock(SourceUpdateItemRepository.class);
        var rules = mock(BusinessRuleRepository.class);
        var plans = mock(TestPlanRepository.class);
        var covered = mock(TestPlanCoveredRuleRepository.class);
        var cases = mock(TestCaseRepository.class);
        var tests = mock(UnitTestRepository.class);
        var classes = mock(JavaClassRepository.class);
        var methods = mock(JavaMethodRepository.class);
        Project project = new Project(); project.setId(10L); project.setOwnerUserId(1L);
        AuthUser user = new AuthUser(); user.setId(1L); user.setRole(UserRole.USER);
        SourceUpdate update = new SourceUpdate(); update.setId(79L); update.setProjectId(10L);
        update.setCandidateRevisionId(89L); update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        SourceRevision revision = new SourceRevision(); revision.setStoragePath(snapshot.toString());
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(revisions.findById(89L)).thenReturn(Optional.of(revision));
        when(updates.findByIdAndProjectId(79L, 10L)).thenReturn(Optional.of(update));
        when(updates.save(any())).thenAnswer(call -> call.getArgument(0));
        String relativePath = "src/main/java/demo/EmailService.java";
        Path file = snapshot.resolve(relativePath); Files.createDirectories(file.getParent());
        Files.writeString(file, "package demo;\npublic class EmailService {\n"
                + " public void send(String address) {\n  if (address == null) throw new IllegalArgumentException();\n }\n}\n");
        JavaClass owner = new JavaClass(); owner.setId(20L); owner.setProjectId(10L);
        owner.setClassName("EmailService"); owner.setPackageName("demo"); owner.setQualifiedName("demo.EmailService");
        owner.setClassType(ClassType.SERVICE); owner.setFilePath(relativePath); owner.setSourceCode("class EmailService {}");
        JavaMethod method = new JavaMethod(); method.setId(50L); method.setClassId(20L); method.setMethodName("send");
        method.setParameters(List.of(new MethodParam("address", "String"))); method.setLineStart(1); method.setLineEnd(10);
        method.setSourceCode("public void send(String address) { if (address == null) throw new IllegalArgumentException(); }");
        when(classes.findByProjectId(10L)).thenReturn(List.of(owner));
        when(methods.findByClassId(20L)).thenReturn(List.of(method));
        BusinessRule first = new BusinessRule(); first.setId(101L); first.setMethodId(50L); first.setProjectId(10L);
        BusinessRule second = new BusinessRule(); second.setId(102L); second.setMethodId(50L); second.setProjectId(10L);
        when(rules.findById(101L)).thenReturn(Optional.of(first));
        when(rules.findById(102L)).thenReturn(Optional.of(second));
        when(rules.findByProjectId(10L)).thenReturn(List.of(first, second));
        when(rules.save(any())).thenAnswer(call -> call.getArgument(0));
        TestPlan plan = new TestPlan(); plan.setId(201L); plan.setProjectId(10L); plan.setBusinessRuleId(101L);
        when(plans.findById(201L)).thenReturn(Optional.of(plan));
        when(plans.save(any())).thenAnswer(call -> call.getArgument(0));
        TestCase testCase = new TestCase(); testCase.setId(301L); testCase.setTestPlanId(201L);
        when(cases.findById(301L)).thenReturn(Optional.of(testCase));
        when(cases.save(any())).thenAnswer(call -> call.getArgument(0));
        UnitTest unit = new UnitTest(); unit.setId(401L); unit.setTestCaseId(301L);
        when(tests.findById(401L)).thenReturn(Optional.of(unit));
        var methodItem = item(SourceUpdateTargetType.METHOD, null, method.getSourceCode());
        methodItem.setTargetKey("demo.EmailService#send(String)");
        List<SourceUpdateItem> generated = List.of(methodItem,
                item(SourceUpdateTargetType.BUSINESS_RULE, 101L, "{\"method_id\":50,\"branch_id\":\"IF-1\",\"description\":\"reject missing address\",\"category\":\"VALIDATION\"}"),
                item(SourceUpdateTargetType.BUSINESS_RULE, 102L, "{\"method_id\":50,\"branch_id\":\"STMT-1\",\"description\":\"send mail\",\"category\":\"SIDE_EFFECT\"}"),
                item(SourceUpdateTargetType.TEST_PLAN, 201L, "{\"method_id\":50,\"rule_id\":101,\"covered_rule_ids\":[101,102],\"title\":\"send\",\"description\":\"send mail\",\"test_type\":\"HAPPY_PATH\"}"),
                item(SourceUpdateTargetType.TEST_CASE, 301L, "{\"plan_id\":201,\"description\":\"send mail\",\"test_type\":\"HAPPY_PATH\",\"priority\":\"HIGH\",\"test_data\":{},\"expected_result\":\"mail sent\"}"),
                item(SourceUpdateTargetType.UNIT_TEST, 401L, "{\"case_id\":301,\"test_class_name\":\"EmailServiceTest\",\"test_method_name\":\"send\",\"package_name\":\"demo\",\"generation_type\":\"NEW_TEST\",\"source_code\":\"package demo; class EmailServiceTest {}\"}"));
        when(items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(79L)).thenReturn(generated);
        var service = new SourceUpdateService(projects, revisions, updates, items,
                mock(SourceRevisionStorageService.class), new SourceUpdateMapper(), mock(SourceDiffService.class),
                mock(ImpactAnalysisService.class), rules, plans, cases, tests, classes, methods, new ObjectMapper(), covered);

        assertThat(service.applyUpdate(10L, 79L, user).status()).isEqualTo(SourceUpdateStatus.APPLIED);

        assertThat(first.getReviewNote()).startsWith("SOURCE_BRANCH:IF-1");
        assertThat(second.getReviewNote()).startsWith("SOURCE_BRANCH:STMT-1");
        assertThat(method.getLineStart()).isEqualTo(3);
        assertThat(method.getLineEnd()).isEqualTo(5);
        assertThat(owner.getSourceCode()).contains("package demo;");
        assertThat(plan.getBusinessRuleId()).isEqualTo(101L);
        assertThat(testCase.getTestPlanId()).isEqualTo(201L);
        assertThat(unit.getTestCaseId()).isEqualTo(301L);
        var coverage = org.mockito.ArgumentCaptor.forClass(TestPlanCoveredRule.class);
        verify(covered, times(2)).save(coverage.capture());
        assertThat(coverage.getAllValues()).extracting(TestPlanCoveredRule::getBusinessRuleId).containsExactlyInAnyOrder(101L, 102L);
        assertThat(coverage.getAllValues()).extracting(TestPlanCoveredRule::getTestPlanId).containsOnly(201L);
        verify(rules, never()).save(argThat(r -> r.getId() == null));
        verify(plans, never()).save(argThat(p -> p.getId() == null));
        verify(cases, never()).save(argThat(c -> c.getId() == null));
        verify(tests, never()).save(argThat(t -> t.getId() == null));
        service.applyUpdate(10L, 79L, user);
        verify(tests, times(1)).save(unit);
    }

    private SourceUpdateItem item(SourceUpdateTargetType type, Long id, String data) {
        SourceUpdateItem item = new SourceUpdateItem(); item.setTargetType(type); item.setTargetId(id);
        item.setAction(SourceUpdateAction.CREATE); item.setAfterData(data); item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
        return item;
    }
}
