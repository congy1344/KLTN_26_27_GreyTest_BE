package com.greytest.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.greytest.dto.CoverageReportDto;
import com.greytest.dto.CoverageRefinementDto;
import com.greytest.service.AuthService;
import com.greytest.service.CoverageService;
import com.greytest.service.CoverageRefinementService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.ProjectService;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.ActivityAction;

@RestController
public class CoverageController {

    private final CoverageService service;
    private final AuthService auth;
    private final ProjectService projects;
    private final CoverageRefinementService refinement;
    private final GenerationJobService jobs;

    public CoverageController(CoverageService service, AuthService auth, ProjectService projects,
            CoverageRefinementService refinement, GenerationJobService jobs) {
        this.service = service;
        this.auth = auth;
        this.projects = projects;
        this.refinement = refinement;
        this.jobs = jobs;
    }

    @PostMapping("/api/projects/{projectId}/coverage/upload")
    public ResponseEntity<CoverageReportDto> upload(@PathVariable Long projectId,
            @RequestParam("file") MultipartFile file, @RequestHeader("Authorization") String authorization) {
        access(projectId, authorization);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(jobs.executeMutation(projectId, () -> service.upload(projectId, file)));
    }

    /** Trả 204 khi project chưa có coverage report nào. */
    @GetMapping("/api/projects/{projectId}/coverage")
    public ResponseEntity<CoverageReportDto> latest(@PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        access(projectId, authorization);
        return service.latest(projectId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/projects/{projectId}/coverage/refine")
    public CoverageRefinementDto refine(@PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        AuthUser actor = access(projectId, authorization);
        return jobs.executeAiMutation(
                projectId, actor.getId(), ActivityAction.COVERAGE_REFINEMENT,
                () -> refinement.start(projectId));
    }

    private AuthUser access(Long id, String authorization) {
        AuthUser user = auth.currentUser(authorization);
        projects.requireAccess(id, user);
        return user;
    }
}
