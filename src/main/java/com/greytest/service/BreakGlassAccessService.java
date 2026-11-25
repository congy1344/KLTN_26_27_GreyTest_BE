package com.greytest.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.admin.BreakGlassGrantDto;
import com.greytest.dto.admin.BreakGlassRequestDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.BreakGlassGrant;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.BreakGlassGrantRepository;
import com.greytest.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý cơ chế Break-Glass (truy cập khẩn cấp) cho phép Admin truy cập tạm thời vào dự án
 * của khách hàng nhằm mục đích hỗ trợ kỹ thuật hoặc xử lý sự cố.
 * Thực thi nghiêm ngặt:
 * 1. Nguyên tắc Four-Eyes / Separation of Duty: Admin yêu cầu và Admin phê duyệt phải là 2 người khác nhau.
 *    Yêu cầu được khởi tạo ở trạng thái PENDING và chỉ có hiệu lực sau khi Approver phê duyệt.
 * 2. Giới hạn thời gian tối đa (Time-boxed).
 * 3. Ghi vết kiểm toán đầy đủ (Full Audit Logging).
 * 4. Tự động hết hạn khi quá thời gian hiệu lực.
 */
@Slf4j
@Service
public class BreakGlassAccessService {

    private final BreakGlassGrantRepository grantRepository;
    private final ProjectRepository projectRepository;
    private final AuthUserRepository authUserRepository;
    private final UserActivityService activityService;

    @Autowired
    public BreakGlassAccessService(
            BreakGlassGrantRepository grantRepository,
            ProjectRepository projectRepository,
            AuthUserRepository authUserRepository,
            @Autowired(required = false) UserActivityService activityService) {
        this.grantRepository = grantRepository;
        this.projectRepository = projectRepository;
        this.authUserRepository = authUserRepository;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveBreakGlass(Long projectId, Long adminUserId) {
        if (projectId == null || adminUserId == null) {
            return false;
        }
        List<BreakGlassGrant> active = grantRepository.findActiveGrants(projectId, adminUserId, LocalDateTime.now());
        return !active.isEmpty();
    }

    /**
     * Bước 1: Admin tạo yêu cầu truy cập Break-Glass (trạng thái PENDING).
     */
    @Transactional
    public BreakGlassGrantDto requestAccess(BreakGlassRequestDto request, AuthUser requester) {
        requireAdmin(requester, "Chỉ tài khoản ADMIN mới có thể yêu cầu Break-Glass");
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(request.projectId()));

        if (request.approverUserId().equals(requester.getId())) {
            throw new AuthException("SEPARATION_OF_DUTY", "Người yêu cầu và người phê duyệt Break-Glass phải khác nhau", HttpStatus.BAD_REQUEST);
        }
        AuthUser approver = authUserRepository.findById(request.approverUserId())
                .orElseThrow(() -> new AuthException("INVALID_APPROVER", "Không tìm thấy tài khoản người phê duyệt", HttpStatus.BAD_REQUEST));
        if (approver.getRole() != UserRole.ADMIN) {
            throw new AuthException("APPROVER_NOT_ADMIN", "Người phê duyệt phải có vai trò ADMIN", HttpStatus.BAD_REQUEST);
        }

        int duration = (request.durationMinutes() != null && request.durationMinutes() > 0)
                ? Math.min(request.durationMinutes(), 120)
                : 30;

        LocalDateTime now = LocalDateTime.now();

        BreakGlassGrant grant = new BreakGlassGrant();
        grant.setProjectId(project.getId());
        grant.setAdminUserId(requester.getId());
        grant.setApproverUserId(approver.getId());
        grant.setReason(request.reason().trim());
        grant.setDurationMinutes(duration);
        grant.setStatus("PENDING");
        grant.setRevoked(false);
        grant.setCreatedAt(now);

        grant = grantRepository.save(grant);

        if (activityService != null) {
            activityService.record(requester.getId(), ActivityAction.ADMIN_BREAK_GLASS_REQUESTED, project.getId(),
                    Map.of(
                            "grantId", grant.getId(),
                            "approverUserId", approver.getId(),
                            "durationMinutes", duration,
                            "reason", grant.getReason()
                    ));
        }
        log.info("BREAK-GLASS REQUESTED: Admin {} requested access to Project {}, awaiting approval by Admin {}",
                requester.getId(), project.getId(), approver.getId());

        return toDto(grant);
    }

