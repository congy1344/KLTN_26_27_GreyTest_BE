package com.greytest.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.greytest.dto.GithubCloneRequest;
import com.greytest.dto.ProjectDto;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final AuthService authService;

    public ProjectController(ProjectService projectService, AuthService authService) {
        this.projectService = projectService;
        this.authService = authService;
    }

    @PostMapping("/upload")
    public ResponseEntity<ProjectDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createFromZip(file, authService.optionalCurrentUser(authorization).orElse(null)));
    }

    @PostMapping("/github")
    public ResponseEntity<ProjectDto> github(
            @Valid @RequestBody GithubCloneRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createFromGithub(request.url(), authService.optionalCurrentUser(authorization).orElse(null)));
    }

    @GetMapping
    public List<ProjectDto> list(@RequestHeader("Authorization") String authorization) {
        return projectService.getAll(authService.currentUser(authorization));
    }

    @GetMapping("/{id}")
    public ProjectDto get(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authorization) {
        return projectService.getById(id, authService.currentUser(authorization));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authorization) {
        projectService.delete(id, authService.currentUser(authorization));
        return ResponseEntity.noContent().build();
    }
}
