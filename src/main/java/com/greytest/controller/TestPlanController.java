package com.greytest.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.CreateTestPlanRequest;
import com.greytest.dto.GenerationJobAcceptedDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.TestPlanDto;
import com.greytest.dto.UpdateTestPlanRequest;
import com.greytest.service.AuthService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.ProjectService;
import com.greytest.service.TestPlanService;
import com.greytest.entity.AuthUser;

import jakarta.validation.Valid;

@RestController
public class TestPlanController {

    private final TestPlanService testPlanService;
    private final AuthService authService;
    private final ProjectService projectService;
    private final GenerationJobService generationJobService;

    public TestPlanController(
            TestPlanService testPlanService,
            AuthService authService,
            ProjectService projectService,
            GenerationJobService generationJobService) {
        this.testPlanService = testPlanService;
        this.authService = authService;
        this.projectService = projectService;
        this.generationJobService = generationJobService;
    }

    @GetMapping("/api/projects/{projectId}/test-plans")
    public List<TestPlanDto> list(
            @PathVariable Long projectId,
            @RequestParam(required = false) String servicePath,
            @RequestHeader("Authorization") String authorization) {
        requireAccess(projectId, authorization);
        return testPlanService.list(projectId, servicePath);
    }

    @PostMapping("/api/projects/{projectId}/test-plans/generate")
    public ResponseEntity<GenerationJobAcceptedDto> generate(
            @PathVariable Long projectId,
            @RequestParam(required = false) String servicePath,
            @RequestHeader("Authorization") String authorization) {
        AuthUser actor = requireAccess(projectId, authorization);
        return ResponseEntity.accepted().body(generationJobService.submit(
                projectId,
                actor.getId(),
                GenerationProgressStage.TEST_PLAN,
                () -> testPlanService.generate(projectId, servicePath)));
    }

    @PostMapping("/api/projects/{projectId}/test-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public TestPlanDto create(
            @PathVariable Long projectId,
            @RequestParam(required = false) String servicePath,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateTestPlanRequest request) {
        requireAccess(projectId, authorization);
        return generationJobService.executeMutation(
                projectId, () -> testPlanService.create(projectId, servicePath, request));
    }

    @PostMapping("/api/projects/{projectId}/test-plans/approve")
    public List<TestPlanDto> approve(
            @PathVariable Long projectId,
            @RequestParam(required = false) String servicePath,
            @RequestHeader("Authorization") String authorization) {
        requireAccess(projectId, authorization);
        return generationJobService.executeMutation(
                projectId, () -> testPlanService.approve(projectId, servicePath));
    }

    @PutMapping("/api/test-plans/{planId}")
    public TestPlanDto update(
            @PathVariable Long planId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody UpdateTestPlanRequest request) {
        Long projectId = testPlanService.projectIdForPlan(planId);
        requireAccess(projectId, authorization);
        return generationJobService.executeMutation(projectId, () -> testPlanService.update(planId, request));
    }

    @DeleteMapping("/api/test-plans/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long planId,
            @RequestHeader("Authorization") String authorization) {
        Long projectId = testPlanService.projectIdForPlan(planId);
        requireAccess(projectId, authorization);
        generationJobService.executeMutation(projectId, () -> testPlanService.delete(planId));
    }

    private AuthUser requireAccess(Long projectId, String authorization) {
        AuthUser user = authService.currentUser(authorization);
        projectService.requireAccess(projectId, user);
        return user;
    }
}
