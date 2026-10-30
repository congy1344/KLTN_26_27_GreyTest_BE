package com.greytest.mapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.entity.SourceUpdate;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.service.generation.GenerationCheckpointStore;
import com.greytest.service.generation.GenerationCheckpointStore.Checkpoint;

class SourceUpdateMapperTest {
    @Test
    void verifiedRetainedUnitTestCompletesStageWithoutCreatingADraftItem() {
        var result = retainedSummary("UNIT_TEST", "10", "class Test {}", true, "proof", "retained-verified-digest");
        assertThat(result.generationSummary().stages().get("UNIT_TEST").completed()).isTrue();
        assertThat(result.generationSummary().readyForApply()).isTrue();
    }

    @Test
    void missingOrMismatchedRetainedProofDoesNotCompleteStage() {
        assertThat(retainedSummary("UNIT_TEST", "11", "class Test {}", true, "proof", "retained-verified-digest")
                .generationSummary().readyForApply()).isFalse();
        assertThat(retainedSummary("UNIT_TEST", "10", " ", true, "proof", "retained-verified-digest")
                .generationSummary().readyForApply()).isFalse();
        assertThat(retainedSummary("UNIT_TEST", "10", "class Test {}", false, "proof", "retained-verified-digest")
                .generationSummary().readyForApply()).isFalse();
        assertThat(retainedSummary("UNIT_TEST", "10", "class Test {}", true, "", "retained-verified-digest")
                .generationSummary().readyForApply()).isFalse();
        assertThat(retainedSummary("UNIT_TEST", "10", "class Test {}", true, "proof", "arbitrary")
                .generationSummary().readyForApply()).isFalse();
        assertThat(retainedSummary("BUSINESS_RULE", "10", "class Test {}", true, "proof", "retained-verified-digest")
                .generationSummary().readyForApply()).isFalse();
    }

    private com.greytest.dto.SourceUpdateDto retainedSummary(String stage, String caseId, String source, boolean reusable,
            String fingerprint, String digest) {
        var store = mock(GenerationCheckpointStore.class);
        List<Checkpoint> saved = new ArrayList<>();
        for (String current : List.of("BUSINESS_RULE", "TEST_PLAN", "TEST_CASE", "UNIT_TEST"))
            saved.add(checkpoint(current, "__stage__", "SUCCEEDED", current.equals(stage) ? "{\"targets\":[\"10\"]}" : "{\"targets\":[]}"));
        String envelope = new ObjectMapper().createObjectNode().put("outputReusable", reusable).put("outputFingerprint", fingerprint)
                .set("itemIds", new ObjectMapper().createArrayNode()).toString();
        var json = new ObjectMapper();
        try {
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(envelope);
            value.set("response", json.valueToTree(java.util.Map.of("unit_tests", List.of(java.util.Map.of("case_id", Long.valueOf(caseId), "source_code", source)))));
            saved.add(new Checkpoint(stage, "10", "fp", "{}", value.toString(), digest, "SUCCEEDED"));
        } catch (Exception invalid) { throw new AssertionError(invalid); }
        when(store.list(1L)).thenReturn(saved);
        var update = new SourceUpdate(); update.setId(1L);
        return new SourceUpdateMapper(store, json).toUpdateDto(update, List.of());
    }
    @Test
    void oneSuccessfulCaseDoesNotCompleteItsWholeStage() {
        var store = mock(GenerationCheckpointStore.class);
        when(store.list(1L)).thenReturn(List.of(checkpoint("TEST_CASE", "10", "SUCCEEDED", "{}")));
        var update = new SourceUpdate(); update.setId(1L);

        var result = new SourceUpdateMapper(store, new ObjectMapper()).toUpdateDto(update, List.of());

        assertThat(result.generationSummary().stages().get("TEST_CASE").completed()).isFalse();
        assertThat(result.generationSummary().readyForApply()).isFalse();
    }

    @Test
    void allStageReceiptsStillRequireReviewAndNoActiveRun() {
        var store = mock(GenerationCheckpointStore.class);
        List<Checkpoint> checkpoints = new ArrayList<>();
        for (String stage : List.of("BUSINESS_RULE", "TEST_PLAN", "TEST_CASE", "UNIT_TEST")) {
            checkpoints.add(checkpoint(stage, "__stage__", "SUCCEEDED", "{\"targets\":[]}"));
        }
        when(store.list(1L)).thenReturn(checkpoints);
        var update = new SourceUpdate(); update.setId(1L);
        var item = new SourceUpdateItem(); item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
        var mapper = new SourceUpdateMapper(store, new ObjectMapper());
        assertThat(mapper.toUpdateDto(update, List.of(item)).generationSummary().readyForApply()).isFalse();
        item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
        assertThat(mapper.toUpdateDto(update, List.of(item)).generationSummary().readyForApply()).isTrue();
        when(store.isRunning(1L)).thenReturn(true);
        assertThat(mapper.toUpdateDto(update, List.of(item)).generationSummary().readyForApply()).isFalse();
    }

    @Test
    void conflictProposalStaysSeparateFromPreservedUserItem() {
        var store = mock(GenerationCheckpointStore.class);
        when(store.list(1L)).thenReturn(List.of(checkpoint("BUSINESS_RULE", "10", "CONFLICT",
                "{\"itemId\":5,\"proposedAfterData\":\"proposal\",\"proposedAction\":\"UPDATE\"}")));
        var update = new SourceUpdate(); update.setId(1L);
        var item = new SourceUpdateItem(); item.setAfterData("user content"); item.setUserRevision(2L);

        var result = new SourceUpdateMapper(store, new ObjectMapper()).toUpdateDto(update, List.of(item));

        assertThat(result.items().get(0).afterData()).isEqualTo("user content");
        assertThat(result.items().get(0).userRevision()).isEqualTo(2L);
        assertThat(result.generationSummary().conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.itemId()).isEqualTo(5L);
            assertThat(conflict.proposedAfterData()).isEqualTo("proposal");
        });
    }

    @Test
    void obsoleteConflictsOutsideLatestSuccessfulReceiptDoNotBlockCurrentStages() {
        var store = mock(GenerationCheckpointStore.class);
        List<Checkpoint> saved = new ArrayList<>();
        saved.add(checkpoint("BUSINESS_RULE", "__stage__", "SUCCEEDED", "{\"targets\":[\"1\"]}"));
        saved.add(checkpoint("BUSINESS_RULE", "1", "SUCCEEDED", "{\"itemIds\":[5]}"));
        saved.add(checkpoint("BUSINESS_RULE", "2", "CONFLICT", "{\"itemId\":6}"));
        for (String stage : List.of("TEST_PLAN", "TEST_CASE", "UNIT_TEST")) {
            saved.add(checkpoint(stage, "__stage__", "SUCCEEDED", "{\"targets\":[]}"));
        }
        when(store.list(1L)).thenReturn(saved);
        var update = new SourceUpdate(); update.setId(1L);
        var current = new SourceUpdateItem(); current.setId(5L); current.setAfterData("current");
        current.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);

        var result = new SourceUpdateMapper(store, new ObjectMapper()).toUpdateDto(update, List.of(current));

        assertThat(result.generationSummary().readyForApply()).isTrue();
        assertThat(result.generationSummary().conflicts()).isEmpty();
        assertThat(result.generationSummary().stages().get("BUSINESS_RULE").blocked()).isZero();
    }

    private Checkpoint checkpoint(String stage, String key, String state, String output) {
        return new Checkpoint(stage, key, "fp", "{}", output, "digest", state);
    }
}
