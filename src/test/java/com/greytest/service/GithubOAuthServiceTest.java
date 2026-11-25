package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.greytest.dto.GithubConfigResponse;
import com.greytest.entity.AuthUser;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;

class GithubOAuthServiceTest {

    private AuthUserRepository userRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(AuthUserRepository.class);
        authService = mock(AuthService.class);
    }

    @Test
    void isConfigured_shouldReturnTrueWhenIdAndSecretSet() {
        GithubOAuthService service = new GithubOAuthService(
                "test-client-id",
                "test-client-secret",
                "http://localhost:5173/auth/callback/github",
                userRepository,
                authService,
                new SettingsCipher("github-oauth-test-secret"));

        assertThat(service.isConfigured()).isTrue();
        GithubConfigResponse config = service.getConfig();
        assertThat(config.enabled()).isTrue();
        assertThat(config.clientId()).isEqualTo("test-client-id");
        assertThat(config.redirectUri()).isEqualTo("http://localhost:5173/auth/callback/github");
    }

    @Test
    void isConfigured_shouldReturnFalseWhenSecretEmpty() {
        GithubOAuthService service = new GithubOAuthService(
                "test-client-id",
                "",
                "http://localhost:5173/auth/callback/github",
                userRepository,
                authService,
                new SettingsCipher("github-oauth-test-secret"));

        assertThat(service.isConfigured()).isFalse();
        assertThat(service.getConfig().enabled()).isFalse();
    }

    @Test
    void loginWithGithub_shouldThrowWhenNotConfigured() {
        GithubOAuthService service = new GithubOAuthService(
                "",
                "",
                "http://localhost:5173/auth/callback/github",
                userRepository,
                authService,
                new SettingsCipher("github-oauth-test-secret"));

        assertThatThrownBy(() -> service.loginWithGithub("test-code", null))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("chưa được cấu hình");
    }

    @Test
    void loginWithGithub_shouldThrowWhenCodeIsBlank() {
        GithubOAuthService service = new GithubOAuthService(
                "client-id",
                "client-secret",
                "http://localhost:5173/auth/callback/github",
                userRepository,
                authService,
                new SettingsCipher("github-oauth-test-secret"));

        assertThatThrownBy(() -> service.loginWithGithub("  ", null))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("không hợp lệ");
    }

    @Test
    void getUserRepositories_shouldThrowWhenUserHasNoGithubToken() {
        GithubOAuthService service = new GithubOAuthService(
                "client-id",
                "client-secret",
                "http://localhost:5173/auth/callback/github",
                userRepository,
                authService,
                new SettingsCipher("github-oauth-test-secret"));

        AuthUser user = new AuthUser();
        user.setEmail("user@example.com");
        user.setGithubAccessToken(null);

        assertThatThrownBy(() -> service.getUserRepositories(user))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("chưa liên kết GitHub");
    }

    @Test
    void disconnectGithub_shouldClearGithubFieldsAndSaveUser() {
        GithubOAuthService service = new GithubOAuthService(
                "",
                "",
                "http://localhost:3000/auth/callback/github",
                userRepository,
                authService,
                new SettingsCipher("github-oauth-test-secret"));

        AuthUser user = new AuthUser();
        user.setEmail("user@example.com");
        user.setGithubUserId("142229123");
        user.setGithubUsername("tiennguyen2406");
        user.setGithubAccessToken("gho_secret_token");
        when(userRepository.save(user)).thenReturn(user);

        AuthUser updated = service.disconnectGithub(user);

        assertThat(updated.getGithubUserId()).isNull();
        assertThat(updated.getGithubUsername()).isNull();
        assertThat(updated.getGithubAccessToken()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void githubTokenIsStoredEncryptedAndNeverUsedAsPlaintext() {
        SettingsCipher cipher = new SettingsCipher("github-oauth-test-secret");
        GithubOAuthService service = new GithubOAuthService(
                "client-id", "client-secret", "http://localhost:3000/auth/callback/github",
                userRepository, authService, cipher);
        AuthUser user = new AuthUser();

        String sealed = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "seal", "gho_secret_token");
        assertThat(sealed).isNotNull().doesNotContain("gho_secret_token");
        user.setGithubAccessToken(sealed);
        assertThat(service.accessToken(user)).isEqualTo("gho_secret_token");

        // Token cũ lưu dạng văn bản thường không được dùng tiếp: người dùng phải kết nối lại.
        user.setGithubAccessToken("gho_legacy_plaintext");
        assertThat(service.accessToken(user)).isNull();
    }

    @Test
    void githubTokenIsNotStoredWithoutSettingsSecret() {
        GithubOAuthService service = new GithubOAuthService(
                "client-id", "client-secret", "http://localhost:3000/auth/callback/github",
                userRepository, authService, new SettingsCipher(""));
        String sealed = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "seal", "gho_secret_token");
        assertThat(sealed).isNull();
    }
}
