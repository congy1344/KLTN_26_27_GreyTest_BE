package com.greytest.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateQuotaRequest(
        @Min(value = 0, message = "Quota không được âm")
        @Max(value = 100000, message = "Quota vượt giới hạn cho phép")
        Integer quotaLimit) {}

