package com.greytest.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.ExistingTestDto;
import com.greytest.service.analysis.ExistingTestService;

@RestController
@RequestMapping("/api/projects/{projectId}/existing-tests")
public class ExistingTestController {

    private final ExistingTestService existingTestService;

    public ExistingTestController(ExistingTestService existingTestService) {
        this.existingTestService = existingTestService;
    }

    @GetMapping
    public List<ExistingTestDto> list(@PathVariable Long projectId) {
        return existingTestService.list(projectId);
    }
}
