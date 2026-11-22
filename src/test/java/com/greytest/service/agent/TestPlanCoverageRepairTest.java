package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;

/**
 * Hồi quy jhipster-sample-app (project 1706, batch 3): plan của registerUser kéo thêm rule của
 * removeNonActivatedUser (6707) và createUser (6843) vào covered_rule_ids nên cả batch bị từ chối.
 */
class TestPlanCoverageRepairTest {
    private static final long REGISTER = 108227L, REMOVE_INACTIVE = 108228L, CREATE = 108229L;

    @Test
    void dropsCoveredRulesOwnedByAnotherMethodSoTheBatchPasses() {
        var context = context();
        var response = new TestPlanResponseDto(List.of(
                plan(REGISTER, 6703L, List.of(6703L, 6803L, 6707L, 6843L)),
                plan(REMOVE_INACTIVE, 6707L, List.of(6707L)),
                plan(CREATE, 6712L, List.of(6712L, 6843L))));
        assertThat(GenerationValidationGate.validateReferences(context, response)).isPresent();

        var repaired = TestPlanCoverageRepair.repair(context, response);

        assertThat(repaired.plans().get(0).coveredRuleIds()).containsExactly(6703L, 6803L);
        assertThat(GenerationValidationGate.validateReferences(context, repaired)).isEmpty();
    }

    @Test
    void stillReportsARuleLeftWithoutAnyPlanAfterRepair() {
        var context = context();
        // 6707 chỉ xuất hiện như rule phụ trong plan của method khác: bỏ ra thì phải báo thiếu, không âm thầm mất.
        var response = new TestPlanResponseDto(List.of(
                plan(REGISTER, 6703L, List.of(6703L, 6803L, 6707L)),
                plan(CREATE, 6712L, List.of(6712L, 6843L))));

        var repaired = TestPlanCoverageRepair.repair(context, response);

        assertThat(GenerationValidationGate.validateReferences(context, repaired))
                .hasValueSatisfying(problem -> assertThat(problem).contains("Missing test plans", "6707"));
    }

    @Test
    void keepsInventedRuleIdsSoTheGateStillRejectsThem() {
        var context = context();
        var response = new TestPlanResponseDto(List.of(
                plan(REGISTER, 6703L, List.of(6703L, 6803L, 9999L)),
                plan(REMOVE_INACTIVE, 6707L, List.of(6707L)),
                plan(CREATE, 6712L, List.of(6712L, 6843L))));

        var repaired = TestPlanCoverageRepair.repair(context, response);

        assertThat(repaired.plans().get(0).coveredRuleIds()).contains(9999L);
        assertThat(GenerationValidationGate.validateReferences(context, repaired))
                .hasValueSatisfying(problem -> assertThat(problem).contains("rule_id=9999"));
    }

    private TestPlanContextDto context() {
        return new TestPlanContextDto(null, null, List.of(), List.of(
                rule(6703L, REGISTER), rule(6803L, REGISTER), rule(6707L, REMOVE_INACTIVE),
                rule(6712L, CREATE), rule(6843L, CREATE)), List.of());
    }

    @Test
    void identifiesOnlyUncoveredRulesAfterRejectingWrongMethodLinks() {
        var response = new TestPlanResponseDto(List.of(
                plan(REGISTER, 6703L, List.of(6703L, 6803L)),
                plan(CREATE, 6707L, List.of(6707L)),
                plan(CREATE, 6712L, List.of(6712L, 6843L))));
        var coverage = TestPlanCoverageRepair.coverage(context(), response);
        assertThat(coverage.valid()).hasSize(2);
        assertThat(coverage.missingRuleIds()).containsExactly(6707L);
        var narrowed = TestPlanCoverageRepair.narrow(context(), coverage.missingRuleIds());
        assertThat(narrowed.approvedBusinessRules()).extracting(BusinessRuleContextDto::methodId)
                .containsExactly(REMOVE_INACTIVE);
    }

    private BusinessRuleContextDto rule(long id, long methodId) {
        return new BusinessRuleContextDto(id, methodId, "BR-" + id, "rule " + id, null, "AI_GENERATED", "APPROVED", false, null);
    }

    private GeneratedTestPlanDto plan(long methodId, long ruleId, List<Long> covered) {
        return new GeneratedTestPlanDto(methodId, ruleId, covered, "Plan " + ruleId, "Mô tả " + ruleId, "HAPPY_PATH");
    }
}
