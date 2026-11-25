package com.greytest.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.AuthUserDto;
import com.greytest.dto.ChangePasswordRequest;
import com.greytest.dto.ForgotPasswordRequest;
import com.greytest.dto.ForgotPasswordResponse;
import com.greytest.dto.GithubConfigResponse;
import com.greytest.dto.GithubLoginRequest;
import com.greytest.dto.GoogleConfigResponse;
import com.greytest.dto.GoogleLoginRequest;
import com.greytest.dto.LoginRequest;
import com.greytest.dto.LoginResponse;
import com.greytest.dto.PasswordActionResponse;
import com.greytest.dto.RegisterRequest;
import com.greytest.dto.ResetPasswordRequest;
import com.greytest.dto.UpdateProfileRequest;
import com.greytest.service.AuthService;
import com.greytest.service.GithubOAuthService;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final GithubOAuthService githubOAuthService;

    @Autowired
    public AuthController(AuthService authService, @Autowired(required = false) GithubOAuthService githubOAuthService) {
        this.authService = authService;
        this.githubOAuthService = githubOAuthService;
    }

    public AuthController(AuthService authService) {
        this(authService, null);
    }

    @PostMapping("/register")
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/google-config")
    public GoogleConfigResponse googleConfig() {
        return authService.getGoogleConfig();
    }

    @PostMapping("/google")
    public LoginResponse googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.googleLogin(request);
    }

    @GetMapping("/github-config")
    public GithubConfigResponse githubConfig() {
        return githubOAuthService != null
                ? githubOAuthService.getConfig()
                : new GithubConfigResponse(false, "", "");
    }

    @PostMapping("/github")
    public LoginResponse githubLogin(
            @Valid @RequestBody GithubLoginRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (githubOAuthService == null || !githubOAuthService.isConfigured()) {
            throw new com.greytest.exception.AuthException(
                    "GITHUB_AUTH_NOT_CONFIGURED",
                    "Chức năng GitHub OAuth chưa được cấu hình",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
        return githubOAuthService.loginWithGithub(request.code(), authorization);
    }

    @PostMapping("/admin/login")
    public LoginResponse adminLogin(@Valid @RequestBody LoginRequest request) {
        return authService.adminLogin(request);
    }

    @PostMapping("/forgot-password")
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    public PasswordActionResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }

    @PostMapping("/change-password")
    public PasswordActionResponse changePassword(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(authorization, request);
    }

    @PutMapping("/profile")
    public AuthUserDto updateProfile(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody UpdateProfileRequest request) {
        return authService.updateProfile(authorization, request);
    }

    @GetMapping("/me")
    public AuthUserDto me(@RequestHeader("Authorization") String authorization) {
        return authService.toDto(authService.currentUser(authorization));
    }
}
