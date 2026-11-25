package com.greytest.dto;

public record GithubRepoDto(
        Long id,
        String name,
        String fullName,
        String htmlUrl,
        String cloneUrl,
        String defaultBranch,
        boolean isPrivate,
        String description) {
}
