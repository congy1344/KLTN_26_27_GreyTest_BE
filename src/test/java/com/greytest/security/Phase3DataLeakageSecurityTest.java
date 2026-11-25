package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.greytest.controller.ExportController;
import com.greytest.controller.UnitTestController;
import com.greytest.dto.ErrorResponse;
import com.greytest.dto.ProjectDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.BreakGlassGrant;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.exception.GlobalExceptionHandler;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.StorageException;
import com.greytest.mapper.ProjectMapper;
import com.greytest.repository.BreakGlassGrantRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.AuthService;
import com.greytest.service.BreakGlassAccessService;
import com.greytest.service.ExportService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.GenerationProgressService;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.service.ProjectService;
import com.greytest.service.ServerTelemetryService;
import com.greytest.service.UnitTestService;
import com.greytest.service.coverage.SandboxTestRunService;
import com.greytest.service.storage.FileStorageService;
import com.greytest.service.storage.GithubService;

/**
 * Phase 3.4: API, Artifact & Data Leakage Protection Security Tests.
 * Xác minh kiểm soát rò rỉ dữ liệu qua REST API, Export, Download, Log, Exception và Admin Access.
 */
class Phase3DataLeakageSecurityTest {

    private AuthUser createUser(Long id, String email, UserRole role) {
        AuthUser user = new AuthUser();
        user.setId(id);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }

    private Project createProject(Long id, Long ownerId) {
        Project project = new Project();
        project.setId(id);
        project.setName("Customer Project " + id);
        project.setOwnerUserId(ownerId);
        project.setStoragePath("/app/storage/projects/secret-path-" + id);
        project.setStorageEncrypted(true);
        project.setEncryptedDek("secret-dek-blob");
        project.setStatus(ProjectStatus.UPLOADED);
        project.setSourceType(SourceType.ZIP);
        project.setCreatedAt(LocalDateTime.now());
        return project;
    }

    @Test
    @DisplayName("1. User A không đọc được source của Project B")
    void testUserCannotReadOtherProjectSource() {
        AuthUser userA = createUser(101L, "userA@example.com", UserRole.USER);
        Project projectB = createProject(202L, 999L); // thuộc sở hữu của user 999

        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(202L)).thenReturn(Optional.of(projectB));
        when(repo.existsById(202L)).thenReturn(true);
        when(repo.findByIdAndOwnerUserId(202L, 101L)).thenReturn(Optional.empty());

        ProjectService service = new ProjectService(repo, mock(FileStorageService.class),
                mock(GithubService.class), mock(ProjectMapper.class), null, null, null);

