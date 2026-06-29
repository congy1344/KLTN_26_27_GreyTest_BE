package com.greytest.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.AuthUserDto;
import com.greytest.dto.LoginRequest;
import com.greytest.dto.LoginResponse;
import com.greytest.dto.RegisterRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;

@Service
public class AuthService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Duration TOKEN_TTL = Duration.ofDays(7);

    private final AuthUserRepository userRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String tokenSecret;

    public AuthService(
            AuthUserRepository userRepository,
            @Value("${greytest.auth.token-secret:dev-greytest-token-secret-change-me}") String tokenSecret) {
        this.userRepository = userRepository;
        this.tokenSecret = tokenSecret;
    }

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new AuthException("Email da duoc su dung");
        }

        AuthUser user = new AuthUser();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);
        user.setEnabled(true);
        user = userRepository.save(user);
        return new LoginResponse(issueToken(user), toDto(user));
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        AuthUser user = userRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
                .orElseThrow(() -> new AuthException("Email hoac mat khau khong dung"));
        if (!Boolean.TRUE.equals(user.getEnabled())
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new AuthException("Email hoac mat khau khong dung");
        }
        return new LoginResponse(issueToken(user), toDto(user));
    }

    @Transactional(readOnly = true)
    public AuthUser currentUser(String authorizationHeader) {
        Long userId = parseBearerToken(authorizationHeader)
                .orElseThrow(() -> new AuthException("Token khong hop le hoac da het han"));
        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Nguoi dung khong ton tai"));
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException("Tai khoan da bi vo hieu hoa");
        }
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<AuthUser> optionalCurrentUser(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(currentUser(authorizationHeader));
    }

    public AuthUserDto toDto(AuthUser user) {
        return new AuthUserDto(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }

    private String issueToken(AuthUser user) {
        long expiresAt = System.currentTimeMillis() + TOKEN_TTL.toMillis();
        String payload = user.getId() + ":" + expiresAt;
        String encodedPayload = base64Url(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);
        return encodedPayload + "." + signature;
    }

    private Optional<Long> parseBearerToken(String authorizationHeader) {
        try {
            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                return Optional.empty();
            }
            String token = authorizationHeader.substring("Bearer ".length()).trim();
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2 || !constantTimeEquals(sign(parts[0]), parts[1])) {
                return Optional.empty();
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] payloadParts = payload.split(":", 2);
            if (payloadParts.length != 2) {
                return Optional.empty();
            }
            long expiresAt = Long.parseLong(payloadParts[1]);
            if (expiresAt < System.currentTimeMillis()) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(payloadParts[0]));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return base64Url(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AuthException("Khong tao duoc token dang nhap");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) return false;
        int result = 0;
        for (int i = 0; i < left.length; i++) {
            result |= left[i] ^ right[i];
        }
        return result == 0;
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