    /**
     * Bước 2: Admin được chỉ định (Approver) xác nhận phê duyệt yêu cầu Break-Glass.
     */
    @Transactional
    public BreakGlassGrantDto approveAccess(Long grantId, AuthUser approver) {
        requireAdmin(approver, "Chỉ tài khoản ADMIN mới có quyền phê duyệt Break-Glass");
        BreakGlassGrant grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Break-Glass grant: " + grantId));

        if (!approver.getId().equals(grant.getApproverUserId())) {
            throw new AuthException("NOT_DESIGNATED_APPROVER", "Bạn không phải là người phê duyệt được chỉ định cho yêu cầu này", HttpStatus.FORBIDDEN);
        }
        if (approver.getId().equals(grant.getAdminUserId())) {
            throw new AuthException("SEPARATION_OF_DUTY", "Không thể tự phê duyệt yêu cầu Break-Glass của chính mình", HttpStatus.BAD_REQUEST);
        }
        if (!"PENDING".equalsIgnoreCase(grant.getStatus())) {
            throw new IllegalStateException("Yêu cầu Break-Glass không ở trạng thái PENDING");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(grant.getDurationMinutes());

        grant.setStatus("APPROVED");
        grant.setGrantedAt(now);
        grant.setExpiresAt(expiresAt);

        grant = grantRepository.save(grant);

        if (activityService != null) {
            activityService.record(grant.getAdminUserId(), ActivityAction.ADMIN_BREAK_GLASS_GRANTED, grant.getProjectId(),
                    Map.of(
                            "grantId", grant.getId(),
                            "approverUserId", approver.getId(),
                            "durationMinutes", grant.getDurationMinutes(),
                            "expiresAt", expiresAt.toString()
                    ));
        }
        log.warn("BREAK-GLASS APPROVED: Grant {} for Project {} approved by Admin {} for Admin {}. Expires at {}",
                grant.getId(), grant.getProjectId(), approver.getId(), grant.getAdminUserId(), expiresAt);

        return toDto(grant);
    }

    @Transactional
    public void revokeGrant(Long grantId, AuthUser admin) {
        requireAdmin(admin, "Chỉ tài khoản ADMIN mới có quyền thu hồi Break-Glass");
        BreakGlassGrant grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Break-Glass grant: " + grantId));
        grant.setRevoked(true);
        grant.setStatus("REVOKED");
        grantRepository.save(grant);

        if (activityService != null) {
            activityService.record(admin.getId(), ActivityAction.ADMIN_BREAK_GLASS_REVOKED, grant.getProjectId(),
                    Map.of("grantId", grant.getId(), "revokedBy", admin.getId()));
        }
        log.info("BREAK-GLASS REVOKED: Grant {} for Project {} revoked by Admin {}", grantId, grant.getProjectId(), admin.getId());
    }

    @Transactional(readOnly = true)
    public List<BreakGlassGrantDto> listActiveGrants(AuthUser admin) {
        requireAdmin(admin, "Chỉ tài khoản ADMIN mới có quyền xem danh sách Break-Glass");
        return grantRepository.findAllActive(LocalDateTime.now()).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<BreakGlassGrantDto> listPendingRequests(AuthUser admin) {
        requireAdmin(admin, "Chỉ tài khoản ADMIN mới có quyền xem danh sách Break-Glass");
        return grantRepository.findByStatusOrderByCreatedAtDesc("PENDING").stream().map(this::toDto).toList();
    }

    private void requireAdmin(AuthUser user, String message) {
        if (user == null || user.getRole() != UserRole.ADMIN) {
            throw new AuthException("ADMIN_REQUIRED", message, HttpStatus.FORBIDDEN);
        }
    }

    private BreakGlassGrantDto toDto(BreakGlassGrant g) {
        boolean active = "APPROVED".equalsIgnoreCase(g.getStatus())
                && !g.isRevoked()
                && g.getExpiresAt() != null
                && g.getExpiresAt().isAfter(LocalDateTime.now());
        return new BreakGlassGrantDto(
                g.getId(), g.getProjectId(), g.getAdminUserId(), g.getApproverUserId(),
                g.getReason(), g.getDurationMinutes(), g.getStatus(), g.getGrantedAt(), g.getExpiresAt(), active);
    }
}