        assertThatThrownBy(() -> service.requireSourceAccess(202L, userA))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(authEx.getCode()).isEqualTo("FORBIDDEN");
                });
    }

    @Test
    @DisplayName("2. User A không tải được ZIP Unit Test của Project B")
    void testUserCannotDownloadOtherProjectZip() {
        AuthUser userA = createUser(101L, "userA@example.com", UserRole.USER);
        Project projectB = createProject(202L, 999L);

        AuthService auth = mock(AuthService.class);
        when(auth.currentUser("Bearer tokenA")).thenReturn(userA);

        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(202L)).thenReturn(Optional.of(projectB));
        when(repo.existsById(202L)).thenReturn(true);
        when(repo.findByIdAndOwnerUserId(202L, 101L)).thenReturn(Optional.empty());

        ProjectService projects = new ProjectService(repo, mock(FileStorageService.class),
                mock(GithubService.class), mock(ProjectMapper.class), null, null, null);

        UnitTestController controller = new UnitTestController(
                mock(UnitTestService.class), auth, projects,
                mock(GenerationJobService.class), mock(SandboxTestRunService.class));

        assertThatThrownBy(() -> controller.download(202L, null, "Bearer tokenA"))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("3. User A không truy cập được Job hoặc quyền của Project B")
    void testUserCannotAccessOtherProjectJobOrData() {
        AuthUser userA = createUser(101L, "userA@example.com", UserRole.USER);
        Project projectB = createProject(202L, 999L);

        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(202L)).thenReturn(Optional.of(projectB));
        when(repo.existsById(202L)).thenReturn(true);
        when(repo.findByIdAndOwnerUserId(202L, 101L)).thenReturn(Optional.empty());

        ProjectService projects = new ProjectService(repo, mock(FileStorageService.class),
                mock(GithubService.class), mock(ProjectMapper.class), null, null, null);

        assertThatThrownBy(() -> projects.requireAccess(202L, userA))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("4. User A không tải được report Export của Project B")
    void testUserCannotDownloadOtherProjectReport() {
        AuthUser userA = createUser(101L, "userA@example.com", UserRole.USER);
        Project projectB = createProject(202L, 999L);

        AuthService auth = mock(AuthService.class);
        when(auth.currentUser("Bearer tokenA")).thenReturn(userA);

        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(202L)).thenReturn(Optional.of(projectB));
        when(repo.existsById(202L)).thenReturn(true);
        when(repo.findByIdAndOwnerUserId(202L, 101L)).thenReturn(Optional.empty());

        ProjectService projects = new ProjectService(repo, mock(FileStorageService.class),
                mock(GithubService.class), mock(ProjectMapper.class), null, null, null);

        ExportController controller = new ExportController(mock(ExportService.class), auth, projects);

        assertThatThrownBy(() -> controller.export(202L, "json", null, "Bearer tokenA"))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("5. Admin không thể đọc source qua REST API khi thiếu Break-Glass")
    void testAdminCannotAccessSourceWithoutBreakGlass() {
        AuthUser admin = createUser(1L, "admin@example.com", UserRole.ADMIN);
        Project customerProject = createProject(505L, 777L); // project của khách hàng

        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(505L)).thenReturn(Optional.of(customerProject));

        BreakGlassAccessService breakGlass = mock(BreakGlassAccessService.class);
        when(breakGlass.hasActiveBreakGlass(505L, 1L)).thenReturn(false);

        ProjectService projects = new ProjectService(repo, mock(FileStorageService.class),
                mock(GithubService.class), mock(ProjectMapper.class), breakGlass, null, null);

        assertThatThrownBy(() -> projects.requireSourceAccess(505L, admin))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(authEx.getMessage()).contains("Break-Glass");
                });
    }

    @Test
    @DisplayName("6. Admin không thể download artifact khi thiếu Break-Glass")
    void testAdminCannotDownloadArtifactWithoutBreakGlass() {
        AuthUser admin = createUser(1L, "admin@example.com", UserRole.ADMIN);
        Project customerProject = createProject(505L, 777L);

        AuthService auth = mock(AuthService.class);
        when(auth.currentUser("Bearer adminToken")).thenReturn(admin);

        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(505L)).thenReturn(Optional.of(customerProject));

        BreakGlassAccessService breakGlass = mock(BreakGlassAccessService.class);
        when(breakGlass.hasActiveBreakGlass(505L, 1L)).thenReturn(false);

        ProjectService projects = new ProjectService(repo, mock(FileStorageService.class),
                mock(GithubService.class), mock(ProjectMapper.class), breakGlass, null, null);

        ExportController controller = new ExportController(mock(ExportService.class), auth, projects);

        assertThatThrownBy(() -> controller.export(505L, "markdown", null, "Bearer adminToken"))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getMessage()).contains("Break-Glass"));
    }

    @Test
    @DisplayName("7. Break-Glass hết hạn thì API từ chối truy cập ngay lập tức")
    void testExpiredBreakGlassDeniedImmediately() {
        BreakGlassGrantRepository grantRepo = mock(BreakGlassGrantRepository.class);
        // Khi grant hết hạn, findActiveGrants trả về danh sách rỗng
        when(grantRepo.findActiveGrants(eq(505L), eq(1L), any())).thenReturn(List.of());

        BreakGlassAccessService breakGlass = new BreakGlassAccessService(grantRepo, null, null, null);
        assertThat(breakGlass.hasActiveBreakGlass(505L, 1L)).isFalse();
    }

    @Test
    @DisplayName("8. Break-Glass bị revoke thì API từ chối ngay lập tức")
    void testRevokedBreakGlassDeniedImmediately() {
        BreakGlassGrant grant = new BreakGlassGrant();
        grant.setId(10L);
        grant.setProjectId(505L);
        grant.setAdminUserId(1L);
        grant.setStatus("REVOKED");
        grant.setRevoked(true);

        BreakGlassGrantRepository grantRepo = mock(BreakGlassGrantRepository.class);
        when(grantRepo.findActiveGrants(eq(505L), eq(1L), any())).thenReturn(List.of());

        BreakGlassAccessService breakGlass = new BreakGlassAccessService(grantRepo, null, null, null);
        assertThat(breakGlass.hasActiveBreakGlass(505L, 1L)).isFalse();
    }

    @Test
    @DisplayName("9. API ProjectDto không tiết lộ đường dẫn lưu trữ source code trên server")
    void testProjectDtoDoesNotExposeStoragePath() {
        Project project = createProject(101L, 50L);
        ProjectDto dto = new ProjectDto(
                project.getId(),
                project.getName(),
                project.getSourceType(),
                project.getSourceUrl(),
                project.getStatus(),
                project.getCreatedAt(),
                project.getOwnerUserId(),
                true
        );

        // Xác nhận record ProjectDto chỉ có metadata, tuyệt đối không có trường storagePath hay dek
        assertThat(dto.id()).isEqualTo(101L);
        assertThat(dto.name()).isEqualTo("Customer Project 101");
        assertThat(dto.toString()).doesNotContain("/app/storage");
        assertThat(dto.toString()).doesNotContain("secret-path");
        assertThat(dto.toString()).doesNotContain("dek");
    }

    @Test
    @DisplayName("10. GlobalExceptionHandler không trả stack trace hay internal path cho client")
    void testExceptionDoesNotLeakStackTraceOrInternalPath() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        // 1. Generic Exception
        ResponseEntity<ErrorResponse> responseAll = handler.handleAll(new RuntimeException("NullPointerException at com.greytest.DbInternal:123"));
        assertThat(responseAll.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(responseAll.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(responseAll.getBody().message()).isEqualTo("Có lỗi xảy ra");

        // 2. Storage Exception
        ResponseEntity<ErrorResponse> responseStorage = handler.handleStorage(new StorageException("Failed to write to /app/storage/projects/uuid-secret"));
        assertThat(responseStorage.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(responseStorage.getBody().code()).isEqualTo("STORAGE_ERROR");
        assertThat(responseStorage.getBody().message()).isEqualTo("Có lỗi khi xử lý file source");

        // 3. Security Exception
        ResponseEntity<ErrorResponse> responseSec = handler.handleSecurity(new SecurityException("Path traversal blocked"));
        assertThat(responseSec.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(responseSec.getBody().code()).isEqualTo("FORBIDDEN");
        assertThat(responseSec.getBody().message()).isEqualTo("Truy cập bị từ chối vì lý do bảo mật");

        // 4. Sanitized IllegalArgumentException
        ResponseEntity<ErrorResponse> responseArg = handler.handleIllegalArgument(new IllegalArgumentException("Invalid file: C:\\app\\storage\\secret.txt"));
        assertThat(responseArg.getBody().message()).doesNotContain("C:\\app\\storage");
        assertThat(responseArg.getBody().message()).contains("[REDACTED_PATH]");

        ResponseEntity<ErrorResponse> responseArg2 = handler.handleIllegalArgument(new IllegalArgumentException("Invalid file: C:/Users/Khai Tien/project/secret.java"));
        assertThat(responseArg2.getBody().message()).doesNotContain("C:/Users");
        assertThat(responseArg2.getBody().message()).contains("[REDACTED_PATH]");
    }

    @Test
    @DisplayName("11. Telemetry log không chứa sensitive query params (password, tokens)")
    void testLogsDoNotContainSensitiveParameters() {
        String rawUri = "/api/auth/login?password=superSecret123&token=jwtSecretToken&name=test";
        String sanitized = ServerTelemetryService.sanitizeUri(rawUri);

        assertThat(sanitized).doesNotContain("superSecret123");
        assertThat(sanitized).doesNotContain("jwtSecretToken");
        assertThat(sanitized).contains("password=[REDACTED]");
        assertThat(sanitized).contains("token=[REDACTED]");
        assertThat(sanitized).contains("name=test");
    }

    @Test
    @DisplayName("12. Path traversal trong servicePath bị từ chối tuyệt đối")
    void testPathTraversalRejected() {
        AuthUser user = createUser(101L, "user@example.com", UserRole.USER);
        Project project = createProject(101L, 101L);

        AuthService auth = mock(AuthService.class);
        when(auth.currentUser("Bearer token")).thenReturn(user);

        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(101L)).thenReturn(Optional.of(project));
        when(repo.findByIdAndOwnerUserId(101L, 101L)).thenReturn(Optional.of(project));
        when(repo.existsById(101L)).thenReturn(true);

        ProjectService projects = new ProjectService(repo, mock(FileStorageService.class),
                mock(GithubService.class), mock(ProjectMapper.class), null, null, null);

        ExportController exportController = new ExportController(mock(ExportService.class), auth, projects);
        UnitTestController unitController = new UnitTestController(mock(UnitTestService.class), auth, projects,
                mock(GenerationJobService.class), mock(SandboxTestRunService.class));

        // 1. Export path traversal
        assertThatThrownBy(() -> exportController.export(101L, "json", "../../etc/passwd", "Bearer token"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("nguy hiểm");

        // 2. Download path traversal
        assertThatThrownBy(() -> unitController.download(101L, "../../../root", "Bearer token"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("nguy hiểm");

        // 3. Absolute path / Drive letter (:)
        assertThatThrownBy(() -> exportController.export(101L, "json", "C:/Windows/System32", "Bearer token"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("nguy hiểm");

        assertThatThrownBy(() -> unitController.download(101L, "D:\\data\\secrets", "Bearer token"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("nguy hiểm");
    }

    @Test
    @DisplayName("13. Export và Download không thể truy cập công khai (bắt buộc Authorization)")
    void testExportRequiresAuthentication() {
        AuthService auth = mock(AuthService.class);
        when(auth.currentUser(null)).thenThrow(new AuthException("AUTH_ERROR", "Token không hợp lệ hoặc đã hết hạn", HttpStatus.UNAUTHORIZED));
        ExportController controller = new ExportController(mock(ExportService.class), auth, mock(ProjectService.class));

        assertThatThrownBy(() -> controller.export(101L, "json", null, null))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("14. Project deletion thu hồi quyền truy cập artifact ngay lập tức")
    void testProjectDeletionRevokesArtifactAccess() {
        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(999L)).thenReturn(Optional.empty()); // Đã bị xóa

        ProjectService projects = new ProjectService(repo, mock(FileStorageService.class),
                mock(GithubService.class), mock(ProjectMapper.class), null, null, null);

        AuthUser user = createUser(101L, "user@example.com", UserRole.USER);

        assertThatThrownBy(() -> projects.requireSourceAccess(999L, user))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    @DisplayName("15. Cache Control headers được thiết lập chống lưu cache giữa các user")
    void testCacheControlHeadersPreventSensitiveDataCaching() {
        AuthUser user = createUser(101L, "user@example.com", UserRole.USER);
        Project project = createProject(101L, 101L);

        AuthService auth = mock(AuthService.class);
        when(auth.currentUser("Bearer token")).thenReturn(user);

        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(101L)).thenReturn(Optional.of(project));
        when(repo.findByIdAndOwnerUserId(101L, 101L)).thenReturn(Optional.of(project));
        when(repo.existsById(101L)).thenReturn(true);

        ProjectService projects = new ProjectService(repo, mock(FileStorageService.class),
                mock(GithubService.class), mock(ProjectMapper.class), null, null, null);

        ExportService exportService = mock(ExportService.class);
        when(exportService.export(101L, "json", null)).thenReturn("{\"data\":\"sample\"}");

        ExportController exportController = new ExportController(exportService, auth, projects);

        ResponseEntity<String> response = exportController.export(101L, "json", null, "Bearer token");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().get(HttpHeaders.CACHE_CONTROL))
                .contains("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(response.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("Expires")).isEqualTo("0");

        // Xác nhận ServerRequestLoggingFilter tự động gắn Cache-Control và security headers
        com.greytest.config.ServerRequestLoggingFilter filter = new com.greytest.config.ServerRequestLoggingFilter(
                mock(ServerTelemetryService.class), auth, mock(com.greytest.service.DdosProtectionService.class));
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/projects/101/unit-tests");
        org.springframework.mock.web.MockHttpServletResponse filterResponse = new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain filterChain = new org.springframework.mock.web.MockFilterChain();

        try {
            filter.doFilter(request, filterResponse, filterChain);
            assertThat(filterResponse.getHeader("Cache-Control")).isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
            assertThat(filterResponse.getHeader("Pragma")).isEqualTo("no-cache");
            assertThat(filterResponse.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        } catch (Exception e) {
            org.junit.jupiter.api.Assertions.fail("Filter execution failed: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("16. SEC-3.5-01: Break-Glass bị thu hồi in-flight thì Background Job từ chối thực thi")
    void testInFlightRevokedBreakGlassAbortsBackgroundJob() {
        Long projectId = 505L;
        Long adminId = 1L;
        Project customerProject = createProject(projectId, 999L);

        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(projectId)).thenReturn(Optional.of(customerProject));

        BreakGlassAccessService breakGlass = mock(BreakGlassAccessService.class);
        // Tại thời điểm submit: grant còn active (true). Khi job run: grant bị revoke/hết hạn (false).
        when(breakGlass.hasActiveBreakGlass(projectId, adminId)).thenReturn(true, false);

        GenerationProgressService progressService = mock(GenerationProgressService.class);
        // Executor chạy đồng bộ trên cùng thread để kiểm tra logic ngắt
        java.util.concurrent.Executor directExecutor = Runnable::run;

        GenerationJobService jobService = new GenerationJobService(
                directExecutor, progressService, repo, null, null, breakGlass);

        java.util.concurrent.atomic.AtomicBoolean taskExecuted = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Submit job: lần 1 hasActiveBreakGlass trả về true (cho phép submit).
        // Khi executor chạy run(): lần 2 hasActiveBreakGlass trả về false -> ném AuthException và task KHÔNG được chạy!
        jobService.submit(projectId, adminId, GenerationProgressStage.UNIT_TEST, () -> {
            taskExecuted.set(true);
        });

        assertThat(taskExecuted.get()).isFalse();
    }
}
