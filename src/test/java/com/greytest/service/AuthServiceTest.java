package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.greytest.dto.ChangePasswordRequest;
import com.greytest.dto.ForgotPasswordRequest;
import com.greytest.dto.LoginRequest;
import com.greytest.dto.RegisterRequest;
import com.greytest.dto.ResetPasswordRequest;
import com.greytest.dto.UpdateProfileRequest;
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
                .hasMessageContaining("quyền quản trị");
    }

    @Test
    void regularLoginRejectsAdminAndRequiresDedicatedFlow() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        when(repository.findByEmailIgnoreCase("admin@greytest.dev"))
                .thenReturn(Optional.of(user(UserRole.ADMIN)));

        assertThatThrownBy(() -> new AuthService(repository, TOKEN_SECRET)
                .login(new LoginRequest("admin@greytest.dev", "correct-password")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("cổng đăng nhập Admin");
    }

    @Test
    void loginRejectsDisabledUserWithExplicitAccountDisabledError() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUser disabledUser = user(UserRole.USER);
        disabledUser.setEnabled(false);
        when(repository.findByEmailIgnoreCase("user@greytest.dev"))
                .thenReturn(Optional.of(disabledUser));

        assertThatThrownBy(() -> new AuthService(repository, TOKEN_SECRET)
                .login(new LoginRequest("user@greytest.dev", "correct-password")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "ACCOUNT_DISABLED".equals(((AuthException) e).getCode()))
                .hasMessageContaining("vô hiệu hóa");
    }

    @Test
    void loginValidatesBlankOrInvalidEmailAndPasswordRegexAndLength() {
        AuthService authService = new AuthService(mock(AuthUserRepository.class), TOKEN_SECRET);

        // Email trống
        assertThatThrownBy(() -> authService.login(new LoginRequest("   ", "correct-password")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "VALIDATION_ERROR".equals(((AuthException) e).getCode()))
                .hasMessageContaining("Email không được để trống");

        // Email sai định dạng regex
        assertThatThrownBy(() -> authService.login(new LoginRequest("invalid-email@", "correct-password")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "VALIDATION_ERROR".equals(((AuthException) e).getCode()))
                .hasMessageContaining("Email không đúng định dạng");

        // Email vượt quá 254 ký tự
        String longEmail = "a".repeat(245) + "@example.com";
        assertThatThrownBy(() -> authService.login(new LoginRequest(longEmail, "correct-password")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "VALIDATION_ERROR".equals(((AuthException) e).getCode()))
                .hasMessageContaining("254 ký tự");

        // Password trống
        assertThatThrownBy(() -> authService.login(new LoginRequest("user@greytest.dev", "")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "VALIDATION_ERROR".equals(((AuthException) e).getCode()))
                .hasMessageContaining("Mật khẩu không được để trống");

        // Password quá ngắn (< 6) hoặc quá dài (> 72)
        assertThatThrownBy(() -> authService.login(new LoginRequest("user@greytest.dev", "12345")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "VALIDATION_ERROR".equals(((AuthException) e).getCode()))
                .hasMessageContaining("từ 6 đến 72 ký tự");

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@greytest.dev", "a".repeat(73))))
                .isInstanceOf(AuthException.class)
                .matches(e -> "VALIDATION_ERROR".equals(((AuthException) e).getCode()))
                .hasMessageContaining("từ 6 đến 72 ký tự");

        // Password chứa khoảng trắng hoặc không khớp regex
        assertThatThrownBy(() -> authService.login(new LoginRequest("user@greytest.dev", "pass word 123")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "VALIDATION_ERROR".equals(((AuthException) e).getCode()))
                .hasMessageContaining("không chứa khoảng trắng");

        // Họ tên đăng ký vượt giới hạn
        assertThatThrownBy(() -> authService.register(new RegisterRequest("user@greytest.dev", "correct-password", "A")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "VALIDATION_ERROR".equals(((AuthException) e).getCode()))
                .hasMessageContaining("Họ tên phải từ 2 đến 100 ký tự");
    }

    @Test
    void loginEnforcesBruteForceRateLimitingAfterRepeatedFailures() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        when(repository.findByEmailIgnoreCase("user@greytest.dev"))
                .thenReturn(Optional.of(user(UserRole.USER)));

        AuthService authService = new AuthService(repository, TOKEN_SECRET);

        // 4 lần sai đầu tiên trả 401 UNAUTHORIZED
        for (int i = 1; i < AuthService.MAX_FAILED_LOGIN_ATTEMPTS; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("user@greytest.dev", "wrong-pass-" + System.nanoTime())))
                    .isInstanceOf(AuthException.class)
                    .matches(e -> ((AuthException) e).getStatus() == HttpStatus.UNAUTHORIZED);
        }

        // Lần sai thứ 5 kích hoạt khóa chống brute-force (429 TOO_MANY_REQUESTS)
        assertThatThrownBy(() -> authService.login(new LoginRequest("user@greytest.dev", "wrong-password")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "TOO_MANY_LOGIN_ATTEMPTS".equals(((AuthException) e).getCode())
                        && ((AuthException) e).getStatus() == HttpStatus.TOO_MANY_REQUESTS);

        // Khi đang bị khóa, kể cả gửi mật khẩu đúng cũng bị từ chối với 429
        assertThatThrownBy(() -> authService.login(new LoginRequest("user@greytest.dev", "correct-password")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "TOO_MANY_LOGIN_ATTEMPTS".equals(((AuthException) e).getCode())
                        && ((AuthException) e).getStatus() == HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void forgotAndResetPasswordFlowUpdatesPasswordAndInvalidatesTokenOnReuse() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUser existingUser = user(UserRole.USER);
        when(repository.findByEmailIgnoreCase("user@greytest.dev")).thenReturn(Optional.of(existingUser));
        when(repository.findById(1L)).thenReturn(Optional.of(existingUser));

        AuthService authService = new AuthService(repository, TOKEN_SECRET);

        var forgotRes = authService.forgotPassword(new ForgotPasswordRequest("user@greytest.dev"));
        assertThat(forgotRes.resetUrl()).contains("/reset-password?token=");

        String resetToken = forgotRes.resetUrl().substring(forgotRes.resetUrl().indexOf("token=") + 6);
        var resetRes = authService.resetPassword(new ResetPasswordRequest(resetToken, "NewPass789", "NewPass789"));
        assertThat(resetRes.message()).contains("thành công");

        // Đăng nhập bằng mật khẩu mới thành công
        var loginRes = authService.login(new LoginRequest("user@greytest.dev", "NewPass789"));
        assertThat(loginRes.token()).isNotBlank();

        // Dùng lại cùng resetToken lần 2 phải bị từ chối vì passwordHash đã thay đổi
        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(resetToken, "AnotherPass123", "AnotherPass123")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "INVALID_RESET_TOKEN".equals(((AuthException) e).getCode()));
    }

    @Test
    void changePasswordVerifiesCurrentPasswordAndUpdatesHash() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUser existingUser = user(UserRole.USER);
        when(repository.findByEmailIgnoreCase("user@greytest.dev")).thenReturn(Optional.of(existingUser));
        when(repository.findById(1L)).thenReturn(Optional.of(existingUser));

        AuthService authService = new AuthService(repository, TOKEN_SECRET);
        String bearer = "Bearer " + authService.login(new LoginRequest("user@greytest.dev", "correct-password")).token();

        // Sai mật khẩu hiện tại
        assertThatThrownBy(() -> authService.changePassword(bearer,
                new ChangePasswordRequest("wrong-current", "NewValidPass123", "NewValidPass123")))
                .isInstanceOf(AuthException.class)
                .matches(e -> "INVALID_CURRENT_PASSWORD".equals(((AuthException) e).getCode()));

        // Đổi mật khẩu hợp lệ
        var res = authService.changePassword(bearer,
                new ChangePasswordRequest("correct-password", "NewValidPass123", "NewValidPass123"));
        assertThat(res.message()).contains("thành công");

        // Đăng nhập bằng mật khẩu mới
        assertThat(authService.login(new LoginRequest("user@greytest.dev", "NewValidPass123")).token()).isNotBlank();
    }

    @Test
    void updateProfile_updatesFullName_whenValid() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUser user = user(UserRole.USER);
        when(repository.findByEmailIgnoreCase("user@greytest.dev")).thenReturn(Optional.of(user));
        when(repository.findById(1L)).thenReturn(Optional.of(user));
        when(repository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthService authService = new AuthService(repository, TOKEN_SECRET);
        String bearer = "Bearer " + authService.login(new LoginRequest("user@greytest.dev", "correct-password")).token();

        var updated = authService.updateProfile(bearer, new UpdateProfileRequest("Nguyen Van Khai Tien"));
        assertThat(updated.fullName()).isEqualTo("Nguyen Van Khai Tien");
        assertThat(user.getFullName()).isEqualTo("Nguyen Van Khai Tien");
    }

    @Test
    void updateProfile_updatesAndClearsOptionalFields() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUser user = user(UserRole.USER);
        when(repository.findByEmailIgnoreCase("user@greytest.dev")).thenReturn(Optional.of(user));
        when(repository.findById(1L)).thenReturn(Optional.of(user));
        when(repository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthService authService = new AuthService(repository, TOKEN_SECRET);
        String bearer = "Bearer " + authService.login(new LoginRequest("user@greytest.dev", "correct-password")).token();

        // Cập nhật đầy đủ các trường
        var updated = authService.updateProfile(bearer, new UpdateProfileRequest(
                "Nguyen Van Khai Tien", "0912345678", "MALE", java.time.LocalDate.of(2002, 5, 15)));
        assertThat(updated.fullName()).isEqualTo("Nguyen Van Khai Tien");
        assertThat(updated.phoneNumber()).isEqualTo("0912345678");
        assertThat(updated.gender()).isEqualTo("MALE");
        assertThat(updated.dateOfBirth()).isEqualTo(java.time.LocalDate.of(2002, 5, 15));

        // Xóa các trường tùy chọn về null
        var cleared = authService.updateProfile(bearer, new UpdateProfileRequest(
                "Nguyen Van Khai Tien", null, null, null));
        assertThat(cleared.phoneNumber()).isNull();
        assertThat(cleared.gender()).isNull();
        assertThat(cleared.dateOfBirth()).isNull();
    }

    @Test
    void updateProfile_throwsException_whenFullNameInvalid() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUser user = user(UserRole.USER);
        when(repository.findByEmailIgnoreCase("user@greytest.dev")).thenReturn(Optional.of(user));
        when(repository.findById(1L)).thenReturn(Optional.of(user));

        AuthService authService = new AuthService(repository, TOKEN_SECRET);
        String bearer = "Bearer " + authService.login(new LoginRequest("user@greytest.dev", "correct-password")).token();

        assertThatThrownBy(() -> authService.updateProfile(bearer, new UpdateProfileRequest("")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("Họ và tên không được để trống");

        assertThatThrownBy(() -> authService.updateProfile(bearer, new UpdateProfileRequest("A")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("Họ và tên phải từ 2 đến 100 ký tự");
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
