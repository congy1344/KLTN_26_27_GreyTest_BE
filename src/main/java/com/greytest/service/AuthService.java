package com.greytest.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.AuthUserDto;
import com.greytest.dto.ChangePasswordRequest;
import com.greytest.dto.ForgotPasswordRequest;
import com.greytest.dto.ForgotPasswordResponse;
import com.greytest.dto.GoogleConfigResponse;
import com.greytest.dto.GoogleLoginRequest;
import com.greytest.dto.LoginRequest;
import com.greytest.dto.LoginResponse;
import com.greytest.dto.PasswordActionResponse;
import com.greytest.dto.RegisterRequest;
import com.greytest.dto.ResetPasswordRequest;
import com.greytest.dto.UpdateProfileRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Quản lý đăng ký, đăng nhập và xác thực JWT HS256 của GreyTest. */
@Service
public class AuthService {

    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    // Phiên có quyền quản trị ngắn hơn theo khuyến nghị OWASP ASVS cho tài khoản đặc quyền.
    private static final Duration ADMIN_TOKEN_TTL = Duration.ofHours(12);
    private static final Duration PASSWORD_RESET_TOKEN_TTL = Duration.ofMinutes(15);
    private static final String TOKEN_ISSUER = "greytest";
    private static final String TOKEN_AUDIENCE = "greytest-api";
    private static final String PASSWORD_RESET_AUDIENCE = "greytest-password-reset";
    private static final String PASSWORD_RESET_FINGERPRINT_CLAIM = "pwdFp";

    public static final int MAX_EMAIL_LENGTH = 254;
    public static final int MIN_PASSWORD_LENGTH = 6;
    public static final int MAX_PASSWORD_LENGTH = 72;
    public static final int MIN_FULL_NAME_LENGTH = 2;
    public static final int MAX_FULL_NAME_LENGTH = 100;

