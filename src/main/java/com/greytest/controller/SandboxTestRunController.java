package com.greytest.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.coverage.SandboxTestRunService;
import com.greytest.service.coverage.SandboxTestRunService.RunState;

/** Chạy Unit Test đã sinh trong sandbox trên server và theo dõi trạng thái lần chạy. */
@RestController
public class SandboxTestRunController {
    private final SandboxTestRunService service;
    private final AuthService auth;
    private final ProjectService projects;

    public SandboxTestRunController(SandboxTestRunService service, AuthService auth, ProjectService projects) {
        this.service = service;
        this.auth = auth;
        this.projects = projects;
    }

    @PostMapping("/api/projects/{projectId}/coverage/run")
    public ResponseEntity<RunState> start(@PathVariable Long projectId,
            @RequestParam(required = false) String servicePath,
            @RequestParam(defaultValue = "false") boolean repair,
            @RequestHeader("Authorization") String authorization) {
        var user = auth.currentUser(authorization);
        projects.requireAccess(projectId, user);
        // repair=true: cho AI sửa test không đạt rồi chạy lại; lượt gọi AI tính vào quota của người bấm.
        return ResponseEntity.accepted().body(service.start(projectId, servicePath, repair ? user.getId() : null));
    }

    @GetMapping("/api/projects/{projectId}/coverage/run")
    public RunState state(@PathVariable Long projectId, @RequestHeader("Authorization") String authorization) {
        projects.requireAccess(projectId, auth.currentUser(authorization));
        return service.state(projectId);
    }
}
