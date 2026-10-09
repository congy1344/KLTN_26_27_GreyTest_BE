package com.greytest.service;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
    private static final String TOKEN_ISSUER = "greytest";
    private static final String TOKEN_AUDIENCE = "greytest-api";

    private final AuthUserRepository userRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final byte[] tokenSecret;

    public AuthService(
            AuthUserRepository userRepository,
            @Value("${greytest.auth.token-secret}") String tokenSecret) {
        if (tokenSecret == null || tokenSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("GREYTEST_AUTH_TOKEN_SECRET phai co it nhat 32 bytes");
        }
        this.userRepository = userRepository;
        this.tokenSecret = tokenSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new AuthException("EMAIL_EXISTS", "Email da duoc su dung", HttpStatus.CONFLICT);
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
        AuthUser user = authenticate(request);
        if (user.getRole() == UserRole.ADMIN) {
            throw new AuthException("ADMIN_LOGIN_REQUIRED",
                    "Tai khoan quan tri phai dang nhap tai cong dang nhap Admin",
                    HttpStatus.FORBIDDEN);
        }
        return new LoginResponse(issueToken(user), toDto(user));
    }

    @Transactional(readOnly = true)
    public LoginResponse adminLogin(LoginRequest request) {
        AuthUser user = authenticate(request);
        // Luồng quản trị chỉ cấp phiên sau khi xác nhận đúng role ADMIN.
        if (user.getRole() != UserRole.ADMIN) {
            throw new AuthException("ADMIN_ACCESS_DENIED", "Tai khoan khong co quyen quan tri", HttpStatus.FORBIDDEN);
        }
        return new LoginResponse(issueToken(user), toDto(user));
    }

    @Transactional(readOnly = true)
    public AuthUser currentUser(String authorizationHeader) {
        Long userId = parseBearerToken(authorizationHeader)
                .orElseThrow(() -> unauthorized("Token khong hop le hoac da het han"));
        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> unauthorized("Nguoi dung khong ton tai"));
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    public AuthUserDto toDto(AuthUser user) {
        return new AuthUserDto(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getTier() != null ? user.getTier() : com.greytest.entity.enums.UserTier.FREE);
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

    private AuthUser authenticate(LoginRequest request) {
        AuthUser user = userRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
                .orElseThrow(() -> unauthorized("Email hoac mat khau khong dung"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw unauthorized("Email hoac mat khau khong dung");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
