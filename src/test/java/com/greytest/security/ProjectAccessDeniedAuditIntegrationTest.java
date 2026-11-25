package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.greytest.entity.AuthUser;
import com.greytest.entity.Project;
import com.greytest.entity.UserActivityLog;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.UserActivityLogRepository;
import com.greytest.service.ProjectService;

@SpringBootTest(properties = "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes")
class ProjectAccessDeniedAuditIntegrationTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AuthUserRepository userRepository;

    @Autowired
    private UserActivityLogRepository activityLogRepository;

    private AuthUser owner;
    private AuthUser intruder;
    private Project project;

    @BeforeEach
    void setUp() {
        owner = new AuthUser();
        owner.setEmail("audit-owner-" + System.nanoTime() + "@test.local");
        owner.setFullName("Audit Owner");
        owner.setPasswordHash("hash");
        owner.setRole(UserRole.USER);
        owner.setEnabled(true);
        owner = userRepository.save(owner);

        intruder = new AuthUser();
        intruder.setEmail("audit-intruder-" + System.nanoTime() + "@test.local");
        intruder.setFullName("Audit Intruder");
        intruder.setPasswordHash("hash");
        intruder.setRole(UserRole.USER);
        intruder.setEnabled(true);
        intruder = userRepository.save(intruder);

        project = new Project();
        project.setName("audit-project-" + System.nanoTime());
        project.setOwnerUserId(owner.getId());
        project.setSourceType(SourceType.ZIP);
        project.setStatus(ProjectStatus.ANALYZED);
        project = projectRepository.save(project);
    }

    @AfterEach
    void tearDown() {
        activityLogRepository.deleteAll();
        projectRepository.deleteById(project.getId());
        userRepository.deleteById(intruder.getId());
        userRepository.deleteById(owner.getId());
    }

    @Test
    @DisplayName("Audit log PROJECT_ACCESS_DENIED phải được commit vào PostgreSQL ngay cả khi caller ném AuthException từ readOnly transaction")
    void recordsAndCommitsAccessDeniedEvenWhenCallerThrowsAuthException() {
        // Intruder cố tình truy cập project của Owner qua phương thức readOnly requireAccess
        assertThatThrownBy(() -> projectService.requireAccess(project.getId(), intruder))
                .isInstanceOf(AuthException.class);

        // Kiểm tra audit log trong database PostgreSQL thật: phải tồn tại bản ghi PROJECT_ACCESS_DENIED
        List<UserActivityLog> logs = activityLogRepository.findAll().stream()
                .filter(l -> intruder.getId().equals(l.getUserId()) && ActivityAction.PROJECT_ACCESS_DENIED == l.getActionType())
                .toList();

        assertThat(logs).hasSize(1);
        UserActivityLog log = logs.get(0);
        assertThat(log.getRelatedProjectId()).isEqualTo(project.getId());
        assertThat(log.getMetadata()).containsKey("reason");
    }

    @Test
    @DisplayName("Xóa project không vi phạm FK và ghi nhận audit PROJECT_DELETED an toàn")
    void recordsProjectDeletedWithoutForeignKeyViolation() {
        projectService.delete(project.getId(), owner);

        List<UserActivityLog> logs = activityLogRepository.findAll().stream()
                .filter(l -> owner.getId().equals(l.getUserId()) && ActivityAction.PROJECT_DELETED == l.getActionType())
                .toList();

        assertThat(logs).hasSize(1);
        UserActivityLog log = logs.get(0);
        assertThat(log.getRelatedProjectId()).isNull();
        assertThat(log.getMetadata().get("deletedProjectId")).isEqualTo(project.getId().intValue());
    }
}
