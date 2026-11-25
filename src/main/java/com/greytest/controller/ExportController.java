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
            @RequestParam("format") String format,
            @RequestParam(required = false) String servicePath,
            @RequestHeader("Authorization") String authorization) {
        projects.requireSourceAccess(projectId, auth.currentUser(authorization));
        if (servicePath != null && (servicePath.contains("..") || servicePath.startsWith("/") || servicePath.startsWith("\\") || servicePath.contains(":"))) {
            throw new SecurityException("Đường dẫn servicePath không hợp lệ hoặc chứa ký tự nguy hiểm");
        }
        String normalizedFormat = format != null ? format.toLowerCase() : "";
        if (!"json".equals(normalizedFormat) && !"markdown".equals(normalizedFormat) && !"md".equals(normalizedFormat)) {
            throw new IllegalArgumentException("Format không hợp lệ. Chỉ hỗ trợ json hoặc markdown.");
        }
        String content = service.export(projectId, normalizedFormat, servicePath);
        boolean json = "json".equals(normalizedFormat);
        String fileName = "greytest-report-" + projectId + (json ? ".json" : ".md");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .contentType(json ? MediaType.APPLICATION_JSON : new MediaType("text", "markdown"))
                .body(content);
    }
}
