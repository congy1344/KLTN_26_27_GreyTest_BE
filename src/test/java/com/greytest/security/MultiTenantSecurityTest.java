package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.ProjectDto;
import com.greytest.dto.admin.BreakGlassGrantDto;
import com.greytest.dto.admin.BreakGlassRequestDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.BreakGlassGrant;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.mapper.ProjectMapper;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.BreakGlassGrantRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.BreakGlassAccessService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.GenerationProgressService;
import com.greytest.service.ProjectService;
import com.greytest.service.SourceUpdateService;
import com.greytest.service.UserActivityService;
import com.greytest.service.storage.FileStorageService;
import com.greytest.service.storage.GithubService;

@ExtendWith(MockitoExtension.class)
class MultiTenantSecurityTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private BreakGlassGrantRepository grantRepository;
    @Mock
    private AuthUserRepository authUserRepository;
    @Mock
    private UserActivityService activityService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private GithubService githubService;
    @Mock
    private GenerationProgressService progressService;
    @Mock
    private Executor executor;

    private BreakGlassAccessService breakGlassService;
    private ProjectService projectService;
    private GenerationJobService generationJobService;
    private SourceUpdateService sourceUpdateService;

    private AuthUser userA;
    private AuthUser userB;
    private AuthUser adminUser;
    private AuthUser approverAdmin;
    private Project projectA;
    private Project projectB;

    @BeforeEach
    void setUp() {
        breakGlassService = new BreakGlassAccessService(grantRepository, projectRepository, authUserRepository, activityService);
        projectService = new ProjectService(projectRepository, fileStorageService, githubService, new ProjectMapper(), breakGlassService, activityService);
        generationJobService = new GenerationJobService(executor, progressService, projectRepository, activityService, breakGlassService);

        sourceUpdateService = new SourceUpdateService(
                projectRepository, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        sourceUpdateService.setBreakGlassAccessService(breakGlassService);

        userA = new AuthUser();
        userA.setId(101L);
        userA.setEmail("userA@example.com");
        userA.setRole(UserRole.USER);
        userA.setEnabled(true);

        userB = new AuthUser();
        userB.setId(102L);
        userB.setEmail("userB@example.com");
        userB.setRole(UserRole.USER);
        userB.setEnabled(true);

        adminUser = new AuthUser();
        adminUser.setId(901L);
        adminUser.setEmail("admin@example.com");
        adminUser.setRole(UserRole.ADMIN);
        adminUser.setEnabled(true);

        approverAdmin = new AuthUser();
        approverAdmin.setId(902L);
        approverAdmin.setEmail("approver@example.com");
        approverAdmin.setRole(UserRole.ADMIN);
        approverAdmin.setEnabled(true);

        projectA = new Project();
        projectA.setId(1L);
        projectA.setName("Project Alpha");
        projectA.setOwnerUserId(101L);
        projectA.setSourceType(SourceType.ZIP);
        projectA.setStatus(ProjectStatus.ANALYZED);

        projectB = new Project();
        projectB.setId(2L);
        projectB.setName("Project Beta");
        projectB.setOwnerUserId(102L);
        projectB.setSourceType(SourceType.ZIP);
        projectB.setStatus(ProjectStatus.ANALYZED);
    }

    @Test
    @DisplayName("1. User A truy cập Project A: thành công qua repository level query")
    void userAAccessProjectASuccess() {
        when(projectRepository.findByIdAndOwnerUserId(1L, 101L)).thenReturn(Optional.of(projectA));

        Project result = projectService.requireProjectOwner(1L, userA);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        verify(projectRepository).findByIdAndOwnerUserId(1L, 101L);
    }

    @Test
    @DisplayName("2. User A truy cập Project B: bị từ chối 403 Forbidden")
    void userAAccessProjectBDenied() {
        when(projectRepository.findByIdAndOwnerUserId(2L, 101L)).thenReturn(Optional.empty());
        when(projectRepository.existsById(2L)).thenReturn(true);

        assertThatThrownBy(() -> projectService.requireProjectOwner(2L, userA))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> {
                    AuthException ae = (AuthException) e;
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ae.getCode()).isEqualTo("FORBIDDEN");
                });
    }

    @Test
    @DisplayName("3. User B truy cập Project A: bị từ chối 403 Forbidden")
    void userBAccessProjectADenied() {
        when(projectRepository.findByIdAndOwnerUserId(1L, 102L)).thenReturn(Optional.empty());
        when(projectRepository.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> projectService.requireProjectOwner(1L, userB))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> {
                    AuthException ae = (AuthException) e;
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    @Test
    @DisplayName("4. Admin xem metadata Project: được phép")
    void adminViewMetadataAllowed() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(projectA));

        ProjectDto dto = projectService.getById(1L, adminUser);

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.name()).isEqualTo("Project Alpha");
    }

    @Test
    @DisplayName("5. Admin đọc source Project khi chưa có Break-Glass: bị từ chối 403 Forbidden")
    void adminReadSourceWithoutBreakGlassDenied() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(projectA));
        when(grantRepository.findActiveGrants(eq(1L), eq(901L), any())).thenReturn(List.of());

        assertThatThrownBy(() -> projectService.requireSourceAccess(1L, adminUser))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> {
                    AuthException ae = (AuthException) e;
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ae.getMessage()).contains("Break-Glass");
                });
    }

    @Test
    @DisplayName("6. Admin download source archive khi chưa có Break-Glass: bị từ chối 403 Forbidden")
    void adminDownloadSourceWithoutBreakGlassDenied() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(projectA));
        when(grantRepository.findActiveGrants(eq(1L), eq(901L), any())).thenReturn(List.of());

        assertThatThrownBy(() -> projectService.requireSourceAccess(1L, adminUser))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("Break-Glass");
    }

    @Test
    @DisplayName("7. Admin export dữ liệu khách hàng khi chưa có Break-Glass: bị từ chối 403 Forbidden")
    void adminExportDataWithoutBreakGlassDenied() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(projectA));
        when(grantRepository.findActiveGrants(eq(1L), eq(901L), any())).thenReturn(List.of());

        assertThatThrownBy(() -> projectService.requireSourceAccess(1L, adminUser))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("8. User A cố chạy test run trên Project B: bị từ chối 403 Forbidden")
    void userARunTestOnProjectBDenied() {
        when(projectRepository.findByIdAndOwnerUserId(2L, 101L)).thenReturn(Optional.empty());
        when(projectRepository.existsById(2L)).thenReturn(true);

        assertThatThrownBy(() -> projectService.requireAccess(2L, userA))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("9. User A xem Test Case của Project B: bị từ chối 403 Forbidden")
    void userAViewTestCaseOfProjectBDenied() {
        when(projectRepository.findByIdAndOwnerUserId(2L, 101L)).thenReturn(Optional.empty());
        when(projectRepository.existsById(2L)).thenReturn(true);

        assertThatThrownBy(() -> projectService.requireAccess(2L, userA))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("10. Background Job chạy với actor không sở hữu project: bị từ chối")
    void backgroundJobWithInvalidActorDenied() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(projectA));

        // Actor 102 (User B) cố submit job trên project 1 của User A
        assertThatThrownBy(() -> generationJobService.submit(1L, 102L, GenerationProgressStage.UNIT_TEST, () -> {}))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("11. Quy trình Four-Eyes Break-Glass: Request -> Approve -> Truy cập -> Hết hạn/Revoke")
    void fourEyesBreakGlassWorkflowVerification() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(projectA));
        when(authUserRepository.findById(902L)).thenReturn(Optional.of(approverAdmin));

        BreakGlassGrant savedGrant = new BreakGlassGrant();
        savedGrant.setId(500L);
        savedGrant.setProjectId(1L);
        savedGrant.setAdminUserId(901L);
        savedGrant.setApproverUserId(902L);
        savedGrant.setReason("Sửa lỗi biên dịch cho khách hàng");
        savedGrant.setDurationMinutes(30);
        savedGrant.setStatus("PENDING");

        when(grantRepository.save(any(BreakGlassGrant.class))).thenReturn(savedGrant);

        // 11a. Admin 901 gửi yêu cầu Break-Glass (trạng thái ban đầu: PENDING, active = false)
        BreakGlassRequestDto req = new BreakGlassRequestDto(1L, 902L, "Sửa lỗi biên dịch cho khách hàng", 30);
        BreakGlassGrantDto requestedDto = breakGlassService.requestAccess(req, adminUser);

        assertThat(requestedDto).isNotNull();
        assertThat(requestedDto.id()).isEqualTo(500L);
        assertThat(requestedDto.status()).isEqualTo("PENDING");
        assertThat(requestedDto.active()).isFalse();

        // Khi đang PENDING: Admin 901 chưa có quyền truy cập
        when(grantRepository.findActiveGrants(eq(1L), eq(901L), any())).thenReturn(List.of());
        assertThatThrownBy(() -> projectService.requireSourceAccess(1L, adminUser))
                .isInstanceOf(AuthException.class);

        // 11b. Approver Admin 902 phê duyệt yêu cầu Break-Glass
        when(grantRepository.findById(500L)).thenReturn(Optional.of(savedGrant));
        BreakGlassGrant approvedGrant = new BreakGlassGrant();
        approvedGrant.setId(500L);
        approvedGrant.setProjectId(1L);
        approvedGrant.setAdminUserId(901L);
        approvedGrant.setApproverUserId(902L);
        approvedGrant.setReason(savedGrant.getReason());
        approvedGrant.setDurationMinutes(30);
        approvedGrant.setStatus("APPROVED");
        approvedGrant.setGrantedAt(LocalDateTime.now());
        approvedGrant.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        approvedGrant.setRevoked(false);

        when(grantRepository.save(any(BreakGlassGrant.class))).thenReturn(approvedGrant);
        BreakGlassGrantDto approvedDto = breakGlassService.approveAccess(500L, approverAdmin);

        assertThat(approvedDto.status()).isEqualTo("APPROVED");
        assertThat(approvedDto.active()).isTrue();

        // 11c. Sau khi được phê duyệt: Admin 901 truy cập thành công
        when(grantRepository.findActiveGrants(eq(1L), eq(901L), any())).thenReturn(List.of(approvedGrant));
        Project accessed = projectService.requireSourceAccess(1L, adminUser);
        assertThat(accessed).isNotNull();
        assertThat(accessed.getId()).isEqualTo(1L);

        // 11d. Khi hết hạn / revoke: findActiveGrants rỗng -> bị từ chối
        when(grantRepository.findActiveGrants(eq(1L), eq(901L), any())).thenReturn(List.of());
        assertThatThrownBy(() -> projectService.requireSourceAccess(1L, adminUser))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("12. Tách biệt người yêu cầu và người phê duyệt: Không thể tự phê duyệt chính mình")
    void breakGlassSelfApprovalRejected() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(projectA));

        BreakGlassRequestDto selfApproveReq = new BreakGlassRequestDto(1L, 901L, "Tự duyệt truy cập", 30);

        assertThatThrownBy(() -> breakGlassService.requestAccess(selfApproveReq, adminUser))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> {
                    AuthException ae = (AuthException) e;
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ae.getCode()).isEqualTo("SEPARATION_OF_DUTY");
                });
    }

    @Test
    @DisplayName("13. Người phê duyệt không phải ADMIN bị từ chối")
    void nonAdminApproverRejected() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(projectA));
        when(authUserRepository.findById(102L)).thenReturn(Optional.of(userB)); // userB has role USER

        BreakGlassRequestDto req = new BreakGlassRequestDto(1L, 102L, "Nhờ user duyệt", 30);

        assertThatThrownBy(() -> breakGlassService.requestAccess(req, adminUser))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> {
                    AuthException ae = (AuthException) e;
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ae.getCode()).isEqualTo("APPROVER_NOT_ADMIN");
                });
    }

    @Test
    @DisplayName("14. Admin truy cập project của chính mình tạo: được phép không cần Break-Glass")
    void adminAccessOwnProjectPermitted() {
        Project adminOwnedProject = new Project();
        adminOwnedProject.setId(99L);
        adminOwnedProject.setOwnerUserId(901L); // owned by adminUser (901L)

        when(projectRepository.findById(99L)).thenReturn(Optional.of(adminOwnedProject));

        Project accessed = projectService.requireSourceAccess(99L, adminUser);
        assertThat(accessed).isNotNull();
        assertThat(accessed.getId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("15. SourceUpdateService chặn User không sở hữu và Admin không có Break-Glass")
    void sourceUpdateServiceAccessControl() {
        // User A truy cập Project B -> 403 FORBIDDEN
        assertThatThrownBy(() -> sourceUpdateService.checkProjectAccess(projectB, userA))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Admin truy cập Project A mà không có Break-Glass -> 403 FORBIDDEN
        when(grantRepository.findActiveGrants(eq(1L), eq(901L), any())).thenReturn(List.of());
        assertThatThrownBy(() -> sourceUpdateService.checkProjectAccess(projectA, adminUser))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("16. Background job với project ownerUserId == null bị từ chối")
    void backgroundJobNullOwnerRejected() {
        Project unownedProject = new Project();
        unownedProject.setId(55L);
        unownedProject.setOwnerUserId(null);

        when(projectRepository.findById(55L)).thenReturn(Optional.of(unownedProject));

        assertThatThrownBy(() -> generationJobService.submit(55L, 101L, GenerationProgressStage.UNIT_TEST, () -> {}))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
