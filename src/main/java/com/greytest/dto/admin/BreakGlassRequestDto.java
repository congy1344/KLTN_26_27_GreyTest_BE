package com.greytest.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BreakGlassRequestDto(
        @NotNull(message = "projectId không được để trống")
        Long projectId,

        @NotNull(message = "approverUserId không được để trống")
        Long approverUserId,

        @NotBlank(message = "Lý do Break-Glass không được để trống")
        @Size(min = 10, max = 500, message = "Lý do phải từ 10 đến 500 ký tự")
        String reason,

        @Min(value = 5, message = "Thời gian cấp tối thiểu 5 phút")
        @Max(value = 120, message = "Thời gian cấp tối đa 120 phút")
        Integer durationMinutes
) {
}