    public static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    public static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[A-Za-z0-9])\\S{6,72}$");

    // Cấu hình chống tấn công dò mật khẩu (Brute-force Rate Limiting) và OTP quên mật khẩu
    public static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_LOCKOUT_WINDOW_MS = 5 * 60_000L; // 5 phút
    private static final long OTP_TTL_MS = 5 * 60_000L; // 5 phút
    private static final long OTP_RESEND_COOLDOWN_MS = 60_000L; // 60 giây giữa các lần gửi lại OTP
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final Pattern OTP_PATTERN = Pattern.compile("^\\d{6}$");

    private static final class LoginAttemptTracker {
        final AtomicInteger failedCount = new AtomicInteger(0);
        volatile long firstFailureAtMs = System.currentTimeMillis();
        volatile long lockedUntilMs = 0L;
    }

    private static final class OtpEntry {
        final String code;
        final long createdAtMs;
        final long expiresAtMs;
        final AtomicInteger attempts = new AtomicInteger(0);
        volatile boolean consumed = false;

        OtpEntry(String code, long createdAtMs, long expiresAtMs) {
            this.code = code;
            this.createdAtMs = createdAtMs;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final ConcurrentHashMap<String, LoginAttemptTracker> loginAttemptMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OtpEntry> passwordResetOtpMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> otpCooldownMap = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    private final AuthUserRepository userRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final byte[] tokenSecret;
    private final UserActivityService activityService;
    private final HttpServletRequest currentRequest;
    private final PasswordResetMailService mailService;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final com.greytest.security.waf.TrustedProxyResolver trustedProxyResolver;

    public AuthService(
            AuthUserRepository userRepository,
            @Value("${greytest.auth.token-secret}") String tokenSecret) {
        this(userRepository, tokenSecret, null, null, null, null, null);
    }

    public AuthService(
            AuthUserRepository userRepository,
            String tokenSecret,
            UserActivityService activityService,
            HttpServletRequest currentRequest) {
        this(userRepository, tokenSecret, activityService, currentRequest, null, null, null);
    }

    public AuthService(
            AuthUserRepository userRepository,
            String tokenSecret,
            UserActivityService activityService,
            HttpServletRequest currentRequest,
            PasswordResetMailService mailService) {
        this(userRepository, tokenSecret, activityService, currentRequest, mailService, null, null);
    }

    public AuthService(
            AuthUserRepository userRepository,
            String tokenSecret,
            UserActivityService activityService,
            HttpServletRequest currentRequest,
            PasswordResetMailService mailService,
            GoogleTokenVerifier googleTokenVerifier) {
        this(userRepository, tokenSecret, activityService, currentRequest, mailService, googleTokenVerifier, null);
    }

    @Autowired
    public AuthService(
            AuthUserRepository userRepository,
            @Value("${greytest.auth.token-secret}") String tokenSecret,
            @Autowired(required = false) UserActivityService activityService,
            @Autowired(required = false) HttpServletRequest currentRequest,
            @Autowired(required = false) PasswordResetMailService mailService,
            @Autowired(required = false) GoogleTokenVerifier googleTokenVerifier,
            @Autowired(required = false) com.greytest.security.waf.TrustedProxyResolver trustedProxyResolver) {
        if (tokenSecret == null || tokenSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("GREYTEST_AUTH_TOKEN_SECRET phải có ít nhất 32 bytes");
        }
        this.userRepository = userRepository;
        this.tokenSecret = tokenSecret.getBytes(StandardCharsets.UTF_8);
        this.activityService = activityService;
        this.currentRequest = currentRequest;
        this.mailService = mailService;
        this.googleTokenVerifier = googleTokenVerifier;
        this.trustedProxyResolver = trustedProxyResolver != null ? trustedProxyResolver : new com.greytest.security.waf.TrustedProxyResolver();
    }

    /** Trả về cấu hình Google Sign-In công khai để Frontend khởi tạo Google Identity Services. */
    public GoogleConfigResponse getGoogleConfig() {
        boolean enabled = googleTokenVerifier != null && googleTokenVerifier.isConfigured();
        String clientId = enabled ? googleTokenVerifier.getGoogleClientId() : "";
        return new GoogleConfigResponse(enabled, clientId);
    }

    /**
     * Đăng nhập bằng Google Identity Services (GIS): xác thực Google ID Token chuẩn RS256,
     * tự động tạo tài khoản USER gói FREE nếu email chưa tồn tại và cấp JWT phiên làm việc.
     * Lưu ý: Không gắn @Transactional bọc ngoài bước verifyIdToken để tránh giữ kết nối DB khi tải Google JWKS.
     */
    public LoginResponse googleLogin(GoogleLoginRequest request) {
        if (request == null || request.credential() == null || request.credential().isBlank()) {
            throw badRequest("Google ID token không được để trống");
        }
        if (googleTokenVerifier == null || !googleTokenVerifier.isConfigured()) {
            throw new AuthException(
                    "GOOGLE_AUTH_NOT_CONFIGURED",
                    "Chức năng đăng nhập Google chưa được cấu hình (thiếu GOOGLE_CLIENT_ID)",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        GoogleTokenVerifier.GoogleUserPayload googleUser = googleTokenVerifier.verifyIdToken(request.credential());
        String email = normalizeEmail(googleUser.email());
        validateEmailOnly(email);

        AuthUser user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            user = new AuthUser();
            user.setEmail(email);
            user.setFullName(normalizeGoogleFullName(googleUser.fullName()));
            // Cột password_hash bắt buộc NOT NULL trong DB; gán mật khẩu ngẫu nhiên không thể đoán
            user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setRole(UserRole.USER);
            user.setTier(com.greytest.entity.enums.UserTier.FREE);
            user.setEnabled(true);
            user.setEmailVerified(true);
            user = userRepository.save(user);
            recordLoginEvent(user, "GOOGLE_REGISTER");
        } else {
            if (!Boolean.TRUE.equals(user.getEnabled())) {
                throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
            }
            if (user.getRole() == UserRole.ADMIN) {
                throw new AuthException("ADMIN_LOGIN_REQUIRED",
                        "Tài khoản quản trị phải đăng nhập tại cổng đăng nhập Admin",
                        HttpStatus.FORBIDDEN);
            }
            if (claimByVerifiedEmail(user, passwordEncoder)) {
                user = userRepository.save(user);
            }
            recordLoginEvent(user, "GOOGLE_OAUTH");
        }

        return new LoginResponse(issueToken(user), toDto(user));
    }

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        if (request == null) {
            throw badRequest("Dữ liệu đăng ký không hợp lệ");
        }
        validateEmailAndPassword(request.email(), request.password());
        validateFullName(request.fullName());

        String email = normalizeEmail(request.email());
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new AuthException("EMAIL_EXISTS", "Email đã được sử dụng", HttpStatus.CONFLICT);
        }

        AuthUser user = new AuthUser();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);
        user.setEnabled(true);
        user = userRepository.save(user);
        recordLoginEvent(user, "REGISTER");
        return new LoginResponse(issueToken(user), toDto(user));
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        AuthUser user = authenticate(request);
        if (user.getRole() == UserRole.ADMIN) {
            throw new AuthException("ADMIN_LOGIN_REQUIRED",
                    "Tài khoản quản trị phải đăng nhập tại cổng đăng nhập Admin",
                    HttpStatus.FORBIDDEN);
        }
        recordLoginEvent(user, "USER_PORTAL");
        return new LoginResponse(issueToken(user), toDto(user));
    }

    @Transactional
    public LoginResponse adminLogin(LoginRequest request) {
        AuthUser user = authenticate(request);
        // Luồng quản trị chỉ cấp phiên sau khi xác nhận đúng role ADMIN.
        if (user.getRole() != UserRole.ADMIN) {
            throw new AuthException("ADMIN_ACCESS_DENIED", "Tài khoản không có quyền quản trị", HttpStatus.FORBIDDEN);
        }
        recordLoginEvent(user, "ADMIN_PORTAL");
        return new LoginResponse(issueToken(user), toDto(user));
    }

    /**
     * Khởi tạo yêu cầu quên mật khẩu: kiểm tra email hợp lệ và tồn tại, sinh mã OTP 6 chữ số (hiệu lực 5 phút)
     * và gửi về hộp thư Gmail của người dùng (hoặc trả fallback local khi SMTP chưa cấu hình).
     * Lưu ý: Không gắn @Transactional để quá trình gửi email SMTP không giữ kết nối DB.
     */
    public ForgotPasswordResponse forgotPassword(ForgotPasswordRequest request) {
        if (request == null) {
            throw badRequest("Dữ liệu yêu cầu không hợp lệ");
        }
        validateEmailOnly(request.email());
        String email = normalizeEmail(request.email());

        AuthUser user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AuthException(
                        "EMAIL_NOT_FOUND",
                        "Email này chưa được đăng ký trong hệ thống",
                        HttpStatus.NOT_FOUND));

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
        }
        if (user.getRole() == UserRole.ADMIN) {
            throw new AuthException("ADMIN_ACCESS_DENIED",
                    "Tài khoản quản trị không hỗ trợ đặt lại mật khẩu tại cổng người dùng",
                    HttpStatus.FORBIDDEN);
        }

        long now = System.currentTimeMillis();
        // Dọn dẹp OTP đã dùng hoặc đã hết hạn để tránh rò rỉ bộ nhớ
        passwordResetOtpMap.entrySet().removeIf(e -> e.getValue().consumed || now > e.getValue().expiresAtMs);
        otpCooldownMap.entrySet().removeIf(e -> (now - e.getValue()) >= OTP_RESEND_COOLDOWN_MS);

        Long lastRequestedAt = otpCooldownMap.get(email);
        if (lastRequestedAt != null && (now - lastRequestedAt) < OTP_RESEND_COOLDOWN_MS) {
            long waitSec = Math.max(1L, (OTP_RESEND_COOLDOWN_MS - (now - lastRequestedAt) + 999L) / 1000L);
            throw new AuthException(
                    "OTP_COOLDOWN",
                    "Vui lòng đợi " + waitSec + " giây trước khi yêu cầu gửi lại mã OTP",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        // Sinh mã OTP 6 chữ số có hiệu lực 5 phút, tối đa 5 lần nhập sai
        String otpCode = String.format("%06d", secureRandom.nextInt(1_000_000));
        OtpEntry otpEntry = new OtpEntry(otpCode, now, now + OTP_TTL_MS);
        passwordResetOtpMap.put(email, otpEntry);
        otpCooldownMap.put(email, now);

        boolean smtpConfigured = mailService != null && mailService.isSmtpConfigured();
        if (smtpConfigured) {
            boolean mailSent = mailService.sendPasswordResetOtpEmail(user.getEmail(), user.getFullName(), otpCode);
            if (!mailSent) {
                passwordResetOtpMap.remove(email, otpEntry);
                otpCooldownMap.remove(email);
                throw new AuthException(
                        "MAIL_SEND_FAILED",
                        "Không thể gửi email mã OTP lúc này. Vui lòng thử lại sau",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }
            return new ForgotPasswordResponse(
                    "Đã gửi mã OTP 6 chữ số tới " + user.getEmail() + ". Vui lòng kiểm tra hộp thư Gmail.",
                    true,
                    null,
                    null);
        }

        String resetToken = issuePasswordResetToken(user);
        String resetUrl = mailService != null
                ? mailService.buildResetUrl(resetToken)
                : "http://localhost:3000/reset-password?token=" + resetToken;
        if (mailService != null) {
            mailService.sendPasswordResetOtpEmail(user.getEmail(), user.getFullName(), otpCode);
        }
        return new ForgotPasswordResponse(
                "Hệ thống chưa cấu hình SMTP Gmail. Sử dụng mã OTP dự phòng bên dưới để đặt lại mật khẩu.",
                false,
                resetUrl,
                otpCode);
    }

    /**
     * Đặt lại mật khẩu mới bằng mã OTP 6 chữ số gửi qua Gmail (hoặc token từ đường dẫn email dự phòng).
     * Mã OTP và JWT đều chỉ dùng được đúng 1 lần và sẽ tự vô hiệu hóa sau khi đổi mật khẩu thành công.
     */
    @Transactional
    public PasswordActionResponse resetPassword(ResetPasswordRequest request) {
        if (request == null) {
            throw badRequest("Dữ liệu đặt lại mật khẩu không hợp lệ");
        }
        validatePasswordOnly(request.newPassword(), "Mật khẩu mới");
        if (request.confirmPassword() == null || !request.newPassword().equals(request.confirmPassword())) {
            throw badRequest("Xác nhận mật khẩu mới không khớp");
        }

        boolean hasOtp = request.otp() != null && !request.otp().isBlank();
        boolean hasToken = request.token() != null && !request.token().isBlank();

        AuthUser user;
        if (hasOtp) {
            user = verifyAndConsumeOtp(request.email(), request.otp().trim());
        } else if (hasToken) {
            user = verifyPasswordResetToken(request.token().trim());
        } else {
            throw badRequest("Vui lòng nhập mã OTP hoặc mã xác thực đặt lại mật khẩu");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        if (mailService != null && mailService.isSmtpConfigured()) {
            // Mã chỉ đến được hộp thư của chủ email nên coi là đã xác minh.
            user.setEmailVerified(true);
        }
        invalidateSessions(user);
        userRepository.save(user);

        String normalizedEmail = normalizeEmail(user.getEmail());
        if (hasToken) {
            passwordResetOtpMap.remove(normalizedEmail);
        }
        otpCooldownMap.remove(normalizedEmail);
        // Xóa khóa brute-force nếu tài khoản trước đó bị nhập sai nhiều lần
        loginAttemptMap.keySet().removeIf(key -> key.endsWith("|" + normalizedEmail));

        return new PasswordActionResponse("Đặt lại mật khẩu thành công. Bạn có thể đăng nhập bằng mật khẩu mới.");
    }

    /**
     * Nhà cung cấp OAuth vừa xác nhận chủ email. Nếu tài khoản được tạo bằng mật khẩu mà chưa xác minh email thì
     * người tạo có thể là kẻ chiếm chỗ: hủy mật khẩu và mọi phiên cũ. Trả true nếu tài khoản đã thay đổi.
     */
    static boolean claimByVerifiedEmail(AuthUser user, PasswordEncoder encoder) {
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            return false;
        }
        user.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));
        user.setEmailVerified(true);
        invalidateSessions(user);
        return true;
    }

    /** JWT chỉ lưu thời điểm cấp tới giây nên cắt phần lẻ để phiên mới cấp ngay sau đó vẫn dùng được. */
    private static void invalidateSessions(AuthUser user) {
        user.setSessionsValidFrom(java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    private AuthUser verifyAndConsumeOtp(String rawEmail, String otp) {
        validateEmailOnly(rawEmail);
        String normalizedEmail = normalizeEmail(rawEmail);
        if (!OTP_PATTERN.matcher(otp).matches()) {
            throw badRequest("Mã OTP phải gồm đúng 6 chữ số");
        }

        OtpEntry entry = passwordResetOtpMap.get(normalizedEmail);
        if (entry == null) {
            throw new AuthException(
                    "INVALID_OTP",
                    "Mã OTP không tồn tại hoặc đã hết hạn. Vui lòng gửi lại mã mới",
                    HttpStatus.BAD_REQUEST);
        }

        synchronized (entry) {
            if (entry.consumed) {
                throw new AuthException(
                        "INVALID_OTP",
                        "Mã OTP đã được sử dụng. Vui lòng gửi lại mã mới",
                        HttpStatus.BAD_REQUEST);
            }
            if (System.currentTimeMillis() > entry.expiresAtMs) {
                entry.consumed = true;
                passwordResetOtpMap.remove(normalizedEmail, entry);
                throw new AuthException(
                        "INVALID_OTP",
                        "Mã OTP đã hết hạn. Vui lòng gửi lại mã mới",
                        HttpStatus.BAD_REQUEST);
            }
            int attempts = entry.attempts.incrementAndGet();
            if (attempts > MAX_OTP_ATTEMPTS) {
                entry.consumed = true;
                passwordResetOtpMap.remove(normalizedEmail, entry);
                throw new AuthException(
                        "OTP_ATTEMPTS_EXCEEDED",
                        "Bạn đã nhập sai mã OTP quá 5 lần. Vui lòng yêu cầu mã OTP mới",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
            boolean matches = MessageDigest.isEqual(
                    entry.code.getBytes(StandardCharsets.UTF_8),
                    otp.getBytes(StandardCharsets.UTF_8));
            if (!matches) {
                if (attempts >= MAX_OTP_ATTEMPTS) {
                    entry.consumed = true;
                    passwordResetOtpMap.remove(normalizedEmail, entry);
                    throw new AuthException(
                            "OTP_ATTEMPTS_EXCEEDED",
                            "Bạn đã nhập sai mã OTP quá 5 lần. Vui lòng yêu cầu mã OTP mới",
                            HttpStatus.TOO_MANY_REQUESTS);
                }
                throw new AuthException(
                        "INVALID_OTP",
                        "Mã OTP không chính xác (còn " + (MAX_OTP_ATTEMPTS - attempts) + " lần thử)",
                        HttpStatus.BAD_REQUEST);
            }
            // Đánh dấu đã tiêu thụ trước khi rời synchronized để chặn race condition dùng lại OTP
            entry.consumed = true;
            passwordResetOtpMap.remove(normalizedEmail, entry);
        }

        AuthUser user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new AuthException(
                        "EMAIL_NOT_FOUND",
                        "Email này chưa được đăng ký trong hệ thống",
                        HttpStatus.NOT_FOUND));
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    private String normalizeGoogleFullName(String rawName) {
        String name = rawName != null ? rawName.trim() : "Google User";
        if (name.length() < MIN_FULL_NAME_LENGTH) {
            return "Google User";
        }
        return name.length() > MAX_FULL_NAME_LENGTH ? name.substring(0, MAX_FULL_NAME_LENGTH) : name;
    }

    /**
     * Đổi mật khẩu dành cho người dùng đang đăng nhập trong trang Hồ sơ cá nhân.
     */
    @Transactional
    public PasswordActionResponse changePassword(String authorizationHeader, ChangePasswordRequest request) {
        AuthUser user = currentUser(authorizationHeader);
        if (request == null) {
            throw badRequest("Dữ liệu đổi mật khẩu không hợp lệ");
        }
        if (request.currentPassword() == null || request.currentPassword().isBlank()) {
            throw badRequest("Mật khẩu hiện tại không được để trống");
        }
        validatePasswordOnly(request.newPassword(), "Mật khẩu mới");
        if (request.confirmPassword() == null || !request.newPassword().equals(request.confirmPassword())) {
            throw badRequest("Xác nhận mật khẩu mới không khớp");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new AuthException("INVALID_CURRENT_PASSWORD", "Mật khẩu hiện tại không chính xác", HttpStatus.BAD_REQUEST);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw badRequest("Mật khẩu mới không được trùng với mật khẩu hiện tại");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        return new PasswordActionResponse("Đổi mật khẩu thành công");
    }

    /**
     * Cập nhật thông tin cá nhân (họ tên) của người dùng đang đăng nhập.
     */
    @Transactional
    public AuthUserDto updateProfile(String authorizationHeader, UpdateProfileRequest request) {
        AuthUser user = currentUser(authorizationHeader);
        if (request == null || request.fullName() == null || request.fullName().trim().isEmpty()) {
            throw badRequest("Họ và tên không được để trống");
        }
        String trimmed = request.fullName().trim();
        if (trimmed.length() < MIN_FULL_NAME_LENGTH || trimmed.length() > MAX_FULL_NAME_LENGTH) {
            throw badRequest("Họ và tên phải từ " + MIN_FULL_NAME_LENGTH + " đến " + MAX_FULL_NAME_LENGTH + " ký tự");
        }
        user.setFullName(trimmed);
        user.setPhoneNumber(request.phoneNumber() != null && !request.phoneNumber().isBlank()
                ? request.phoneNumber().trim() : null);
        user.setGender(request.gender() != null && !request.gender().isBlank()
                ? request.gender().trim() : null);
        user.setDateOfBirth(request.dateOfBirth());
        AuthUser saved = userRepository.save(user);
        if (activityService != null) {
            try {
                String ip = currentRequest != null ? resolveCurrentClientIp() : "127.0.0.1";
                activityService.record(saved.getId(), ActivityAction.USER_UPDATE_PROFILE, null, Map.of(
                        "ip", ip != null ? ip : "127.0.0.1",
                        "fullName", trimmed,
                        "status", "SUCCESS"
                ));
            } catch (Exception ignored) {}
        }
        return toDto(saved);
    }

    void recordLoginEvent(AuthUser user, String portal) {
        if (activityService == null || user == null || user.getId() == null) return;
        try {
            String ip = currentRequest != null ? resolveCurrentClientIp() : "127.0.0.1";
            String userAgent = "Web Client";
            if (currentRequest != null) {
                String ua = currentRequest.getHeader("User-Agent");
                if (ua != null && !ua.isBlank()) userAgent = ua;
            }
            activityService.record(user.getId(), ActivityAction.USER_LOGIN, null, Map.of(
                    "ip", ip != null ? ip : "127.0.0.1",
                    "userAgent", userAgent != null ? userAgent : "Browser",
                    "portal", portal,
                    "status", "SUCCESS"
            ));
        } catch (Exception ignored) {
            // Không làm gián đoạn luồng login nếu ghi log xảy ra sự cố
        }
    }

    @Transactional(readOnly = true)
    public AuthUser currentUser(String authorizationHeader) {
        BearerToken token = parseBearerToken(authorizationHeader)
                .orElseThrow(() -> unauthorized("Token không hợp lệ hoặc đã hết hạn"));
        AuthUser user = userRepository.findById(token.userId())
                .orElseThrow(() -> unauthorized("Người dùng không tồn tại"));
        if (user.getSessionsValidFrom() != null && token.issuedAt().toInstant()
                .isBefore(user.getSessionsValidFrom().atZone(java.time.ZoneId.systemDefault()).toInstant())) {
            throw unauthorized("Phiên đăng nhập đã bị hủy. Vui lòng đăng nhập lại");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    public AuthUserDto toDto(AuthUser user) {
        return new AuthUserDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getTier() != null ? user.getTier() : com.greytest.entity.enums.UserTier.FREE,
                user.getPhoneNumber(),
                user.getGender(),
                user.getDateOfBirth(),
                user.getGithubUsername());
    }

    private static Duration tokenTtl(AuthUser user) {
        return user.getRole() == UserRole.ADMIN ? ADMIN_TOKEN_TTL : TOKEN_TTL;
    }

    public String issueToken(AuthUser user) {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .issuer(TOKEN_ISSUER)
                .audience(TOKEN_AUDIENCE)
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + tokenTtl(user).toMillis()))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new MACSigner(tokenSecret));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Khong tao duoc JWT dang nhap", exception);
        }
    }

    private record BearerToken(long userId, Date issuedAt) {
    }

    private Optional<BearerToken> parseBearerToken(String authorizationHeader) {
        try {
            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                return Optional.empty();
            }
            String token = authorizationHeader.substring("Bearer ".length()).trim();
            if (token.length() > 4096) return Optional.empty();
            SignedJWT jwt = SignedJWT.parse(token);
            JWSHeader header = jwt.getHeader();
            // Chỉ nhận hợp đồng JWT của ứng dụng, không chọn thuật toán theo token gửi lên.
            if (!JWSAlgorithm.HS256.equals(header.getAlgorithm())
                    || !JOSEObjectType.JWT.equals(header.getType())
                    || !header.isBase64URLEncodePayload()
                    || (header.getCriticalParams() != null && !header.getCriticalParams().isEmpty())
                    || !jwt.verify(new MACVerifier(tokenSecret))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date now = new Date();
            Date expiresAt = claims.getExpirationTime();
            Date issuedAt = claims.getIssueTime();
            if (!TOKEN_ISSUER.equals(claims.getIssuer())
                    || !List.of(TOKEN_AUDIENCE).equals(claims.getAudience())
                    || expiresAt == null || !expiresAt.after(now)
                    || issuedAt == null || issuedAt.after(now) || !issuedAt.before(expiresAt)
                    || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().after(now))) {
                return Optional.empty();
            }
            long userId = Long.parseLong(claims.getSubject());
            return userId > 0 ? Optional.of(new BearerToken(userId, issuedAt)) : Optional.empty();
        } catch (ParseException | JOSEException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private AuthException unauthorized(String message) {
        return new AuthException("AUTH_ERROR", message, HttpStatus.UNAUTHORIZED);
    }

    private AuthException badRequest(String message) {
        return new AuthException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }

    private AuthUser authenticate(LoginRequest request) {
        if (request == null) {
            throw badRequest("Dữ liệu đăng nhập không hợp lệ");
        }
        validateEmailAndPassword(request.email(), request.password());

        String normalizedEmail = normalizeEmail(request.email());
        String attemptKey = buildLoginAttemptKey(normalizedEmail);
        checkBruteForceLockout(attemptKey);

        Optional<AuthUser> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);
        boolean isAdmin = userOpt.isPresent() && userOpt.get().getRole() == UserRole.ADMIN;
        if (isAdmin) {
            checkLockoutKey("admin_acc:" + normalizedEmail);
        }

        if (userOpt.isEmpty() || !passwordEncoder.matches(request.password(), userOpt.get().getPasswordHash())) {
            recordFailedLoginAttempt(attemptKey, normalizedEmail, isAdmin);
            throw unauthorized("Email hoặc mật khẩu không đúng");
        }

        AuthUser user = userOpt.get();
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
        }

        clearFailedLoginAttempts(attemptKey, normalizedEmail);
        return user;
    }

    private void validateEmailAndPassword(String rawEmail, String rawPassword) {
        validateEmailOnly(rawEmail);
        validatePasswordOnly(rawPassword, "Mật khẩu");
    }

    private void validateEmailOnly(String rawEmail) {
        if (rawEmail == null || rawEmail.trim().isEmpty()) {
            throw badRequest("Email không được để trống");
        }
        String trimmedEmail = rawEmail.trim();
        if (trimmedEmail.length() > MAX_EMAIL_LENGTH) {
            throw badRequest("Email không được vượt quá " + MAX_EMAIL_LENGTH + " ký tự");
        }
        if (!EMAIL_PATTERN.matcher(trimmedEmail).matches()) {
            throw badRequest("Email không đúng định dạng");
        }
    }

    private void validatePasswordOnly(String rawPassword, String fieldLabel) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw badRequest(fieldLabel + " không được để trống");
        }
        if (rawPassword.length() < MIN_PASSWORD_LENGTH || rawPassword.length() > MAX_PASSWORD_LENGTH) {
            throw badRequest(fieldLabel + " phải từ " + MIN_PASSWORD_LENGTH + " đến " + MAX_PASSWORD_LENGTH + " ký tự");
        }
        if (!PASSWORD_PATTERN.matcher(rawPassword).matches()) {
            throw badRequest(fieldLabel + " phải từ 6 đến 72 ký tự và không chứa khoảng trắng");
        }
    }

    public String issuePasswordResetToken(AuthUser user) {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .issuer(TOKEN_ISSUER)
                .audience(PASSWORD_RESET_AUDIENCE)
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + PASSWORD_RESET_TOKEN_TTL.toMillis()))
                .jwtID(UUID.randomUUID().toString())
                .claim(PASSWORD_RESET_FINGERPRINT_CLAIM, passwordHashFingerprint(user.getPasswordHash()))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new MACSigner(tokenSecret));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Không tạo được token đặt lại mật khẩu", exception);
        }
    }

    private AuthUser verifyPasswordResetToken(String token) {
        try {
            if (token == null || token.isBlank() || token.length() > 4096) {
                throw invalidResetToken();
            }
            SignedJWT jwt = SignedJWT.parse(token);
            JWSHeader header = jwt.getHeader();
            if (!JWSAlgorithm.HS256.equals(header.getAlgorithm())
                    || !JOSEObjectType.JWT.equals(header.getType())
                    || !header.isBase64URLEncodePayload()
                    || (header.getCriticalParams() != null && !header.getCriticalParams().isEmpty())
                    || !jwt.verify(new MACVerifier(tokenSecret))) {
                throw invalidResetToken();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date now = new Date();
            Date expiresAt = claims.getExpirationTime();
            Date issuedAt = claims.getIssueTime();
            if (!TOKEN_ISSUER.equals(claims.getIssuer())
                    || !List.of(PASSWORD_RESET_AUDIENCE).equals(claims.getAudience())
                    || expiresAt == null || !expiresAt.after(now)
                    || issuedAt == null || issuedAt.after(now) || !issuedAt.before(expiresAt)) {
                throw invalidResetToken();
            }
            long userId = Long.parseLong(claims.getSubject());
            AuthUser user = userRepository.findById(userId).orElseThrow(this::invalidResetToken);
            if (!Boolean.TRUE.equals(user.getEnabled())) {
                throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
            }
            String expectedFp = passwordHashFingerprint(user.getPasswordHash());
            String tokenFp = claims.getStringClaim(PASSWORD_RESET_FINGERPRINT_CLAIM);
            if (tokenFp == null || !MessageDigest.isEqual(
                    expectedFp.getBytes(StandardCharsets.UTF_8),
                    tokenFp.getBytes(StandardCharsets.UTF_8))) {
                throw invalidResetToken();
            }
            return user;
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalidResetToken();
        }
    }

    private AuthException invalidResetToken() {
        return new AuthException(
                "INVALID_RESET_TOKEN",
                "Đường dẫn đặt lại mật khẩu không hợp lệ, đã hết hạn hoặc đã được sử dụng",
                HttpStatus.BAD_REQUEST);
    }

    private static String passwordHashFingerprint(String passwordHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((passwordHash != null ? passwordHash : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Không khởi tạo được SHA-256", ex);
        }
    }

    private void validateFullName(String rawFullName) {
        if (rawFullName == null || rawFullName.trim().isEmpty()) {
            throw badRequest("Họ tên không được để trống");
        }
        String trimmed = rawFullName.trim();
        if (trimmed.length() < MIN_FULL_NAME_LENGTH || trimmed.length() > MAX_FULL_NAME_LENGTH) {
            throw badRequest("Họ tên phải từ " + MIN_FULL_NAME_LENGTH + " đến " + MAX_FULL_NAME_LENGTH + " ký tự");
        }
    }

    private String resolveCurrentClientIp() {
        if (currentRequest == null) {
            return "127.0.0.1";
        }
        try {
            if (trustedProxyResolver != null) {
                return trustedProxyResolver.resolveClientIp(currentRequest);
            }
            String remote = currentRequest.getRemoteAddr();
            if (remote != null && !remote.isBlank()) {
                return remote.trim();
            }
        } catch (Exception ignored) {
            // Trong môi trường unit test hoặc ngoài request scope thì mặc định theo loopback IP
        }
        return "127.0.0.1";
    }

    private static final int MAX_LOGIN_ATTEMPTS_TRACKED = 5_000;
    private final AtomicLong lastLoginCleanupMs = new AtomicLong(0);

    private boolean isTrustedOrLoopback(String ip) {
        if (ip == null || ip.isBlank()) return true;
        if (trustedProxyResolver != null && trustedProxyResolver.isTrustedProxy(ip)) {
            return true;
        }
        return DdosProtectionService.isLoopback(ip);
    }

    private String buildLoginAttemptKey(String normalizedEmail) {
        return resolveCurrentClientIp() + "|" + normalizedEmail;
    }

    private void checkBruteForceLockout(String attemptKey) {
        checkLockoutKey(attemptKey);
        String ip = resolveCurrentClientIp();
        // Không khóa toàn bộ IP nếu IP là Trusted Proxy hoặc Loopback để tránh DoS toàn hệ thống khi chạy sau reverse proxy (Finding SEC-P5-01)
        if (!isTrustedOrLoopback(ip)) {
            checkLockoutKey("ip:" + ip);
        }
    }

    private void checkLockoutKey(String key) {
        LoginAttemptTracker tracker = loginAttemptMap.get(key);
        if (tracker == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (tracker.lockedUntilMs > now) {
            long retryAfterSec = Math.max(1L, (tracker.lockedUntilMs - now + 999L) / 1000L);
            throw new AuthException(
                    "TOO_MANY_LOGIN_ATTEMPTS",
                    "Quá nhiều lần đăng nhập thất bại. Vui lòng thử lại sau " + retryAfterSec + " giây",
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
        if (now - tracker.firstFailureAtMs > LOGIN_LOCKOUT_WINDOW_MS) {
            loginAttemptMap.remove(key, tracker);
        }
    }

    private void cleanupLoginAttemptsIfFull() {
        if (loginAttemptMap.size() > MAX_LOGIN_ATTEMPTS_TRACKED) {
            long now = System.currentTimeMillis();
            long last = lastLoginCleanupMs.get();
            if (now - last > 5_000L && lastLoginCleanupMs.compareAndSet(last, now)) {
                loginAttemptMap.entrySet().removeIf(e -> e.getValue().lockedUntilMs <= now
                        && now - e.getValue().firstFailureAtMs > LOGIN_LOCKOUT_WINDOW_MS);
            }
        }
    }

    private void recordFailedLoginAttempt(String attemptKey) {
        recordFailedLoginAttempt(attemptKey, null, false);
    }

    private void recordFailedLoginAttempt(String attemptKey, String normalizedEmail, boolean isAdmin) {
        cleanupLoginAttemptsIfFull();
        recordFailedKey(attemptKey);
        String ip = resolveCurrentClientIp();
        // Chỉ ghi nhận khóa per-IP cho untrusted client IP ngoài Internet; không khóa proxy/loopback
        if (!isTrustedOrLoopback(ip)) {
            recordFailedKey("ip:" + ip);
        }
        if (isAdmin && normalizedEmail != null) {
            recordFailedKey("admin_acc:" + normalizedEmail);
        }
    }

    private void recordFailedKey(String key) {
        long now = System.currentTimeMillis();
        LoginAttemptTracker tracker = loginAttemptMap.compute(key, (k, existing) -> {
            if (existing == null || (now - existing.firstFailureAtMs > LOGIN_LOCKOUT_WINDOW_MS && existing.lockedUntilMs <= now)) {
                LoginAttemptTracker fresh = new LoginAttemptTracker();
                fresh.firstFailureAtMs = now;
                fresh.failedCount.set(1);
                return fresh;
            }
            int failures = existing.failedCount.incrementAndGet();
            if (failures >= MAX_FAILED_LOGIN_ATTEMPTS) {
                existing.lockedUntilMs = now + LOGIN_LOCKOUT_WINDOW_MS;
            }
            return existing;
        });

        if (tracker.lockedUntilMs > now) {
            long retryAfterSec = Math.max(1L, (tracker.lockedUntilMs - now + 999L) / 1000L);
            throw new AuthException(
                    "TOO_MANY_LOGIN_ATTEMPTS",
                    "Quá nhiều lần đăng nhập thất bại. Vui lòng thử lại sau " + retryAfterSec + " giây",
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
    }

    private void clearFailedLoginAttempts(String attemptKey) {
        clearFailedLoginAttempts(attemptKey, null);
    }

    private void clearFailedLoginAttempts(String attemptKey, String normalizedEmail) {
        loginAttemptMap.remove(attemptKey);
        if (normalizedEmail != null) {
            loginAttemptMap.remove("admin_acc:" + normalizedEmail);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
