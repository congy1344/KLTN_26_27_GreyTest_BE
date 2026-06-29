package com.greytest.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.BusinessRuleDto;
import com.greytest.dto.BusinessRuleReviewDto;
import com.greytest.dto.CreateBusinessRuleRequest;
import com.greytest.dto.UpdateBusinessRuleRequest;
import com.greytest.service.BusinessRuleService;

import jakarta.validation.Valid;

@RestController
public class BusinessRuleController {

    private final BusinessRuleService businessRuleService;

    public BusinessRuleController(BusinessRuleService businessRuleService) {
        this.businessRuleService = businessRuleService;
    }

    @GetMapping("/api/projects/{projectId}/business-rules")
    public List<BusinessRuleDto> list(@PathVariable Long projectId) {
        return businessRuleService.list(projectId);
    }

    @PostMapping("/api/projects/{projectId}/business-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessRuleDto create(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateBusinessRuleRequest request) {
        return businessRuleService.create(projectId, request);
    }

    @PostMapping("/api/projects/{projectId}/business-rules/generate")
    public List<BusinessRuleDto> generate(@PathVariable Long projectId) {
        return businessRuleService.generate(projectId);
    }

    @PostMapping("/api/projects/{projectId}/business-rules/review")
    public BusinessRuleReviewDto review(@PathVariable Long projectId) {
        return businessRuleService.review(projectId);
    }

    @PostMapping("/api/projects/{projectId}/business-rules/approve")
    public List<BusinessRuleDto> approve(@PathVariable Long projectId) {
        return businessRuleService.approve(projectId);
    }

    @PutMapping("/api/business-rules/{ruleId}")
    public BusinessRuleDto update(
            @PathVariable Long ruleId,
            @Valid @RequestBody UpdateBusinessRuleRequest request) {
        return businessRuleService.update(ruleId, request);
    }

    @DeleteMapping("/api/business-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long ruleId) {
        businessRuleService.delete(ruleId);
    }
}
