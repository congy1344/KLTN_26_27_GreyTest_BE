package com.greytest.mapper;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Component;

import com.greytest.dto.SourceRevisionDto;
import com.greytest.dto.SourceUpdateDto;
import com.greytest.dto.SourceUpdateItemDto;
import com.greytest.entity.SourceRevision;
import com.greytest.entity.SourceUpdate;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.dto.GenerationSummaryDto;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.service.generation.GenerationCheckpointStore;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class SourceUpdateMapper {

    private final GenerationCheckpointStore checkpoints;
    private final ObjectMapper objectMapper;

    public SourceUpdateMapper() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public SourceUpdateMapper(GenerationCheckpointStore checkpoints, ObjectMapper objectMapper) {
        this.checkpoints = checkpoints;
        this.objectMapper = objectMapper;
    }

    public SourceRevisionDto toRevisionDto(SourceRevision revision) {
        if (revision == null) {
            return null;
        }
        return new SourceRevisionDto(
                revision.getId(),
                revision.getProjectId(),
                revision.getSourceType(),
                revision.getSourceUrl(),
                revision.getBranch(),
                revision.getCommitSha(),
                revision.getStoragePath(),
                revision.getContentHash(),
                revision.getLogicalRoot(),
                revision.getCreatedAt());
    }

    public SourceUpdateItemDto toItemDto(SourceUpdateItem item) {
        if (item == null) {
            return null;
        }
        return new SourceUpdateItemDto(
                item.getId(),
                item.getSourceUpdateId(),
                item.getTargetType(),
                item.getTargetId(),
                item.getTargetKey(),
                item.getAction(),
                item.getReason(),
                item.getBeforeData(),
                item.getAfterData(),
                item.getReviewStatus(),
                item.getCreatedAt(),
                item.getUpdatedAt(), item.getVersion(), item.getUserRevision());
    }

    public SourceUpdateDto toUpdateDto(SourceUpdate update, List<SourceUpdateItem> items) {
        if (update == null) {
            return null;
        }
        List<SourceUpdateItemDto> itemDtos = items != null
                ? items.stream().map(this::toItemDto).toList()
                : List.of();

        return new SourceUpdateDto(
                update.getId(),
                update.getProjectId(),
                update.getBaseRevisionId(),
                update.getCandidateRevisionId(),
                update.getStatus(),
                update.getTotalChangedMethods(),
                update.getImpactSummary(),
                itemDtos,
                update.getCreatedAt(),
                update.getUpdatedAt(), update.getVersion(), generationSummary(update, items != null ? items : List.of()));
    }

    private GenerationSummaryDto generationSummary(SourceUpdate update, List<SourceUpdateItem> items) {
        if (checkpoints == null || update.getId() == null) return null;
        var saved = checkpoints.list(update.getId());
        Map<String, GenerationSummaryDto.Stage> stages = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> currentTargets = new LinkedHashMap<>();
        List<GenerationSummaryDto.Conflict> conflicts = new ArrayList<>();
        for (String stage : List.of("BUSINESS_RULE", "TEST_PLAN", "TEST_CASE", "UNIT_TEST")) {
            var receipt = saved.stream().filter(c -> stage.equals(c.stage()) && "__stage__".equals(c.targetKey())).findFirst();
            java.util.Set<String> targets = receipt.filter(c -> "SUCCEEDED".equals(c.state())).map(this::receiptTargets).orElse(null);
            if (targets != null) currentTargets.put(stage, targets);
            var units = saved.stream().filter(c -> stage.equals(c.stage()) && !"__stage__".equals(c.targetKey()))
                    .filter(c -> targets == null || targets.contains(c.targetKey())).toList();
            long blocked = units.stream().filter(c -> !"SUCCEEDED".equals(c.state())).count();
            boolean completed = targets != null && units.size() == targets.size() && blocked == 0
                    && units.stream().allMatch(c -> hasCurrentOutputs(c, items));
            stages.put(stage, new GenerationSummaryDto.Stage(receipt.map(GenerationCheckpointStore.Checkpoint::state).orElse("MISSING"),
                    completed, units.stream().filter(c -> "SUCCEEDED".equals(c.state())).count(), blocked));
        }
        for (var checkpoint : saved) {
            if (!"CONFLICT".equals(checkpoint.state())) continue;
            if (currentTargets.containsKey(checkpoint.stage())
                    && !currentTargets.get(checkpoint.stage()).contains(checkpoint.targetKey())) continue;
            try {
                var proposal = objectMapper.readTree(checkpoint.outputJson());
                conflicts.add(new GenerationSummaryDto.Conflict(checkpoint.stage(), checkpoint.targetKey(),
                        proposal.hasNonNull("itemId") ? proposal.path("itemId").asLong() : null,
                        proposal.path("proposedAfterData").asText(null), proposal.path("proposedAction").asText(null)));
            } catch (Exception invalid) {
                conflicts.add(new GenerationSummaryDto.Conflict(checkpoint.stage(), checkpoint.targetKey(), null, null, null));
            }
        }
        boolean ready = stages.values().stream().allMatch(GenerationSummaryDto.Stage::completed)
                && conflicts.isEmpty() && items.stream().noneMatch(i -> i.getReviewStatus() == SourceUpdateReviewStatus.PENDING);
        String metrics = checkpoints.metricsJson(update.getId()).orElse(null);
        boolean running = checkpoints.isRunning(update.getId());
        return new GenerationSummaryDto(stages, conflicts, ready && !running, metrics, running);
    }

    private java.util.Set<String> receiptTargets(GenerationCheckpointStore.Checkpoint checkpoint) {
        try {
            var targets = objectMapper.readTree(checkpoint.outputJson()).path("targets");
            if (!targets.isArray()) return null;
            java.util.Set<String> keys = new java.util.HashSet<>();
            targets.forEach(target -> keys.add(target.asText()));
            return keys;
        } catch (Exception invalid) { return null; }
    }

    private boolean hasCurrentOutputs(GenerationCheckpointStore.Checkpoint checkpoint, List<SourceUpdateItem> items) {
        try {
            var envelope = objectMapper.readTree(checkpoint.outputJson());
            var ids = envelope.path("itemIds");
            if (!ids.isArray()) return false;
            if (ids.isEmpty()) {
                // UT giữ lại không tạo proposal mới; chỉ nhận receipt đã qua validation và dependency gate.
                var units = envelope.path("response").path("unit_tests");
                return "UNIT_TEST".equals(checkpoint.stage())
                        && "retained-verified-digest".equals(checkpoint.outputDigest())
                        && envelope.path("outputReusable").asBoolean(false)
                        && !envelope.path("outputFingerprint").asText("").isBlank()
                        && units.isArray() && units.size() == 1
                        && checkpoint.targetKey().equals(units.get(0).path("case_id").asText())
                        && !units.get(0).path("source_code").asText("").isBlank();
            }
            for (var id : ids) {
                if (items.stream().noneMatch(i -> java.util.Objects.equals(i.getId(), id.asLong()) && i.getAfterData() != null)) return false;
            }
            return true;
        } catch (Exception invalid) { return false; }
    }
}
