package com.greytest.dto;

import com.greytest.entity.enums.ProjectStatus;

public record ProjectServiceDto(String servicePath, String name, ProjectStatus status) {
}
