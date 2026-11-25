package com.greytest.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GithubConfigResponse;
import com.greytest.dto.GithubRepoDto;
import com.greytest.dto.LoginResponse;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Service xử lý xác thực GitHub OAuth, quản lý GitHub access token và truy vấn
 * danh sách Repositories.
 */
@Slf4j
@Service
public class GithubOAuthService {

    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USER_API = "https://api.github.com/user";
    private static final String GITHUB_EMAILS_API = "https://api.github.com/user/emails";
    private static final String GITHUB_REPOS_API = "https://api.github.com/user/repos?sort=updated&per_page=100&affiliation=owner,collaborator";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final AuthUserRepository userRepository;
    private final AuthService authService;
    private final SettingsCipher cipher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired
    public GithubOAuthService(
            @Value("${greytest.auth.github-client-id:}") String clientId,
            @Value("${greytest.auth.github-client-secret:}") String clientSecret,
            @Value("${greytest.auth.github-redirect-uri:http://localhost:3000/auth/callback/github}") String redirectUri,
            AuthUserRepository userRepository,
            AuthService authService,
            SettingsCipher cipher) {
        this.clientId = clientId != null ? clientId.trim() : "";
        this.clientSecret = clientSecret != null ? clientSecret.trim() : "";
        this.redirectUri = redirectUri != null ? redirectUri.trim() : "";
        this.userRepository = userRepository;
        this.authService = authService;
        this.cipher = cipher;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Kiểm tra xem cấu hình GitHub OAuth đã được khai báo đầy đủ hay chưa */
    public boolean isConfigured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /** Trả về thông tin cấu hình công khai cho Frontend */
    public GithubConfigResponse getConfig() {
        return new GithubConfigResponse(isConfigured(), clientId, redirectUri);
    }

    /**
     * Xác thực với GitHub bằng authorization code, lấy thông tin tài khoản và đăng
     * nhập/liên kết vào GreyTest.
     * Không gắn @Transactional để các lời gọi GitHub không giữ kết nối DB.
     */
    public LoginResponse loginWithGithub(String code, String authorizationHeader) {
        if (!isConfigured()) {
            throw new AuthException(
                    "GITHUB_AUTH_NOT_CONFIGURED",
                    "Chức năng GitHub OAuth chưa được cấu hình (thiếu GITHUB_CLIENT_ID hoặc GITHUB_CLIENT_SECRET)",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (code == null || code.isBlank()) {
            throw new AuthException("VALIDATION_ERROR", "Mã xác thực GitHub code không hợp lệ", HttpStatus.BAD_REQUEST);
        }

        // 1. Đổi code lấy Access Token từ GitHub và lấy hồ sơ người dùng
        String accessToken = exchangeCodeForToken(code.trim());
        GithubUserProfile profile = fetchUserProfile(accessToken);

        // 2. Liên kết tài khoản đang đăng nhập hoặc đăng nhập/tạo mới
        AuthUser user;
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            user = authService.currentUser(authorizationHeader);
            final Long currentUserId = user.getId();
            userRepository.findByGithubUserId(profile.id()).ifPresent(existing -> {
                if (!existing.getId().equals(currentUserId)) {
                    throw new AuthException("GITHUB_ACCOUNT_ALREADY_LINKED",
                            "Tài khoản GitHub @" + profile.login()
                                    + " đã được liên kết với một tài khoản người dùng khác.",
                            HttpStatus.BAD_REQUEST);
                }
            });
            user = saveGithubLink(user, profile, accessToken);
            log.info("Người dùng {} (id: {}) đã liên kết thành công tài khoản GitHub @{}",
                    user.getEmail(), user.getId(), profile.login());
        } else {
            user = resolveOrCreateOAuthUser(profile, accessToken);
        }

        String jwt = authService.issueToken(user);
        return new LoginResponse(jwt, authService.toDto(user));
    }

    private AuthUser resolveOrCreateOAuthUser(GithubUserProfile profile, String accessToken) {
        AuthUser user = userRepository.findByGithubUserId(profile.id()).orElse(null);
        if (user != null) {
            assertLoginEligible(user);
            user = saveGithubLink(user, profile, accessToken);
            authService.recordLoginEvent(user, "GITHUB_OAUTH");
            return user;
        }

        // Chỉ cho phép auto-link theo email thật nếu đã xác minh; với email noreply do hệ thống tạo từ chính GitHub ID đó thì vẫn khớp lại để tránh lỗi trùng unique key sau khi disconnect.
        boolean canMatchByEmail = profile.emailVerified()
                || (profile.email() != null && profile.email().startsWith(profile.id() + "+")
                        && profile.email().endsWith("@users.noreply.github.com"));
        user = canMatchByEmail ? userRepository.findByEmailIgnoreCase(profile.email()).orElse(null) : null;
        if (user == null) {
            user = new AuthUser();
            user.setEmail(profile.email());
            user.setFullName(profile.fullName() != null && !profile.fullName().isBlank()
                    ? profile.fullName().trim()
                    : profile.login());
            user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setRole(UserRole.USER);
            user.setTier(UserTier.FREE);
            user.setEnabled(true);
            user.setEmailVerified(profile.emailVerified());
            user = saveGithubLink(user, profile, accessToken);
            authService.recordLoginEvent(user, "GITHUB_REGISTER");
            log.info("Tạo mới tài khoản qua GitHub OAuth: email={}, github={}", user.getEmail(), profile.login());
        } else {
            assertLoginEligible(user);
            AuthService.claimByVerifiedEmail(user, passwordEncoder);
            user = saveGithubLink(user, profile, accessToken);
            authService.recordLoginEvent(user, "GITHUB_OAUTH_LINK");
            log.info("Liên kết tài khoản hiện có {} với GitHub @{}", user.getEmail(), profile.login());
        }
        return user;
    }

    private void assertLoginEligible(AuthUser user) {
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException("ACCOUNT_DISABLED", "Tài khoản của bạn đã bị vô hiệu hóa", HttpStatus.FORBIDDEN);
        }
        if (user.getRole() == UserRole.ADMIN) {
            throw new AuthException("ADMIN_LOGIN_REQUIRED",
                    "Tài khoản quản trị phải đăng nhập tại cổng quản trị Admin",
                    HttpStatus.FORBIDDEN);
        }
    }

    private AuthUser saveGithubLink(AuthUser user, GithubUserProfile profile, String accessToken) {
        user.setGithubUserId(profile.id());
        user.setGithubUsername(profile.login());
        user.setGithubAccessToken(seal(accessToken));
        return userRepository.save(user);
    }

    /** Token đọc được repo private nên chỉ lưu dạng mã hóa; thiếu GREYTEST_SETTINGS_SECRET thì không lưu. */
    private String seal(String accessToken) {
        if (cipher == null || !cipher.isConfigured()) {
            log.warn("Chưa cấu hình GREYTEST_SETTINGS_SECRET nên không lưu GitHub token; chức năng lấy repository sẽ yêu cầu kết nối lại.");
            return null;
        }
        return cipher.encrypt(accessToken);
    }

    /** GitHub token đã giải mã của người dùng; null nếu chưa liên kết hoặc không giải mã được. */
    public String accessToken(AuthUser user) {
        String stored = user.getGithubAccessToken();
        if (stored == null || stored.isBlank() || cipher == null || !cipher.isConfigured()) {
            return null;
        }
        try {
            return cipher.decrypt(stored);
        } catch (IllegalStateException unreadable) {
            log.warn("Không giải mã được GitHub token của người dùng {}; cần kết nối lại.", user.getId());
            return null;
        }
    }

    /** Ngắt liên kết tài khoản GitHub khỏi người dùng hiện tại và thu hồi grant OAuth trên GitHub (best-effort) */
    public AuthUser disconnectGithub(AuthUser user) {
        String existingToken = accessToken(user);
        if (isConfigured() && existingToken != null) {
            revokeGithubGrantBestEffort(existingToken);
        }
        user.setGithubUserId(null);
        user.setGithubUsername(null);
        user.setGithubAccessToken(null);
        return userRepository.save(user);
    }

    private void revokeGithubGrantBestEffort(String accessToken) {
        try {
            String basicCredentials = java.util.Base64.getEncoder()
                    .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            String payload = objectMapper.writeValueAsString(Map.of("access_token", accessToken));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/applications/" + clientId + "/grant"))
                    .header("Authorization", "Basic " + basicCredentials)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "GreyTest-App")
                    .timeout(Duration.ofSeconds(5))
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Không thể thu hồi OAuth grant trên GitHub khi disconnect (bỏ qua): {}", e.getMessage());
        }
    }

