package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.UnitTest;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;

@ExtendWith(MockitoExtension.class)
class ArtifactCodeRenumberServiceTest {
    @Mock BusinessRuleRepository rules;
    @Mock TestPlanRepository plans;
    @Mock TestPlanCoveredRuleRepository coveredRules;
    @Mock TestCaseRepository cases;
    @Mock UnitTestRepository unitTests;
    @Mock JavaClassRepository classes;
    @Mock JavaMethodRepository methods;
    @InjectMocks ArtifactCodeRenumberService service;

    @BeforeEach void sourceLayout() {
        JavaClass second = new JavaClass(); second.setId(1L); second.setFilePath("src/b/B.java"); second.setQualifiedName("b.B");
        JavaClass first = new JavaClass(); first.setId(2L); first.setFilePath("src/a/A.java"); first.setQualifiedName("a.A");
        lenient().when(classes.findByProjectId(1L)).thenReturn(List.of(second, first));
        lenient().when(methods.findByClassIdIn(anyList())).thenReturn(List.of(method(10L, 1L, 5), method(20L, 2L, 44), method(21L, 2L, 71)));
    }

    private JavaMethod method(Long id, Long classId, int line) {
        JavaMethod method = new JavaMethod(); method.setId(id); method.setClassId(classId); method.setLineStart(line);
        return method;
    }

    private BusinessRule rule(Long id, Long methodId, String code) {
        BusinessRule rule = new BusinessRule(); rule.setId(id); rule.setMethodId(methodId); rule.setRuleCode(code);
        return rule;
    }

    private TestPlan plan(Long id, Long ruleId, String code) {
        TestPlan plan = new TestPlan(); plan.setId(id); plan.setBusinessRuleId(ruleId); plan.setPlanCode(code);
        return plan;
    }

    private TestCase testCase(Long id, Long planId, String code) {
        TestCase testCase = new TestCase(); testCase.setId(id); testCase.setTestPlanId(planId); testCase.setCaseCode(code);
        return testCase;
    }

    @Test void rulesFollowFileThenLineInsteadOfWhichBatchFinishedFirst() {
        var inB = rule(1L, 10L, "BR-001");
        var line71 = rule(2L, 21L, "BR-002");
        var line44 = rule(3L, 20L, "BR-003");
        when(plans.findByProjectId(1L)).thenReturn(List.of());
        when(rules.findByProjectId(1L)).thenReturn(List.of(inB, line71, line44));

        service.renumberRules(1L);

        assertThat(line44.getRuleCode()).isEqualTo("BR-001");
        assertThat(line71.getRuleCode()).isEqualTo("BR-002");
        assertThat(inB.getRuleCode()).isEqualTo("BR-003");
        // Hai lượt để không đụng unique index trong lúc hoán đổi mã.
        verify(rules, times(2)).saveAllAndFlush(anyList());
    }

    @Test void rulesAlreadyUsedByAPlanKeepTheirCodeAndOthersShareTheRemainingCodes() {
        var used = rule(1L, 21L, "BR-001");
        var covered = rule(2L, 21L, "BR-002");
        var later = rule(3L, 21L, "BR-003");
        var earlier = rule(4L, 20L, "BR-004");
        var link = new TestPlanCoveredRule(); link.setBusinessRuleId(2L);
        when(plans.findByProjectId(1L)).thenReturn(List.of(plan(50L, 1L, "TP-001")));
        when(coveredRules.findByTestPlanIdIn(List.of(50L))).thenReturn(List.of(link));
        when(rules.findByProjectId(1L)).thenReturn(List.of(used, covered, later, earlier));

        service.renumberRules(1L);

        assertThat(used.getRuleCode()).isEqualTo("BR-001");
        assertThat(covered.getRuleCode()).isEqualTo("BR-002");
        assertThat(earlier.getRuleCode()).isEqualTo("BR-003");
        assertThat(later.getRuleCode()).isEqualTo("BR-004");
    }

    @Test void alreadyOrderedCodesWriteNothing() {
        when(plans.findByProjectId(1L)).thenReturn(List.of());
        when(rules.findByProjectId(1L)).thenReturn(List.of(rule(1L, 20L, "BR-001"), rule(2L, 21L, "BR-002")));

        service.renumberRules(1L);

        verify(rules, never()).saveAllAndFlush(anyList());
    }

    @Test void plansFollowTheirBusinessRule() {
        var far = plan(50L, 2L, "TP-001");
        var near = plan(51L, 1L, "TP-002");
        when(plans.findByProjectId(1L)).thenReturn(List.of(far, near));
        when(cases.findByTestPlanIdIn(List.of(50L, 51L))).thenReturn(List.of());
        when(rules.findByProjectId(1L)).thenReturn(List.of(rule(1L, 20L, "BR-001"), rule(2L, 21L, "BR-166")));

        service.renumberPlans(1L);

        assertThat(near.getPlanCode()).isEqualTo("TP-001");
        assertThat(far.getPlanCode()).isEqualTo("TP-002");
    }

    @Test void plansThatAlreadyHaveTestCasesKeepTheirCode() {
        var far = plan(50L, 2L, "TP-001");
        var near = plan(51L, 1L, "TP-002");
        when(plans.findByProjectId(1L)).thenReturn(List.of(far, near));
        when(cases.findByTestPlanIdIn(List.of(50L, 51L))).thenReturn(List.of(testCase(70L, 50L, "TC-001")));
        when(rules.findByProjectId(1L)).thenReturn(List.of(rule(1L, 20L, "BR-001"), rule(2L, 21L, "BR-166")));

        service.renumberPlans(1L);

        assertThat(far.getPlanCode()).isEqualTo("TP-001");
        assertThat(near.getPlanCode()).isEqualTo("TP-002");
    }

    @Test void casesFollowTheirPlanAndKeepCodesOnceAUnitTestExists() {
        var late = testCase(70L, 51L, "TC-001");
        var early = testCase(71L, 50L, "TC-002");
        var tested = testCase(72L, 52L, "TC-003");
        var unitTest = new UnitTest(); unitTest.setTestCaseId(72L);
        when(cases.findByProjectId(1L)).thenReturn(List.of(late, early, tested));
        when(unitTests.findByTestCaseIdIn(List.of(70L, 71L, 72L))).thenReturn(List.of(unitTest));
        when(plans.findByProjectId(1L)).thenReturn(List.of(plan(50L, 1L, "TP-001"), plan(51L, 2L, "TP-002"), plan(52L, 3L, "TP-003")));

        service.renumberCases(1L);

        assertThat(early.getCaseCode()).isEqualTo("TC-001");
        assertThat(late.getCaseCode()).isEqualTo("TC-002");
        assertThat(tested.getCaseCode()).isEqualTo("TC-003");
    }
}
