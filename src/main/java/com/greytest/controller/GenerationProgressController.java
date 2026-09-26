package com.greytest.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.GenerationProgressDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.service.AuthService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.GenerationProgressService;
import com.greytest.service.ProjectService;

/** Cung cấp snapshot tiến độ cho giao diện trong lúc tác vụ AI đang chạy và hỗ trợ tạm dừng. */
@RestController
public class GenerationProgressController {

    private final GenerationProgressService progressService;
    private final AuthService authService;
    private final ProjectService projectService;
    private final GenerationJobService jobService;

    @Autowired
    public GenerationProgressController(
            GenerationProgressService progressService,
            AuthService authService,
            ProjectService projectService,
            GenerationJobService jobService) {
        this.progressService = progressService;
        this.authService = authService;
        this.projectService = projectService;
        this.jobService = jobService;
    }

    public GenerationProgressController(
            GenerationProgressService progressService,
            AuthService authService,
            ProjectService projectService) {
        this(progressService, authService, projectService, null);
    }

    @GetMapping("/api/projects/{projectId}/generation-progress/{stage}")
    public GenerationProgressDto get(
            @PathVariable Long projectId,
            @PathVariable GenerationProgressStage stage,
            @RequestHeader("Authorization") String authorization) {
        projectService.requireAccess(projectId, authService.currentUser(authorization));
        return progressService.get(projectId, stage);
    }

    @PostMapping("/api/projects/{projectId}/generation-progress/{stage}/pause")
    public GenerationProgressDto pause(
            @PathVariable Long projectId,
            @PathVariable GenerationProgressStage stage,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && !authorization.isBlank()) {
            projectService.requireAccess(projectId, authService.currentUser(authorization));
        }
        if (jobService != null) {
            jobService.pause(projectId, stage);
        } else {
            progressService.pause(projectId, stage, "Đã tạm dừng tác vụ.");
        }
        return progressService.get(projectId, stage);
    }
}