    private HttpRequest buildGithubGetRequest(String url, String accessToken, int timeoutSeconds) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GreyTest-App")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
    }

    /**
     * Lấy danh sách repositories (cả public và private) của tài khoản GitHub đã
     * liên kết.
     */
    public List<GithubRepoDto> getUserRepositories(AuthUser user) {
        String token = accessToken(user);
        if (token == null) {
            throw new AuthException("GITHUB_NOT_CONNECTED",
                    "Tài khoản chưa liên kết GitHub. Vui lòng đăng nhập hoặc kết nối tài khoản GitHub trước.",
                    HttpStatus.BAD_REQUEST);
        }

        HttpRequest request = buildGithubGetRequest(GITHUB_REPOS_API, token, 15);

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401) {
                throw new AuthException("GITHUB_TOKEN_EXPIRED",
                        "Phiên liên kết GitHub đã hết hạn hoặc bị thu hồi. Vui lòng kết nối lại tài khoản GitHub.",
                        HttpStatus.BAD_REQUEST);
            }
            if (response.statusCode() != 200) {
                log.error("Lỗi khi tải repositories từ GitHub: status={}, body={}", response.statusCode(),
                        response.body());
                throw new AuthException("GITHUB_API_ERROR",
                        "Không thể tải danh sách repository từ GitHub (mã lỗi " + response.statusCode() + ")",
                        HttpStatus.BAD_GATEWAY);
            }

            JsonNode root = objectMapper.readTree(response.body());
            List<GithubRepoDto> repos = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    repos.add(new GithubRepoDto(
                            item.path("id").asLong(),
                            item.path("name").asText(""),
                            item.path("full_name").asText(""),
                            item.path("html_url").asText(""),
                            item.path("clone_url").asText(""),
                            item.path("default_branch").asText("main"),
                            item.path("private").asBoolean(false),
                            item.path("description").asText("")));
                }
            }
            return repos;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("GITHUB_CONNECTION_ERROR",
                    "Lỗi kết nối tới GitHub API: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String exchangeCodeForToken(String code) {
        Map<String, String> bodyMap = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri);

        try {
            String jsonPayload = objectMapper.writeValueAsString(bodyMap);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_TOKEN_URL))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "GreyTest-App")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.error("GitHub token exchange trả về status {}: {}", response.statusCode(), response.body());
                throw new AuthException("GITHUB_TOKEN_ERROR", "Lỗi từ GitHub khi xác thực token",
                        HttpStatus.BAD_REQUEST);
            }

            JsonNode json = objectMapper.readTree(response.body());
            if (json.has("error")) {
                String errorDescription = json.path("error_description").asText(json.path("error").asText());
                log.error("GitHub OAuth error: {}", errorDescription);
                throw new AuthException("GITHUB_AUTH_FAILED", "Xác thực GitHub thất bại: " + errorDescription,
                        HttpStatus.BAD_REQUEST);
            }

            String accessToken = json.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new AuthException("GITHUB_TOKEN_MISSING", "Không nhận được access_token từ GitHub",
                        HttpStatus.BAD_REQUEST);
            }
            return accessToken;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("GITHUB_NETWORK_ERROR", "Không thể kết nối đến máy chủ GitHub: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private record GithubUserProfile(String id, String login, String fullName, String email, boolean emailVerified) {
    }

    private GithubUserProfile fetchUserProfile(String accessToken) {
        HttpRequest request = buildGithubGetRequest(GITHUB_USER_API, accessToken, 10);

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new AuthException("GITHUB_PROFILE_ERROR", "Không lấy được thông tin người dùng từ GitHub",
                        HttpStatus.BAD_REQUEST);
            }

            JsonNode userNode = objectMapper.readTree(response.body());
            String id = userNode.path("id").asText();
            String login = userNode.path("login").asText();
            String name = userNode.path("name").asText(login);
            // Luôn lấy email đã được xác minh (verified) từ /user/emails để ngăn chặn tấn
            // công Account Takeover
            String verifiedEmail = fetchPrimaryEmail(accessToken);
            boolean emailVerified = verifiedEmail != null && !verifiedEmail.isBlank();
            String email = emailVerified ? verifiedEmail : (id + "+" + login + "@users.noreply.github.com");

            return new GithubUserProfile(id, login, name, email, emailVerified);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("GITHUB_NETWORK_ERROR", "Không lấy được hồ sơ GitHub: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String fetchPrimaryEmail(String accessToken) {
        HttpRequest request = buildGithubGetRequest(GITHUB_EMAILS_API, accessToken, 10);

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new AuthException("GITHUB_EMAIL_ERROR", "Không lấy được email đã xác minh từ GitHub",
                        HttpStatus.BAD_GATEWAY);
            }
            JsonNode array = objectMapper.readTree(response.body());
            if (array.isArray()) {
                for (JsonNode item : array) {
                    boolean verified = item.path("verified").asBoolean(false);
                    boolean primary = item.path("primary").asBoolean(false);
                    if (verified && primary) {
                        return item.path("email").asText();
                    }
                }
                for (JsonNode item : array) {
                    if (item.path("verified").asBoolean(false)) {
                        return item.path("email").asText();
                    }
                }
            }
            return null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("GITHUB_NETWORK_ERROR",
                    "Không lấy được danh sách emails từ GitHub: " + e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
