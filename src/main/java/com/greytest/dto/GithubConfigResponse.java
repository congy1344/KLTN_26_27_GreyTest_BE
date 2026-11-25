package com.greytest.dto;

public record GithubConfigResponse(
        boolean enabled,
        String clientId,
        String redirectUri) {
}
