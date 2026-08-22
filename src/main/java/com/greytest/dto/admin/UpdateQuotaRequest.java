package com.greytest.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateQuotaRequest(
        @NotNull(message = "Quota limit là bắt buộc")
        @Min(value = 0, message = "Quota không được âm")
        @Max(value = 100000, message = "Quota vượt giới hạn cho phép")
        Integer quotaLimit) {}

