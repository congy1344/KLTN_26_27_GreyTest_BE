package com.greytest.dto;

import java.util.List;
import java.util.Map;

/** Trạng thái generation lấy từ checkpoint server và quyết định review hiện tại. */
public record GenerationSummaryDto(Map<String, Stage> stages, List<Conflict> conflicts,
        boolean readyForApply, String metricsJson, boolean running) {
    public record Stage(String state, boolean completed, long succeeded, long blocked) { }
    public record Conflict(String stage, String targetKey, Long itemId, String proposedAfterData, String proposedAction) { }
}
