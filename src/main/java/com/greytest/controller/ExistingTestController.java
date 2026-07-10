package com.greytest.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.ExistingTestDto;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.analysis.ExistingTestService;

@RestController
@RequestMapping("/api/projects/{projectId}/existing-tests")
public class ExistingTestController {

    private final ExistingTestService existingTestService;
    private final AuthService authService;
    private final ProjectService projectService;

    public ExistingTestController(
            ExistingTestService existingTestService,
            AuthService authService,
            ProjectService projectService) {
        this.existingTestService = existingTestService;
        this.authService = authService;
        this.projectService = projectService;
    }

    @GetMapping
    public List<ExistingTestDto> list(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        projectService.requireAccess(projectId, authService.currentUser(authorization));
        return existingTestService.list(projectId);
    }
}
