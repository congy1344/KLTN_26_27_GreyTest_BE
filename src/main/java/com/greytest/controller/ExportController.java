package com.greytest.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.service.AuthService;
import com.greytest.service.ExportService;
import com.greytest.service.ProjectService;

@RestController
public class ExportController {

    private final ExportService service;
    private final AuthService auth;
    private final ProjectService projects;

    public ExportController(ExportService service, AuthService auth, ProjectService projects) {
        this.service = service;
        this.auth = auth;
        this.projects = projects;
    }

    @GetMapping("/api/projects/{projectId}/export")
    public ResponseEntity<String> export(@PathVariable Long projectId,
            @RequestParam("format") String format, @RequestHeader("Authorization") String authorization) {
        projects.requireAccess(projectId, auth.currentUser(authorization));
        String content = service.export(projectId, format);
        boolean json = "json".equals(format);
        String fileName = "greytest-report-" + projectId + (json ? ".json" : ".md");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(json ? MediaType.APPLICATION_JSON : new MediaType("text", "markdown"))
                .body(content);
    }
}
