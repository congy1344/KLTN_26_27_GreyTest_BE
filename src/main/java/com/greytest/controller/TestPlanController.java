package com.greytest.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.CreateTestPlanRequest;
import com.greytest.dto.TestPlanDto;
import com.greytest.dto.UpdateTestPlanRequest;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.TestPlanService;

import jakarta.validation.Valid;

@RestController
public class TestPlanController {

    private final TestPlanService testPlanService;
    private final AuthService authService;
    private final ProjectService projectService;

    public TestPlanController(
            TestPlanService testPlanService,
            AuthService authService,
            ProjectService projectService) {
        this.testPlanService = testPlanService;
        this.authService = authService;
        this.projectService = projectService;
    }

    @GetMapping("/api/projects/{projectId}/test-plans")
    public List<TestPlanDto> list(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        requireAccess(projectId, authorization);
        return testPlanService.list(projectId);
    }

    @PostMapping("/api/projects/{projectId}/test-plans/generate")
    public List<TestPlanDto> generate(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        requireAccess(projectId, authorization);
        return testPlanService.generate(projectId);
    }

    @PostMapping("/api/projects/{projectId}/test-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public TestPlanDto create(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateTestPlanRequest request) {
        requireAccess(projectId, authorization);
        return testPlanService.create(projectId, request);
    }

    @PostMapping("/api/projects/{projectId}/test-plans/approve")
    public List<TestPlanDto> approve(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        requireAccess(projectId, authorization);
        return testPlanService.approve(projectId);
    }

    @PutMapping("/api/test-plans/{planId}")
    public TestPlanDto update(
            @PathVariable Long planId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody UpdateTestPlanRequest request) {
        requireAccess(testPlanService.projectIdForPlan(planId), authorization);
        return testPlanService.update(planId, request);
    }

    @DeleteMapping("/api/test-plans/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long planId,
            @RequestHeader("Authorization") String authorization) {
        requireAccess(testPlanService.projectIdForPlan(planId), authorization);
        testPlanService.delete(planId);
    }

    private void requireAccess(Long projectId, String authorization) {
        projectService.requireAccess(projectId, authService.currentUser(authorization));
    }
}
