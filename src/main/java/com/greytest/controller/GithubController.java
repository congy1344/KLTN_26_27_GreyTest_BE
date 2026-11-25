package com.greytest.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.AuthUserDto;
import com.greytest.dto.GithubImportRequest;
import com.greytest.dto.GithubRepoDto;
import com.greytest.dto.ProjectDto;
import com.greytest.entity.AuthUser;
import com.greytest.service.AuthService;
import com.greytest.service.GithubOAuthService;
import com.greytest.service.ProjectService;

import jakarta.validation.Valid;

/**
 * Controller phục vụ các tác vụ liên quan đến GitHub: lấy danh sách repository cá nhân và import dự án.
 */
@RestController
@RequestMapping("/api/github")
public class GithubController {

    private final GithubOAuthService githubOAuthService;
    private final AuthService authService;
    private final ProjectService projectService;

    public GithubController(
            GithubOAuthService githubOAuthService,
            AuthService authService,
            ProjectService projectService) {
        this.githubOAuthService = githubOAuthService;
        this.authService = authService;
        this.projectService = projectService;
    }

    /** Lấy danh sách GitHub repository (Public & Private) của người dùng hiện tại */
    @GetMapping("/repos")
    public List<GithubRepoDto> getRepositories(@RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return githubOAuthService.getUserRepositories(user);
    }

    /** Import repository từ GitHub (hỗ trợ cả repo private) vào danh sách project của GreyTest */
    @PostMapping("/import")
    public ResponseEntity<ProjectDto> importRepository(
            @Valid @RequestBody GithubImportRequest request,
            @RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        ProjectDto project = projectService.createFromGithubWithToken(
                request.cloneUrl(),
                request.branch(),
                user.getGithubAccessToken(),
                user);
        return ResponseEntity.status(HttpStatus.CREATED).body(project);
    }

    /** Ngắt kết nối tài khoản GitHub khỏi người dùng hiện tại */
    @DeleteMapping("/disconnect")
    public AuthUserDto disconnect(@RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return authService.toDto(githubOAuthService.disconnectGithub(user));
    }
}
