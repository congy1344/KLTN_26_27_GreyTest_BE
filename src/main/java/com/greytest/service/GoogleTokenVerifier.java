package com.greytest.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.greytest.exception.AuthException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Service xác thực Google ID Token (chuẩn OpenID Connect RS256) nhận từ Google Identity Services (GIS).
 * Thực hiện kiểm tra chữ ký RSA bằng Google JWKS công khai, issuer, audience ({@code GOOGLE_CLIENT_ID}),
 * thời hạn token và trạng thái xác minh email của Google trước khi cho phép đăng nhập hệ thống.
 */
@Service
public class GoogleTokenVerifier {

    private static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> VALID_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final long JWKS_CACHE_TTL_MS = Duration.ofHours(1).toMillis();
    private static final long JWKS_REFRESH_COOLDOWN_MS = Duration.ofSeconds(60).toMillis();

    /** Thông tin người dùng trích xuất từ Google ID Token hợp lệ. */
    public record GoogleUserPayload(String email, String fullName, String subject) {
    }

    private final String googleClientId;
    private final JWKSet fixedJwkSet;
    private final HttpClient httpClient;

    private volatile JWKSet cachedJwkSet;
    private volatile long jwkCachedAtMs;

    @Autowired
    public GoogleTokenVerifier(@Value("${greytest.auth.google-client-id:}") String googleClientId) {
        this.googleClientId = googleClientId != null ? googleClientId.trim() : "";
        this.fixedJwkSet = null;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Constructor phục vụ unit test offline với bộ khóa RSA JWKSet giả lập. */
    public GoogleTokenVerifier(String googleClientId, JWKSet customJwkSet) {
        this.googleClientId = googleClientId != null ? googleClientId.trim() : "";
        this.fixedJwkSet = customJwkSet;
        this.httpClient = null;
    }

    public boolean isConfigured() {
        return !googleClientId.isEmpty();
    }

    public String getGoogleClientId() {
        return googleClientId;
    }

    /**
     * Xác thực chữ ký và các claim bắt buộc của Google ID Token.
     */
    public GoogleUserPayload verifyIdToken(String idToken) {
        if (!isConfigured()) {
            throw new AuthException(
                    "GOOGLE_AUTH_NOT_CONFIGURED",
                    "Chức năng đăng nhập Google chưa được cấu hình (thiếu GOOGLE_CLIENT_ID)",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (idToken == null || idToken.isBlank() || idToken.length() > 8192) {
            throw invalidGoogleToken();
        }

        try {
            SignedJWT jwt = SignedJWT.parse(idToken.trim());
            JWSHeader header = jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())) {
                throw invalidGoogleToken();
            }

            if (!verifySignature(jwt, header.getKeyID())) {
                throw invalidGoogleToken();
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date now = new Date();
            Date expiresAt = claims.getExpirationTime();
            List<String> audience = claims.getAudience();
            String subject = claims.getSubject();
            if (!VALID_ISSUERS.contains(claims.getIssuer())
                    || audience == null || !audience.contains(googleClientId)
                    || (audience.size() > 1 && !googleClientId.equals(claims.getStringClaim("azp")))
                    || subject == null || subject.isBlank()
                    || expiresAt == null || !expiresAt.after(now)) {
                throw invalidGoogleToken();
            }

            Boolean emailVerified = claims.getBooleanClaim("email_verified");
            String email = claims.getStringClaim("email");
            if (!Boolean.TRUE.equals(emailVerified) || email == null || email.isBlank()) {
                throw new AuthException(
                        "GOOGLE_EMAIL_UNVERIFIED",
                        "Tài khoản Google chưa xác minh địa chỉ email",
                        HttpStatus.UNAUTHORIZED);
            }

            String fullName = claims.getStringClaim("name");
            if (fullName == null || fullName.isBlank()) {
                int atIndex = email.indexOf('@');
                fullName = atIndex > 0 ? email.substring(0, atIndex) : "Google User";
            }

            return new GoogleUserPayload(email.trim().toLowerCase(), fullName.trim(), subject);
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalidGoogleToken();
        }
    }

    private boolean verifySignature(SignedJWT jwt, String keyId) throws Exception {
        if (keyId == null || keyId.isBlank()) {
            return false;
        }
        JWKSet jwkSet = getJwkSet(false);
        if (matchesRsaKey(jwt, jwkSet, keyId)) {
            return true;
        }
        // Chỉ làm mới JWKS nếu kid chưa có trong cache và đã qua cooldown chống spam request tới Google
        if (fixedJwkSet == null
                && !containsKeyId(jwkSet, keyId)
                && (System.currentTimeMillis() - jwkCachedAtMs) >= JWKS_REFRESH_COOLDOWN_MS) {
            JWKSet refreshed = getJwkSet(true);
            return matchesRsaKey(jwt, refreshed, keyId);
        }
        return false;
    }

    private boolean containsKeyId(JWKSet jwkSet, String keyId) {
        if (jwkSet == null || keyId == null) {
            return false;
        }
        for (JWK jwk : jwkSet.getKeys()) {
            if (keyId.equals(jwk.getKeyID())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesRsaKey(SignedJWT jwt, JWKSet jwkSet, String keyId) throws Exception {
        if (jwkSet == null || keyId == null) {
            return false;
        }
        for (JWK jwk : jwkSet.getKeys()) {
            if (jwk instanceof RSAKey rsaKey && keyId.equals(rsaKey.getKeyID())) {
                if (jwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private JWKSet getJwkSet(boolean forceRefresh) throws Exception {
        if (fixedJwkSet != null) {
            return fixedJwkSet;
        }
        long now = System.currentTimeMillis();
        if (!forceRefresh && cachedJwkSet != null && (now - jwkCachedAtMs) < JWKS_CACHE_TTL_MS) {
            return cachedJwkSet;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (cachedJwkSet != null && (!forceRefresh || (now - jwkCachedAtMs) < JWKS_REFRESH_COOLDOWN_MS)
                    && (now - jwkCachedAtMs) < JWKS_CACHE_TTL_MS) {
                return cachedJwkSet;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GOOGLE_JWKS_URI))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AuthException(
                        "GOOGLE_JWKS_UNAVAILABLE",
                        "Không thể kết nối tới máy chủ xác thực Google",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }
            cachedJwkSet = JWKSet.parse(response.body());
            jwkCachedAtMs = System.currentTimeMillis();
            return cachedJwkSet;
        }
    }

    private AuthException invalidGoogleToken() {
        return new AuthException(
                "INVALID_GOOGLE_TOKEN",
                "Phiên đăng nhập Google không hợp lệ hoặc đã hết hạn",
                HttpStatus.UNAUTHORIZED);
    }
}
