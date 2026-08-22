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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.BusinessRuleDto;
import com.greytest.dto.BusinessRuleReviewDto;
import com.greytest.dto.GenerationJobAcceptedDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.CreateBusinessRuleRequest;
import com.greytest.dto.UpdateBusinessRuleRequest;
import com.greytest.service.AuthService;
import com.greytest.service.BusinessRuleService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.ProjectService;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.ActivityAction;

import jakarta.validation.Valid;

@RestController
public class BusinessRuleController {

    private final BusinessRuleService businessRuleService;
    private final AuthService authService;
    private final ProjectService projectService;
    private final GenerationJobService generationJobService;

    public BusinessRuleController(
            BusinessRuleService businessRuleService,
            AuthService authService,
            ProjectService projectService,
            GenerationJobService generationJobService) {
        this.businessRuleService = businessRuleService;
        this.authService = authService;
        this.projectService = projectService;
        this.generationJobService = generationJobService;
    }

    @GetMapping("/api/projects/{projectId}/business-rules")
    public List<BusinessRuleDto> list(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        requireAccess(projectId, authorization);
        return businessRuleService.list(projectId);
    }

    @PostMapping("/api/projects/{projectId}/business-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessRuleDto create(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateBusinessRuleRequest request) {
        requireAccess(projectId, authorization);
        return generationJobService.executeMutation(projectId, () -> businessRuleService.create(projectId, request));
    }

    @PostMapping("/api/projects/{projectId}/business-rules/generate")
    public ResponseEntity<GenerationJobAcceptedDto> generate(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        AuthUser actor = requireAccess(projectId, authorization);
        return ResponseEntity.accepted().body(generationJobService.submit(
                projectId,
                actor.getId(),
                GenerationProgressStage.BUSINESS_RULE,
                () -> businessRuleService.generate(projectId)));
    }

    @PostMapping("/api/projects/{projectId}/business-rules/review")
    public BusinessRuleReviewDto review(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        AuthUser actor = requireAccess(projectId, authorization);
        return generationJobService.executeAiMutation(
                projectId, actor.getId(), ActivityAction.REVIEW_BUSINESS_RULE,
                () -> businessRuleService.review(projectId));
    }

    @PostMapping("/api/projects/{projectId}/business-rules/approve")
    public List<BusinessRuleDto> approve(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        requireAccess(projectId, authorization);
        return generationJobService.executeMutation(projectId, () -> businessRuleService.approve(projectId));
    }

    @PutMapping("/api/business-rules/{ruleId}")
    public BusinessRuleDto update(
            @PathVariable Long ruleId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody UpdateBusinessRuleRequest request) {
        Long projectId = businessRuleService.projectIdForRule(ruleId);
        requireAccess(projectId, authorization);
        return generationJobService.executeMutation(projectId, () -> businessRuleService.update(ruleId, request));
    }

    @PostMapping("/api/business-rules/{ruleId}/accept-suggestion")
    public BusinessRuleDto acceptSuggestion(
            @PathVariable Long ruleId,
            @RequestHeader("Authorization") String authorization) {
        Long projectId = businessRuleService.projectIdForRule(ruleId);
        requireAccess(projectId, authorization);
        return generationJobService.executeMutation(projectId, () -> businessRuleService.acceptSuggestion(ruleId));
    }

    @DeleteMapping("/api/business-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long ruleId,
            @RequestHeader("Authorization") String authorization) {
        Long projectId = businessRuleService.projectIdForRule(ruleId);
        requireAccess(projectId, authorization);
        generationJobService.executeMutation(projectId, () -> businessRuleService.delete(ruleId));
    }

    private AuthUser requireAccess(Long projectId, String authorization) {
        AuthUser user = authService.currentUser(authorization);
        projectService.requireAccess(projectId, user);
        return user;
    }
}
