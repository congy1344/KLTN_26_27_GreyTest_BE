package com.greytest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.config.AdminAuthorizationInterceptor;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.GlobalExceptionHandler;
import com.greytest.repository.AuthUserRepository;
import com.greytest.service.AuthService;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class AuthControllerJwtTest {

    private static final String SECRET = "http-test-jwt-secret-" + "x".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, AuthUser> users = new HashMap<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthUserRepository repository = mock(AuthUserRepository.class);
        users.put(1L, user(1L, UserRole.USER));
        users.put(2L, user(2L, UserRole.ADMIN));
        when(repository.findByEmailIgnoreCase(anyString())).thenAnswer(invocation -> users.values().stream()
                .filter(user -> user.getEmail().equalsIgnoreCase(invocation.getArgument(0)))
                .findFirst());
        when(repository.findById(anyLong())).thenAnswer(invocation -> Optional.ofNullable(users.get(invocation.getArgument(0))));
        when(repository.save(any(AuthUser.class))).thenAnswer(invocation -> {
            AuthUser saved = invocation.getArgument(0);
            saved.setId(3L);
            users.put(3L, saved);
            return saved;
        });
        AuthService auth = new AuthService(repository, SECRET);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth), new AdminProbe())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addMappedInterceptors(new String[] { "/api/admin/**" }, new AdminAuthorizationInterceptor(auth))
                .build();
    }

    @Test
    void registeredUserCanUseReturnedJwtOnProtectedMeEndpoint() throws Exception {
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", "new@greytest.dev",
                                "password", "correct-password", "fullName", "New User"))))
                .andExpect(status().isOk()).andExpect(jsonPath("user.role").value("USER"))
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(response).get("token").asText();

        assertThat(token.split("\\.")).hasSize(3);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("id").value(3))
                .andExpect(jsonPath("email").value("new@greytest.dev"));
    }

    @Test
    void loginReturnsStandardJwtThatAuthenticatesProtectedRequests() throws Exception {
        String token = login("/api/auth/login", "user@greytest.dev");

        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getSubject()).isEqualTo("1");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("role").value("USER"));
    }

    @Test
    void invalidPasswordDoesNotIssueJwt() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", "user@greytest.dev", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTH_ERROR"))
                .andExpect(jsonPath("token").doesNotExist());
    }

    @Test
    void missingTamperedAndExpiredTokensReturn401() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        String token = login("/api/auth/login", "user@greytest.dev");
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + String.join(".", parts)))
                .andExpect(status().isUnauthorized());
        SignedJWT expired = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder().subject("1").issuer("greytest").audience("greytest-api")
                        .issueTime(new Date(System.currentTimeMillis() - 60000))
                        .expirationTime(new Date(System.currentTimeMillis() - 1000)).build());
        expired.sign(new MACSigner(SECRET));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired.serialize()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTH_ERROR"));
    }

    @Test
    void adminAccessUsesCurrentRoleFromDatabase() throws Exception {
        String userToken = login("/api/auth/login", "user@greytest.dev");
        mvc.perform(get("/api/admin/jwt-test").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        String adminToken = login("/api/auth/admin/login", "admin@greytest.dev");
        mvc.perform(get("/api/admin/jwt-test").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        users.get(2L).setRole(UserRole.USER);
        mvc.perform(get("/api/admin/jwt-test").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("ADMIN_REQUIRED"));
    }

    @Test
    void disablingAccountRejectsItsUnexpiredJwt() throws Exception {
        String token = login("/api/auth/login", "user@greytest.dev");
        users.get(1L).setEnabled(false);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("ACCOUNT_DISABLED"));
    }

    private String login(String path, String email) throws Exception {
        String response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "correct-password"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("token").asText();
    }

    private AuthUser user(Long id, UserRole role) {
        AuthUser user = new AuthUser();
        user.setId(id);
        user.setEmail(role == UserRole.ADMIN ? "admin@greytest.dev" : "user@greytest.dev");
        user.setFullName("Test User");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("correct-password"));
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    @RestController
    static class AdminProbe {
        @GetMapping("/api/admin/jwt-test")
        Map<String, Boolean> admin() { return Map.of("allowed", true); }
    }
}
