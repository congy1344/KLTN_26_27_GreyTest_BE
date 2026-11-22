package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;
import com.greytest.service.GenerationJobContext;

/**
 * Chuẩn hóa covered_rule_ids của Test Plan trước khi kiểm tra.
 * AI hay kéo rule của method được gọi (helper, method cùng hành vi) vào plan của method gọi; rule đó
 * thuộc method khác và phải có plan riêng, nên bỏ khỏi danh sách phụ thay vì từ chối cả batch.
 * Không đụng tới rule_id chính, mô tả hay ID không có trong context: gate vẫn báo lỗi các trường hợp đó,
 * và rule bị bỏ mà không còn plan nào vẫn bị báo "Missing test plans".
 */
final class TestPlanCoverageRepair {
    record Coverage(List<GeneratedTestPlanDto> valid, java.util.Set<Long> missingRuleIds) { }

    private TestPlanCoverageRepair() {
    }

    /** Chỉ giữ plan hợp lệ; rule chưa có plan đúng phải được AI sinh lại, không tự gán ID. */
    static Coverage coverage(TestPlanContextDto context, TestPlanResponseDto response) {
        List<GeneratedTestPlanDto> valid = new ArrayList<>();
        var missing = context.approvedBusinessRules().stream().map(BusinessRuleContextDto::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (var plan : response.plans()) {
            var ids = new LinkedHashSet<Long>();
            if (plan.ruleId() != null) ids.add(plan.ruleId());
            if (plan.coveredRuleIds() != null) ids.addAll(plan.coveredRuleIds());
            var selected = narrow(context, ids);
            if (GenerationValidationGate.validateReferences(selected, new TestPlanResponseDto(List.of(plan))).isEmpty()) {
                valid.add(plan);
                missing.removeAll(ids);
            }
        }
        return new Coverage(List.copyOf(valid), java.util.Set.copyOf(missing));
    }

    static TestPlanContextDto narrow(TestPlanContextDto context, java.util.Set<Long> ruleIds) {
        return new TestPlanContextDto(context.project(), context.analysis(), context.classes(),
                context.approvedBusinessRules().stream().filter(rule -> ruleIds.contains(rule.id())).toList(),
                context.existingTests(), context.supportingResources(), context.contextWarnings());
    }

    static TestPlanResponseDto repair(TestPlanContextDto context, TestPlanResponseDto response) {
        if (context == null || response == null || response.plans() == null) return response;
        Map<Long, Long> methodByRule = context.approvedBusinessRules().stream()
                .collect(Collectors.toMap(BusinessRuleContextDto::id, BusinessRuleContextDto::methodId, (left, right) -> left));
        List<GeneratedTestPlanDto> repaired = new ArrayList<>();
        for (GeneratedTestPlanDto plan : response.plans()) {
            if (plan.coveredRuleIds() == null || !Objects.equals(methodByRule.get(plan.ruleId()), plan.methodId())) {
                repaired.add(plan);
                continue;
            }
            List<Long> foreign = plan.coveredRuleIds().stream()
                    .filter(id -> id != null && methodByRule.containsKey(id) && !Objects.equals(methodByRule.get(id), plan.methodId()))
                    .toList();
            if (foreign.isEmpty()) {
                repaired.add(plan);
                continue;
            }
            GenerationJobContext.log("Bỏ rule " + foreign + " khỏi Test Plan của method " + plan.methodId()
                    + " vì chúng thuộc method khác và cần Test Plan riêng.");
            var kept = plan.coveredRuleIds().stream().filter(id -> !foreign.contains(id))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            repaired.add(new GeneratedTestPlanDto(plan.methodId(), plan.ruleId(), List.copyOf(kept),
                    plan.title(), plan.description(), plan.testType()));
        }
        return new TestPlanResponseDto(repaired);
    }
}
