package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.greytest.dto.LoginRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;

class AuthServiceTest {

    private static final String TOKEN_SECRET = "greytest-test-token-secret-with-32-bytes";

    @Test
    void rejectsWeakTokenSecretAtStartup() {
        assertThatThrownBy(() -> new AuthService(mock(AuthUserRepository.class), "too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void adminLoginAcceptsActiveAdminAccount() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUser admin = user(UserRole.ADMIN);
        when(repository.findByEmailIgnoreCase("admin@greytest.dev")).thenReturn(Optional.of(admin));

        var response = new AuthService(repository, TOKEN_SECRET)
                .adminLogin(new LoginRequest("admin@greytest.dev", "correct-password"));

        assertThat(response.user().role()).isEqualTo(UserRole.ADMIN);
        assertThat(response.token()).isNotBlank();
    }

    @Test
    void adminLoginRejectsRegularUserWithoutIssuingAdminSession() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        when(repository.findByEmailIgnoreCase("user@greytest.dev"))
                .thenReturn(Optional.of(user(UserRole.USER)));

        assertThatThrownBy(() -> new AuthService(repository, TOKEN_SECRET)
                .adminLogin(new LoginRequest("user@greytest.dev", "correct-password")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("quyen quan tri");
    }

    @Test
    void regularLoginRejectsAdminAndRequiresDedicatedFlow() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        when(repository.findByEmailIgnoreCase("admin@greytest.dev"))
                .thenReturn(Optional.of(user(UserRole.ADMIN)));

        assertThatThrownBy(() -> new AuthService(repository, TOKEN_SECRET)
                .login(new LoginRequest("admin@greytest.dev", "correct-password")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("cong dang nhap Admin");
    }

    private AuthUser user(UserRole role) {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setEmail(role == UserRole.ADMIN ? "admin@greytest.dev" : "user@greytest.dev");
        user.setFullName("Test User");
        user.setRole(role);
        user.setEnabled(true);
        user.setPasswordHash(new BCryptPasswordEncoder().encode("correct-password"));
        return user;
    }
}
