package com.greytest.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.TraceabilityMatrixDto;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.TraceabilityService;

@RestController
public class TraceabilityController {

    private final TraceabilityService service;
    private final AuthService auth;
    private final ProjectService projects;

    public TraceabilityController(TraceabilityService service, AuthService auth, ProjectService projects) {
        this.service = service;
        this.auth = auth;
        this.projects = projects;
    }

    @GetMapping("/api/projects/{projectId}/traceability")
    public TraceabilityMatrixDto matrix(@PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        projects.requireAccess(projectId, auth.currentUser(authorization));
        return service.getMatrix(projectId);
    }
}
