package com.greytest.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    // Cấu hình chống tấn công dò mật khẩu (Brute-force Rate Limiting)
    public static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_LOCKOUT_WINDOW_MS = 5 * 60_000L; // 5 phút

    private static final class LoginAttemptTracker {
        final AtomicInteger failedCount = new AtomicInteger(0);
        volatile long firstFailureAtMs = System.currentTimeMillis();
        volatile long lockedUntilMs = 0L;
    }

    private final ConcurrentHashMap<String, LoginAttemptTracker> loginAttemptMap = new ConcurrentHashMap<>();

    private final AuthUserRepository userRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final byte[] tokenSecret;
    private final UserActivityService activityService;
    private final HttpServletRequest currentRequest;
    private final PasswordResetMailService mailService;

    public AuthService(
            AuthUserRepository userRepository,
            @Value("${greytest.auth.token-secret}") String tokenSecret) {
        this(userRepository, tokenSecret, null, null, null);
    }

    public AuthService(
            AuthUserRepository userRepository,
            String tokenSecret,
            UserActivityService activityService,
            HttpServletRequest currentRequest) {
        this(userRepository, tokenSecret, activityService, currentRequest, null);
    }

    @Autowired
    public AuthService(
            AuthUserRepository userRepository,
            @Value("${greytest.auth.token-secret}") String tokenSecret,
            @Autowired(required = false) UserActivityService activityService,
            @Autowired(required = false) HttpServletRequest currentRequest,
            @Autowired(required = false) PasswordResetMailService mailService) {
        if (tokenSecret == null || tokenSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("GREYTEST_AUTH_TOKEN_SECRET phải có ít nhất 32 bytes");
        }
        this.userRepository = userRepository;
        this.tokenSecret = tokenSecret.getBytes(StandardCharsets.UTF_8);
        this.activityService = activityService;
        this.currentRequest = currentRequest;
        this.mailService = mailService;
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
     * Khởi tạo yêu cầu quên mật khẩu: kiểm tra email hợp lệ và tồn tại, sinh mã JWT đặt lại mật khẩu
     * có hiệu lực 15 phút (tự hủy ngay sau khi mật khẩu thay đổi) và gửi về hộp thư Gmail của người dùng.
     */
    @Transactional(readOnly = true)
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

        String resetToken = issuePasswordResetToken(user);
        String resetUrl = mailService != null
                ? mailService.buildResetUrl(resetToken)
                : "http://localhost:3000/reset-password?token=" + resetToken;

        boolean mailSent = mailService != null
                && mailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(), resetUrl);

        if (mailSent) {
            return new ForgotPasswordResponse(
                    "Đã gửi email hướng dẫn đặt lại mật khẩu tới " + user.getEmail() + ". Vui lòng kiểm tra hộp thư Gmail.",
                    true,
                    null);
        }
        return new ForgotPasswordResponse(
                "Đã tạo đường dẫn đặt lại mật khẩu cho " + user.getEmail() + ". Bạn có thể mở đường dẫn bên dưới để đặt mật khẩu mới.",
                false,
                resetUrl);
    }

    /**
     * Đặt lại mật khẩu mới từ đường dẫn xác thực gửi qua email.
     * Mã JWT đặt lại mật khẩu gắn kèm dấu vân tay SHA-256 của passwordHash hiện tại nên chỉ dùng được đúng 1 lần.
     */
    @Transactional
    public PasswordActionResponse resetPassword(ResetPasswordRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw badRequest("Mã xác thực đặt lại mật khẩu không được để trống");
        }
        validatePasswordOnly(request.newPassword(), "Mật khẩu mới");
        if (request.confirmPassword() == null || !request.newPassword().equals(request.confirmPassword())) {
            throw badRequest("Xác nhận mật khẩu mới không khớp");
        }

        AuthUser user = verifyPasswordResetToken(request.token().trim());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Xóa khóa brute-force nếu tài khoản trước đó bị nhập sai nhiều lần
        String normalizedEmail = normalizeEmail(user.getEmail());
        loginAttemptMap.keySet().removeIf(key -> key.endsWith("|" + normalizedEmail));

        return new PasswordActionResponse("Đặt lại mật khẩu thành công. Bạn có thể đăng nhập bằng mật khẩu mới.");
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
                String ip = "127.0.0.1";
                if (currentRequest != null) {
                    String forwarded = currentRequest.getHeader("X-Forwarded-For");
                    ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : currentRequest.getRemoteAddr();
                }
                activityService.record(saved.getId(), ActivityAction.USER_UPDATE_PROFILE, null, Map.of(
                        "ip", ip != null ? ip : "127.0.0.1",
                        "fullName", trimmed,
                        "status", "SUCCESS"
                ));
            } catch (Exception ignored) {}
        }
        return toDto(saved);
    }

    private void recordLoginEvent(AuthUser user, String portal) {
        if (activityService == null || user == null || user.getId() == null) return;
        try {
            String ip = "127.0.0.1";
            String userAgent = "Web Client";
            if (currentRequest != null) {
                String forwarded = currentRequest.getHeader("X-Forwarded-For");
                ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : currentRequest.getRemoteAddr();
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
        Long userId = parseBearerToken(authorizationHeader)
                .orElseThrow(() -> unauthorized("Token không hợp lệ hoặc đã hết hạn"));
        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> unauthorized("Người dùng không tồn tại"));
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
                user.getDateOfBirth());
    }

    private String issueToken(AuthUser user) {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .issuer(TOKEN_ISSUER)
                .audience(TOKEN_AUDIENCE)
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + TOKEN_TTL.toMillis()))
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

    private Optional<Long> parseBearerToken(String authorizationHeader) {
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
            return userId > 0 ? Optional.of(userId) : Optional.empty();
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
        if (userOpt.isEmpty() || !passwordEncoder.matches(request.password(), userOpt.get().getPasswordHash())) {
            recordFailedLoginAttempt(attemptKey);
            throw unauthorized("Email hoặc mật khẩu không đúng");
        }

        AuthUser user = userOpt.get();
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
        }

        clearFailedLoginAttempts(attemptKey);
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

    private String buildLoginAttemptKey(String normalizedEmail) {
        String ip = "127.0.0.1";
        try {
            if (currentRequest != null) {
                String remote = currentRequest.getRemoteAddr();
                if (remote != null && !remote.isBlank()) {
                    ip = remote.trim();
                }
            }
        } catch (Exception ignored) {
            // Trong môi trường unit test hoặc ngoài request scope thì mặc định theo loopback IP
        }
        return ip + "|" + normalizedEmail;
    }

    private void checkBruteForceLockout(String attemptKey) {
        LoginAttemptTracker tracker = loginAttemptMap.get(attemptKey);
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
            loginAttemptMap.remove(attemptKey, tracker);
        }
    }

    private void recordFailedLoginAttempt(String attemptKey) {
        long now = System.currentTimeMillis();
        LoginAttemptTracker tracker = loginAttemptMap.compute(attemptKey, (key, existing) -> {
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
        loginAttemptMap.remove(attemptKey);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
