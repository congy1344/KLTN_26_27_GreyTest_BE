package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.greytest.dto.LoginRequest;
import com.greytest.dto.RegisterRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

class AuthServiceJwtTest {

    private static final String SECRET = "test-only-jwt-secret-" + "x".repeat(64);
    private final AuthUserRepository repository = mock(AuthUserRepository.class);
    private final AuthService service = new AuthService(repository, SECRET);

    @Test
    void loginIssuesVerifiableStandardJwtAndAuthenticatesAgainstDatabase() throws Exception {
        AuthUser user = user();
        when(repository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(repository.findById(1L)).thenReturn(Optional.of(user));

        String token = service.login(new LoginRequest(user.getEmail(), "correct-password")).token();
        SignedJWT jwt = SignedJWT.parse(token);

        assertThat(token.split("\\.")).hasSize(3);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.getHeader().getType()).isEqualTo(JOSEObjectType.JWT);
        assertThat(jwt.verify(new MACVerifier(SECRET))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("1");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("greytest");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("greytest-api");
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().getTime()
                - jwt.getJWTClaimsSet().getIssueTime().getTime()).isEqualTo(Duration.ofDays(7).toMillis());
        assertThat(jwt.getJWTClaimsSet().getClaims()).doesNotContainKeys("password", "passwordHash");
        assertThat(service.currentUser("Bearer " + token)).isSameAs(user);
    }

    @Test
    void registrationHashesPasswordAndReturnsJwtWithUserRole() throws Exception {
        when(repository.save(org.mockito.ArgumentMatchers.any(AuthUser.class))).thenAnswer(invocation -> {
            AuthUser saved = invocation.getArgument(0);
            saved.setId(1L);
            assertThat(new BCryptPasswordEncoder().matches("correct-password", saved.getPasswordHash())).isTrue();
            assertThat(saved.getRole()).isEqualTo(UserRole.USER);
            return saved;
        });

        var response = service.register(new RegisterRequest("user@greytest.dev", "correct-password", "Test User"));

        assertThat(SignedJWT.parse(response.token()).verify(new MACVerifier(SECRET))).isTrue();
        assertThat(response.user().role()).isEqualTo(UserRole.USER);
    }

    @Test
    void changingSubjectWithoutResigningCannotAuthenticateAnotherUser() throws Exception {
        String valid = signed(claims().build(), JWSAlgorithm.HS256, SECRET);
        String[] parts = valid.split("\\.");
        String changedPayload = Base64URL.encode(new JWTClaimsSet.Builder(claims().build())
                .subject("2").build().toString()).toString();

        assertUnauthorized(parts[0] + "." + changedPayload + "." + parts[2]);
    }

    @Test
    void rejectsWrongSignatureAndUnsignedJwt() throws Exception {
        assertUnauthorized(signed(claims().build(), JWSAlgorithm.HS256, "other-key-" + "y".repeat(64)));
        assertUnauthorized(new PlainJWT(claims().build()).serialize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS384", "HS512"})
    void rejectsOtherHmacAlgorithmsEvenWithValidSignature(String algorithm) throws Exception {
        assertUnauthorized(signed(claims().build(), JWSAlgorithm.parse(algorithm), SECRET));
    }

    @ParameterizedTest
    @MethodSource("invalidClaims")
    void rejectsExpiredMissingOrInvalidClaims(JWTClaimsSet invalid) throws Exception {
        assertUnauthorized(signed(invalid, JWSAlgorithm.HS256, SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-jwt", "legacy.signature", "a.b.c.d"})
    void rejectsMalformedAndLegacyTokens(String token) {
        assertUnauthorized(token);
    }

    @Test
    void rejectsMissingBearerAndOversizedTokens() {
        assertThatThrownBy(() -> service.currentUser(null)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.currentUser("Basic credentials")).isInstanceOf(AuthException.class);
        assertUnauthorized("x".repeat(4097));
    }

    @Test
    void rejectsWrongTypeAndUnsupportedCriticalHeader() throws Exception {
        for (JWSHeader header : new JWSHeader[] {
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("other-token")).build(),
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT)
                        .criticalParams(java.util.Set.of("custom")).customParam("custom", true).build() }) {
            SignedJWT jwt = new SignedJWT(header, claims().build());
            jwt.sign(new MACSigner(SECRET));
            assertUnauthorized(jwt.serialize());
        }
    }

    @Test
    void accountDisabledAfterLoginIsRejectedImmediately() throws Exception {
        AuthUser disabled = user();
        disabled.setEnabled(false);
        when(repository.findById(1L)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.currentUser("Bearer " + signed(claims().build(), JWSAlgorithm.HS256, SECRET)))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("ACCOUNT_DISABLED");
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    @Test
    void signedRoleClaimDoesNotOverrideCurrentDatabaseRole() throws Exception {
        AuthUser current = user();
        when(repository.findById(1L)).thenReturn(Optional.of(current));
        JWTClaimsSet forgedRole = claims().claim("role", "ADMIN").build();

        assertThat(service.currentUser("Bearer " + signed(forgedRole, JWSAlgorithm.HS256, SECRET)).getRole())
                .isEqualTo(UserRole.USER);
    }

    @Test
    void deletedUserCannotKeepUsingAnUnexpiredJwt() throws Exception {
        String token = signed(claims().build(), JWSAlgorithm.HS256, SECRET);
        assertThatThrownBy(() -> service.currentUser("Bearer " + token))
                .isInstanceOfSatisfying(AuthException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private void assertUnauthorized(String token) {
        assertThatThrownBy(() -> service.currentUser("Bearer " + token))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("AUTH_ERROR");
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
        verifyNoInteractions(repository);
    }

    private static Stream<JWTClaimsSet> invalidClaims() {
        long now = System.currentTimeMillis();
        return Stream.of(
                claims().expirationTime(new Date(now - 1000)).build(),
                claims().expirationTime(new Date(now)).build(),
                claims().expirationTime(null).build(),
                claims().issuer("other-app").build(),
                claims().issuer(null).build(),
                claims().audience("other-api").build(),
                claims().audience((String) null).build(),
                claims().issueTime(null).build(),
                claims().issueTime(new Date(now + 60000)).build(),
                claims().notBeforeTime(new Date(now + 60000)).build(),
                claims().subject(null).build(),
                claims().subject("not-a-user-id").build(),
                claims().subject("0").build(),
                claims().subject("-1").build());
    }

    private static JWTClaimsSet.Builder claims() {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder().issuer("greytest").audience("greytest-api").subject("1")
                .issueTime(new Date(now - 1000)).expirationTime(new Date(now + 60000));
    }

    private static String signed(JWTClaimsSet claims, JWSAlgorithm algorithm, String secret) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static AuthUser user() {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setEmail("user@greytest.dev");
        user.setFullName("Test User");
        user.setRole(UserRole.USER);
        user.setEnabled(true);
        user.setPasswordHash(new BCryptPasswordEncoder().encode("correct-password"));
        return user;
    }
}
