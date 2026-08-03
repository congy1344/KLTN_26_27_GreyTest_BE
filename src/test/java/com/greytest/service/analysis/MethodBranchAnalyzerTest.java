package com.greytest.service.analysis;

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

        assertThat(branches).extracting(SourceBranchDto::branchId)
                .containsExactly("IF-1-TRUE", "IF-1-FALSE", "IF-2-TRUE", "IF-2-FALSE");
        assertThat(branches).extracting(SourceBranchDto::condition)
                .containsExactly("score >= 80", "score >= 80", "active", "active");
        assertThat(branches.get(0).lineStart()).isEqualTo(41);
    }

    @Test
    void rejectsUnreadableSourceInsteadOfTreatingItAsBranchless() {
        assertThatThrownBy(() -> MethodBranchAnalyzer.analyze(
                "public void broken() { if (", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Khong parse duoc source method");
    }
}
