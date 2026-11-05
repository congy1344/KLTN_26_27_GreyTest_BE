package com.greytest.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

/**
 * Đánh số lại BR, TP, TC theo thứ tự source khi một bước sinh xong, vì các batch chạy song song nên
 * mã ban đầu đi theo batch nào xong trước. Artifact đã có bước sau tham chiếu thì giữ nguyên mã,
 * vì mã đã nằm trong traceSource và comment của Test Case, Unit Test.
 */
@Service
public class ArtifactCodeRenumberService {
    private final BusinessRuleRepository rules;
    private final TestPlanRepository plans;
    private final TestPlanCoveredRuleRepository coveredRules;
    private final TestCaseRepository cases;
    private final UnitTestRepository unitTests;
    private final JavaClassRepository classes;
    private final JavaMethodRepository methods;

    public ArtifactCodeRenumberService(BusinessRuleRepository rules, TestPlanRepository plans,
            TestPlanCoveredRuleRepository coveredRules, TestCaseRepository cases, UnitTestRepository unitTests,
            JavaClassRepository classes, JavaMethodRepository methods) {
        this.rules = rules;
        this.plans = plans;
        this.coveredRules = coveredRules;
        this.cases = cases;
        this.unitTests = unitTests;
        this.classes = classes;
        this.methods = methods;
    }

    /** Thứ tự như lúc AI đọc: theo file, rồi dòng của method; các rule trong một method giữ thứ tự đã lưu. */
    @Transactional
    public void renumberRules(Long projectId) {
        List<TestPlan> projectPlans = plans.findByProjectId(projectId);
        Set<Long> referenced = new HashSet<>();
        projectPlans.forEach(plan -> referenced.add(plan.getBusinessRuleId()));
        if (!projectPlans.isEmpty()) {
            coveredRules.findByTestPlanIdIn(projectPlans.stream().map(TestPlan::getId).toList()).stream()
                    .map(TestPlanCoveredRule::getBusinessRuleId).forEach(referenced::add);
        }
        Map<Long, Integer> position = methodPositions(projectId);
        List<BusinessRule> free = rules.findByProjectId(projectId).stream()
                .filter(rule -> !referenced.contains(rule.getId()) && rule.getRuleCode() != null)
                .sorted(Comparator.comparingInt((BusinessRule rule) -> position.getOrDefault(rule.getMethodId(), Integer.MAX_VALUE))
                        .thenComparing(BusinessRule::getId))
                .toList();
        reassign(free, BusinessRule::getRuleCode, BusinessRule::setRuleCode, BusinessRule::getId, rules::saveAllAndFlush);
    }

    /** Plan đi theo mã của Business Rule chính để TP-001 nằm cạnh BR đầu tiên. */
    @Transactional
    public void renumberPlans(Long projectId) {
        List<TestPlan> projectPlans = plans.findByProjectId(projectId);
        if (projectPlans.isEmpty()) return;
        Set<Long> referenced = new HashSet<>();
        cases.findByTestPlanIdIn(projectPlans.stream().map(TestPlan::getId).toList())
                .forEach(testCase -> referenced.add(testCase.getTestPlanId()));
        Map<Long, Integer> ruleNumber = new HashMap<>();
        rules.findByProjectId(projectId).forEach(rule -> ruleNumber.put(rule.getId(), number(rule.getRuleCode())));
        List<TestPlan> free = projectPlans.stream()
                .filter(plan -> !referenced.contains(plan.getId()) && plan.getPlanCode() != null)
                .sorted(Comparator.comparingInt((TestPlan plan) -> ruleNumber.getOrDefault(plan.getBusinessRuleId(), Integer.MAX_VALUE))
                        .thenComparing(TestPlan::getId))
                .toList();
        reassign(free, TestPlan::getPlanCode, TestPlan::setPlanCode, TestPlan::getId, plans::saveAllAndFlush);
    }

    /** Case đi theo mã của Test Plan; các case trong một plan giữ thứ tự đã lưu. */
    @Transactional
    public void renumberCases(Long projectId) {
        List<TestCase> projectCases = cases.findByProjectId(projectId);
        if (projectCases.isEmpty()) return;
        Set<Long> referenced = new HashSet<>();
        unitTests.findByTestCaseIdIn(projectCases.stream().map(TestCase::getId).toList())
                .forEach((UnitTest test) -> referenced.add(test.getTestCaseId()));
        Map<Long, Integer> planNumber = new HashMap<>();
        plans.findByProjectId(projectId).forEach(plan -> planNumber.put(plan.getId(), number(plan.getPlanCode())));
        List<TestCase> free = projectCases.stream()
                .filter(testCase -> !referenced.contains(testCase.getId()) && testCase.getCaseCode() != null)
                .sorted(Comparator.comparingInt((TestCase testCase) -> planNumber.getOrDefault(testCase.getTestPlanId(), Integer.MAX_VALUE))
                        .thenComparing(TestCase::getId))
                .toList();
        reassign(free, TestCase::getCaseCode, TestCase::setCaseCode, TestCase::getId, cases::saveAllAndFlush);
    }

    private Map<Long, Integer> methodPositions(Long projectId) {
        List<JavaClass> ordered = classes.findByProjectId(projectId).stream()
                .sorted(Comparator.comparing(JavaClass::getFilePath, Comparator.nullsLast(String::compareTo))
                        .thenComparing(JavaClass::getQualifiedName, Comparator.nullsLast(String::compareTo)))
                .toList();
        Map<Long, Integer> position = new HashMap<>();
        if (ordered.isEmpty()) return position;
        Map<Long, List<JavaMethod>> byClass = new HashMap<>();
        methods.findByClassIdIn(ordered.stream().map(JavaClass::getId).toList())
                .forEach(method -> byClass.computeIfAbsent(method.getClassId(), ignored -> new ArrayList<>()).add(method));
        int next = 0;
        for (JavaClass type : ordered) {
            List<JavaMethod> inClass = byClass.getOrDefault(type.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(JavaMethod::getLineStart, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(JavaMethod::getId))
                    .toList();
            for (JavaMethod method : inClass) position.put(method.getId(), next++);
        }
        return position;
    }

    /**
     * Chia lại đúng các mã đang có trong nhóm tự do, nên không đụng mã của artifact đã bị tham chiếu.
     * Đổi hai lượt qua mã tạm vì business_rule và test_plan có unique index không deferrable trên mã.
     */
    private <T> void reassign(List<T> ordered, Function<T, String> code, BiConsumer<T, String> setCode,
            Function<T, Long> id, Consumer<List<T>> saveAndFlush) {
        List<String> pool = ordered.stream().map(code).sorted(Comparator.comparingInt(ArtifactCodeRenumberService::number)).toList();
        List<T> changed = new ArrayList<>();
        List<String> finalCodes = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            if (pool.get(index).equals(code.apply(ordered.get(index)))) continue;
            changed.add(ordered.get(index));
            finalCodes.add(pool.get(index));
        }
        if (changed.isEmpty()) return;
        changed.forEach(item -> setCode.accept(item, "~" + id.apply(item)));
        saveAndFlush.accept(changed);
        for (int index = 0; index < changed.size(); index++) setCode.accept(changed.get(index), finalCodes.get(index));
        saveAndFlush.accept(changed);
    }

    private static int number(String code) {
        if (code == null) return Integer.MAX_VALUE;
        String digits = code.replaceAll("\\D", "");
        try {
            return digits.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(digits);
        } catch (NumberFormatException tooLong) {
            return Integer.MAX_VALUE;
        }
    }
}
