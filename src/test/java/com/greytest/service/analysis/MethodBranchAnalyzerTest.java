package com.greytest.service.analysis;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.greytest.dto.SourceBranchDto;

class MethodBranchAnalyzerTest {

    @Test
    void extractsTrueAndFalseOutcomesForEveryNestedIfInSourceOrder() {
        var branches = MethodBranchAnalyzer.analyze("""
                public String classify(int score, boolean active) {
                    if (score >= 80) {
                        if (active) {
                            return "ready";
                        }
                        return "inactive";
                    } else {
                        return "low";
                    }
                }
                """, 40);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly("IF-1-TRUE", "IF-1-FALSE", "IF-2-TRUE", "IF-2-FALSE");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::condition)
                .containsExactly("score >= 80", "score >= 80", "active", "active");
        assertThat(branches.get(0).lineStart()).isEqualTo(42);
    }

    @Test
    void usesExecutableStatementsInsteadOfConditionOrCommentLines() {
        var branches = MethodBranchAnalyzer.analyze("""
                public void create(boolean valid) {
                    // The decision is documented here.
                    if (valid) {
                        repository.save();
                    } else {
                        throw new IllegalStateException(
                                "invalid");
                    }
                }
                """, 30);

        assertThat(branches.get(0).lineStart()).isEqualTo(33);
        assertThat(branches.get(0).lineEnd()).isEqualTo(33);
        assertThat(branches.get(1).lineStart()).isEqualTo(35);
        assertThat(branches.get(1).lineEnd()).isEqualTo(36);
    }

    @Test
    void parsesLegacyJava8UnderscoreIdentifier() {
        var branches = MethodBranchAnalyzer.analyze("""
                public int _(int _) {
                    if (_ > 0) {
                        return _;
                    }
                    return 0;
                }
                """, 1);

        assertThat(decisionBranches(branches)).hasSize(2);
    }

    @Test
    void exposesTopLevelExecutableStatementsAsSourceAnchors() {
        var branches = MethodBranchAnalyzer.analyze("""
                public Appointment getById(Long id) {
                    Appointment appointment = repository.findById(id)
                            .orElseThrow(() -> new NotFoundException());
                    return enrichAppointmentResponse(appointment);
                }
                """, 100);

        assertThat(branches).extracting(SourceBranchDto::branchId)
                .containsExactly("STMT-1", "STMT-2");
        assertThat(branches.get(0).lineStart()).isEqualTo(101);
        assertThat(branches.get(0).lineEnd()).isEqualTo(102);
        assertThat(branches.get(1).lineStart()).isEqualTo(103);
        assertThat(branches.get(1).lineEnd()).isEqualTo(103);
    }

    @Test
    void extractsEveryOutcomeFromJava21PatternSwitch() {
        var branches = MethodBranchAnalyzer.analyze("""
                public String inspect(Object source) {
                    return switch (source) {
                        case String text -> text;
                        case Integer number -> number.toString();
                        default -> "";
                    };
                }
                """, 1);

        assertThat(branches).extracting(SourceBranchDto::branchId)
                .containsExactly(
                        "SWITCH-1::CASE-1",
                        "SWITCH-1::CASE-2",
                        "SWITCH-1::DEFAULT");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::outcome)
                .containsExactly("String text", "Integer number", "DEFAULT");
    }

    @Test
    void extractsSwitchExpressionAndTernaryDecisionInSourceOrder() {
        var branches = MethodBranchAnalyzer.analyze("""
                public int tax(int amount, String region) {
                    int percent = switch (region) {
                        case "VN" -> 10;
                        case "US", "SG" -> 8;
                        default -> throw new IllegalArgumentException();
                    };
                    return amount == 0 ? 0 : amount * percent / 100;
                }
                """, 20);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly(
                        "SWITCH-1::CASE-1",
                        "SWITCH-1::CASE-2",
                        "SWITCH-1::DEFAULT",
                        "TERNARY-1::TRUE",
                        "TERNARY-1::FALSE");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::condition)
                .containsExactly("region", "region", "region", "amount == 0", "amount == 0");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::outcome)
                .containsExactly("\"VN\"", "\"US\" | \"SG\"", "DEFAULT", "TRUE", "FALSE");
    }

    @Test
    void addsNoMatchOutcomeForSwitchStatementWithoutDefault() {
        var branches = MethodBranchAnalyzer.analyze("""
                public String label(int value) {
                    switch (value) {
                        case 1: return "one";
                        case 2: return "two";
                    }
                    return "other";
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly(
                        "SWITCH-1::CASE-1",
                        "SWITCH-1::CASE-2",
                        "SWITCH-1::NO_MATCH");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::outcome)
                .containsExactly("1", "2", "NO_MATCH");
    }

    @Test
    void treatsJava21CombinedNullDefaultAsDefaultWithoutFakeNoMatch() {
        var branches = MethodBranchAnalyzer.analyze("""
                public String label(String value) {
                    return switch (value) {
                        case "ready" -> "ok";
                        case null, default -> "other";
                    };
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly("SWITCH-1::CASE-1", "SWITCH-1::DEFAULT");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::outcome)
                .containsExactly("\"ready\"", "DEFAULT");
    }

    @Test
    void extractsForForeachWhileAndDoWhileOutcomes() {
        var branches = MethodBranchAnalyzer.analyze("""
                public int loops(java.util.List<Integer> values, int limit) {
                    int total = 0;
                    for (int index = 0; index < limit; index++) total += index;
                    for (Integer value : values) total += value;
                    while (total < limit) total++;
                    do { total--; } while (total > limit);
                    return total;
                }
                """, 1);

        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::branchId)
                .containsExactly(
                        "FOR-1::ENTER", "FOR-1::SKIP",
                        "FOREACH-1::ENTER", "FOREACH-1::SKIP",
                        "WHILE-1::ENTER", "WHILE-1::SKIP",
                        "DO_WHILE-1::REPEAT", "DO_WHILE-1::EXIT");
        assertThat(decisionBranches(branches)).extracting(SourceBranchDto::condition)
                .containsExactly(
                        "index < limit", "index < limit",
                        "values", "values",
                        "total < limit", "total < limit",
                        "total > limit", "total > limit");
    }

    @Test
    void rejectsUnreadableSourceInsteadOfTreatingItAsBranchless() {
        assertThatThrownBy(() -> MethodBranchAnalyzer.analyze(
                "public void broken() { if (", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Khong parse duoc source method");
    }

    private List<SourceBranchDto> decisionBranches(List<SourceBranchDto> branches) {
        return branches.stream().filter(branch -> !"STATEMENT".equals(branch.kind())).toList();
    }
}
