package com.greytest.dto;

import java.time.Instant;

public record GenerationProgressLogDto(Instant timestamp, String message) {}
