package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;

public record GithubBranchUpdateRequest(
        @NotBlank(message = "Branch không được để trống")
        String branch) {
}
