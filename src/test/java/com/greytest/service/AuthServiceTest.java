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
import com.greytest.dto.GoogleLoginRequest;
import com.greytest.dto.LoginRequest;
import com.greytest.dto.RegisterRequest;
import com.greytest.dto.ResetPasswordRequest;
import com.greytest.dto.UpdateProfileRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;
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

    @Test
    void forgotAndResetPasswordWithOtpValidatesAttemptsAndSingleUse() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUser existingUser = user(UserRole.USER);
        when(repository.findByEmailIgnoreCase("user@greytest.dev")).thenReturn(Optional.of(existingUser));

        AuthService authService = new AuthService(repository, TOKEN_SECRET);
        var forgotRes = authService.forgotPassword(new ForgotPasswordRequest("user@greytest.dev"));
        assertThat(forgotRes.devOtp()).matches("\\d{6}");
        String validOtp = forgotRes.devOtp();
        String wrongOtp = validOtp.equals("000000") ? "111111" : "000000";

        // Nhập sai OTP giảm dần số lần thử
        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest("user@greytest.dev", wrongOtp, "OtpNewPass1", "OtpNewPass1")))
                .isInstanceOfSatisfying(AuthException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_OTP"));

        // Nhập đúng OTP -> đặt lại mật khẩu thành công
        var resetRes = authService.resetPassword(
                new ResetPasswordRequest("user@greytest.dev", validOtp, "OtpNewPass1", "OtpNewPass1"));
        assertThat(resetRes.message()).contains("thành công");
        assertThat(authService.login(new LoginRequest("user@greytest.dev", "OtpNewPass1")).token()).isNotBlank();

        // Dùng lại OTP lần 2 phải bị từ chối
        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest("user@greytest.dev", validOtp, "OtpNewPass2", "OtpNewPass2")))
                .isInstanceOfSatisfying(AuthException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_OTP"));

        // Sai OTP quá 5 lần liên tiếp bị khóa OTP hiện tại
        authService.forgotPassword(new ForgotPasswordRequest("user@greytest.dev"));
        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> authService.resetPassword(
                    new ResetPasswordRequest("user@greytest.dev", "999999", "OtpNewPass3", "OtpNewPass3")))
                    .isInstanceOfSatisfying(AuthException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_OTP"));
        }
        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest("user@greytest.dev", "999999", "OtpNewPass3", "OtpNewPass3")))
                .isInstanceOfSatisfying(AuthException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("OTP_ATTEMPTS_EXCEEDED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });
    }

    @Test
    void googleLoginProvisionsNewUserAndBlocksDisabledOrAdminAccounts() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        GoogleTokenVerifier verifier = mock(GoogleTokenVerifier.class);
        when(verifier.isConfigured()).thenReturn(true);
        when(verifier.getGoogleClientId()).thenReturn("test-google-client-id");

        AuthService authService = new AuthService(repository, TOKEN_SECRET, null, null, null, verifier);
        assertThat(authService.getGoogleConfig().enabled()).isTrue();
        assertThat(authService.getGoogleConfig().clientId()).isEqualTo("test-google-client-id");

        // 1. Tạo user mới khi lần đầu đăng nhập Google
        when(verifier.verifyIdToken("valid-new-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleUserPayload("newgoogle@gmail.com", "Google New", "sub-1"));
        when(repository.findByEmailIgnoreCase("newgoogle@gmail.com")).thenReturn(Optional.empty());
        when(repository.save(any(AuthUser.class))).thenAnswer(invocation -> {
            AuthUser saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        var createdLogin = authService.googleLogin(new GoogleLoginRequest("valid-new-token"));
        assertThat(createdLogin.token()).isNotBlank();
        assertThat(createdLogin.user().email()).isEqualTo("newgoogle@gmail.com");
        assertThat(createdLogin.user().role()).isEqualTo(UserRole.USER);
        assertThat(createdLogin.user().tier()).isEqualTo(UserTier.FREE);

        // 2. Chặn tài khoản bị khóa
        AuthUser disabledUser = user(UserRole.USER);
        disabledUser.setEmail("disabled@gmail.com");
        disabledUser.setEnabled(false);
        when(verifier.verifyIdToken("disabled-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleUserPayload("disabled@gmail.com", "Disabled", "sub-2"));
        when(repository.findByEmailIgnoreCase("disabled@gmail.com")).thenReturn(Optional.of(disabledUser));

        assertThatThrownBy(() -> authService.googleLogin(new GoogleLoginRequest("disabled-token")))
                .isInstanceOfSatisfying(AuthException.class, ex -> assertThat(ex.getCode()).isEqualTo("ACCOUNT_DISABLED"));

        // 3. Chặn tài khoản ADMIN đăng nhập qua cổng User Google
        AuthUser adminUser = user(UserRole.ADMIN);
        when(verifier.verifyIdToken("admin-token"))
                .thenReturn(new GoogleTokenVerifier.GoogleUserPayload("admin@greytest.dev", "Admin", "sub-3"));
        when(repository.findByEmailIgnoreCase("admin@greytest.dev")).thenReturn(Optional.of(adminUser));

        assertThatThrownBy(() -> authService.googleLogin(new GoogleLoginRequest("admin-token")))
                .isInstanceOfSatisfying(AuthException.class, ex -> assertThat(ex.getCode()).isEqualTo("ADMIN_LOGIN_REQUIRED"));
    }

    @Test
    void googleLoginRejectsWhenClientIdNotConfigured() {
        AuthService authService = new AuthService(mock(AuthUserRepository.class), TOKEN_SECRET);
        assertThat(authService.getGoogleConfig().enabled()).isFalse();

        assertThatThrownBy(() -> authService.googleLogin(new GoogleLoginRequest("some-token")))
                .isInstanceOfSatisfying(AuthException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("GOOGLE_AUTH_NOT_CONFIGURED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }

    @Test
    void forgotPasswordEnforcesCooldownBlocksAdminAndFailsClosedWhenConfiguredSmtpErrors() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUser regularUser = user(UserRole.USER);
        AuthUser adminUser = user(UserRole.ADMIN);
        when(repository.findByEmailIgnoreCase("user@greytest.dev")).thenReturn(Optional.of(regularUser));
        when(repository.findByEmailIgnoreCase("admin@greytest.dev")).thenReturn(Optional.of(adminUser));

        // 1. Chặn admin gọi forgotPassword qua cổng người dùng
        AuthService baseService = new AuthService(repository, TOKEN_SECRET);
        assertThatThrownBy(() -> baseService.forgotPassword(new ForgotPasswordRequest("admin@greytest.dev")))
                .isInstanceOfSatisfying(AuthException.class, ex -> assertThat(ex.getCode()).isEqualTo("ADMIN_ACCESS_DENIED"));

        // 2. Chặn spam forgotPassword liên tiếp trong vòng 60s khi OTP chưa tiêu thụ
        baseService.forgotPassword(new ForgotPasswordRequest("user@greytest.dev"));
        assertThatThrownBy(() -> baseService.forgotPassword(new ForgotPasswordRequest("user@greytest.dev")))
                .isInstanceOfSatisfying(AuthException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("OTP_COOLDOWN");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });

        // 3. Khi SMTP đã cấu hình nhưng gửi lỗi -> ném 503 MAIL_SEND_FAILED, không trả devOtp/resetUrl
        PasswordResetMailService failingMailService = mock(PasswordResetMailService.class);
        when(failingMailService.isSmtpConfigured()).thenReturn(true);
        when(failingMailService.sendPasswordResetOtpEmail(any(), any(), any())).thenReturn(false);

        AuthService smtpService = new AuthService(repository, TOKEN_SECRET, null, null, failingMailService, null);
        assertThatThrownBy(() -> smtpService.forgotPassword(new ForgotPasswordRequest("user@greytest.dev")))
                .isInstanceOfSatisfying(AuthException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("MAIL_SEND_FAILED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        // 4. Khi SMTP gửi thành công -> không trả devOtp hay resetUrl
        PasswordResetMailService okMailService = mock(PasswordResetMailService.class);
        when(okMailService.isSmtpConfigured()).thenReturn(true);
        when(okMailService.sendPasswordResetOtpEmail(any(), any(), any())).thenReturn(true);

        AuthService okSmtpService = new AuthService(repository, TOKEN_SECRET, null, null, okMailService, null);
        var res = okSmtpService.forgotPassword(new ForgotPasswordRequest("user@greytest.dev"));
        assertThat(res.mailSent()).isTrue();
        assertThat(res.devOtp()).isNull();
        assertThat(res.resetUrl()).isNull();
    }

    @Test
    void verifiedEmailLoginEvictsWhoeverRegisteredTheAddressFirst() {
        AuthUser squatted = user(UserRole.USER);
        AuthUserRepository repository = mock(AuthUserRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(squatted));
        AuthService service = new AuthService(repository, TOKEN_SECRET);
        String oldSession = "Bearer " + service.issueToken(squatted);
        assertThat(service.currentUser(oldSession)).isSameAs(squatted);

        assertThat(AuthService.claimByVerifiedEmail(squatted, new BCryptPasswordEncoder())).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("correct-password", squatted.getPasswordHash())).isFalse();
        // Lùi mốc để không phụ thuộc việc JWT làm tròn tới giây.
        squatted.setSessionsValidFrom(squatted.getSessionsValidFrom().plusSeconds(2));
        assertThatThrownBy(() -> service.currentUser(oldSession)).isInstanceOf(AuthException.class);

        // Lần đăng nhập OAuth sau không đụng tới mật khẩu người dùng đã đặt.
        String hash = squatted.getPasswordHash();
        assertThat(AuthService.claimByVerifiedEmail(squatted, new BCryptPasswordEncoder())).isFalse();
        assertThat(squatted.getPasswordHash()).isEqualTo(hash);
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
