package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;

public record GithubCloneRequest(
        @NotBlank(message = "URL GitHub không được để trống")
        String url) {
}
