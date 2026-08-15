package com.greytest.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.GenerationProgressDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.service.AuthService;
import com.greytest.service.GenerationProgressService;
import com.greytest.service.ProjectService;

/** Cung cấp snapshot tiến độ cho giao diện trong lúc tác vụ AI đang chạy. */
@RestController
public class GenerationProgressController {

    private final GenerationProgressService progressService;
    private final AuthService authService;
    private final ProjectService projectService;

    public GenerationProgressController(
            GenerationProgressService progressService,
            AuthService authService,
            ProjectService projectService) {
        this.progressService = progressService;
        this.authService = authService;
        this.projectService = projectService;
    }

    @GetMapping("/api/projects/{projectId}/generation-progress/{stage}")
    public GenerationProgressDto get(
            @PathVariable Long projectId,
            @PathVariable GenerationProgressStage stage,
            @RequestHeader("Authorization") String authorization) {
        projectService.requireAccess(projectId, authService.currentUser(authorization));
        return progressService.get(projectId, stage);
    }
}
