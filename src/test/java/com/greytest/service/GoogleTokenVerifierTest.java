package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.greytest.exception.AuthException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class GoogleTokenVerifierTest {

    private static final String CLIENT_ID = "1234567890-test.apps.googleusercontent.com";
    private RSAKey rsaKey;
    private GoogleTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("google-test-kid").generate();
        JWKSet jwkSet = new JWKSet(rsaKey.toPublicJWK());
        verifier = new GoogleTokenVerifier(CLIENT_ID, jwkSet);
    }

    @Test
    void verifiesValidGoogleIdTokenSignedWithRs256() throws Exception {
        String idToken = signToken(baseClaims().build());

        var payload = verifier.verifyIdToken(idToken);

        assertThat(payload.email()).isEqualTo("student@gmail.com");
        assertThat(payload.fullName()).isEqualTo("Nguyen Van A");
        assertThat(payload.subject()).isEqualTo("google-sub-123");
    }

    @Test
    void rejectsWhenGoogleClientIdIsMissing() throws Exception {
        GoogleTokenVerifier unconfigured = new GoogleTokenVerifier("", new JWKSet(rsaKey.toPublicJWK()));
        String idToken = signToken(baseClaims().build());

        assertThatThrownBy(() -> unconfigured.verifyIdToken(idToken))
                .isInstanceOfSatisfying(AuthException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("GOOGLE_AUTH_NOT_CONFIGURED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }

    @Test
    void rejectsExpiredOrWrongAudienceOrWrongIssuerTokens() throws Exception {
        long now = System.currentTimeMillis();
        String expired = signToken(baseClaims().expirationTime(new Date(now - 10_000)).build());
        String wrongAudience = signToken(baseClaims().audience("other-client-id").build());
        String wrongIssuer = signToken(baseClaims().issuer("https://evil.example.com").build());

        for (String token : new String[] { expired, wrongAudience, wrongIssuer }) {
            assertThatThrownBy(() -> verifier.verifyIdToken(token))
                    .isInstanceOfSatisfying(AuthException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo("INVALID_GOOGLE_TOKEN");
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    });
        }
    }

    @Test
    void rejectsUnverifiedGoogleEmailAndBlankEmail() throws Exception {
        String unverified = signToken(baseClaims().claim("email_verified", false).build());
        String missingVerified = signToken(baseClaims().claim("email_verified", null).build());
        String blankEmail = signToken(baseClaims().claim("email", "   ").build());

        for (String token : new String[] { unverified, missingVerified, blankEmail }) {
            assertThatThrownBy(() -> verifier.verifyIdToken(token))
                    .isInstanceOfSatisfying(AuthException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo("GOOGLE_EMAIL_UNVERIFIED");
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    });
        }
    }

    @Test
    void rejectsWrongSignatureMissingKidNonRs256AndBlankSubjectAndAcceptsBareIssuer() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("google-test-kid").generate();
        SignedJWT wrongKeyJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("google-test-kid").build(),
                baseClaims().build());
        wrongKeyJwt.sign(new RSASSASigner(otherKey));

        SignedJWT missingKidJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                baseClaims().build());
        missingKidJwt.sign(new RSASSASigner(rsaKey));

        SignedJWT hs256Jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("google-test-kid").build(),
                baseClaims().build());
        hs256Jwt.sign(new MACSigner("test-hmac-secret-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8)));

        String blankSubject = signToken(baseClaims().subject("   ").build());

        for (String token : new String[] {
                wrongKeyJwt.serialize(), missingKidJwt.serialize(), hs256Jwt.serialize(), blankSubject }) {
            assertThatThrownBy(() -> verifier.verifyIdToken(token))
                    .isInstanceOfSatisfying(AuthException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo("INVALID_GOOGLE_TOKEN");
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    });
        }

        // Chấp nhận issuer không có scheme https:// theo chuẩn Google OIDC
        String bareIssuerToken = signToken(baseClaims().issuer("accounts.google.com").build());
        assertThat(verifier.verifyIdToken(bareIssuerToken).email()).isEqualTo("student@gmail.com");
    }

    private JWTClaimsSet.Builder baseClaims() {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(CLIENT_ID)
                .subject("google-sub-123")
                .claim("email", "Student@Gmail.com")
                .claim("email_verified", true)
                .claim("name", "Nguyen Van A")
                .issueTime(new Date(now - 1_000))
                .expirationTime(new Date(now + 300_000));
    }

    private String signToken(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }
}
