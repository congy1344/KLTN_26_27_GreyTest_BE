package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;

public record GithubImportRequest(
        @NotBlank(message = "URL repository không được để trống")
        String cloneUrl,
        String branch) {
}
