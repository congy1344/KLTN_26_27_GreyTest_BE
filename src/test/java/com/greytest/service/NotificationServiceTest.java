package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.greytest.dto.UserNotificationDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.PaymentTransaction;
import com.greytest.entity.Project;
import com.greytest.entity.UserActivityLog;
import com.greytest.entity.UserFeedback;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.PaymentStatus;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.PaymentTransactionRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.UserActivityLogRepository;
import com.greytest.repository.UserFeedbackRepository;

class NotificationServiceTest {

    private UserFeedbackRepository feedbackRepository;
    private PaymentTransactionRepository paymentRepository;
    private UserActivityLogRepository activityRepository;
    private ProjectRepository projectRepository;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        feedbackRepository = mock(UserFeedbackRepository.class);
        paymentRepository = mock(PaymentTransactionRepository.class);
        activityRepository = mock(UserActivityLogRepository.class);
        projectRepository = mock(ProjectRepository.class);

        notificationService = new NotificationService(
                feedbackRepository,
                paymentRepository,
                activityRepository,
                projectRepository
        );
    }

    @Test
    void returnsEmptyListWhenUserIsNull() {
        assertThat(notificationService.getUserNotifications(null)).isEmpty();
    }

    @Test
    void aggregatesAdminPaymentAndTaskNotifications() {
        AuthUser user = new AuthUser();
        user.setId(10L);

        // 1. Admin Feedback
        UserFeedback fb = new UserFeedback();
        fb.setId(1L);
        fb.setUserId(10L);
        fb.setTitle("Lỗi phân tích class");
        fb.setStatus("RESOLVED");
        fb.setAdminNote("Đã sửa lỗi");
        fb.setCreatedAt(LocalDateTime.now().minusHours(2));
        fb.setUpdatedAt(LocalDateTime.now().minusHours(1));
        when(feedbackRepository.findByUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(fb));

        // 2. Payment Transaction
        PaymentTransaction txn = new PaymentTransaction();
        txn.setId(2L);
        txn.setUserId(10L);
        txn.setOrderId("GT_12345");
        txn.setAmount(20000L);
        txn.setTier(UserTier.PRO);
        txn.setStatus(PaymentStatus.SUCCESS);
        txn.setVnpBankCode("NCB");
        txn.setCreatedAt(LocalDateTime.now().minusDays(1));
        when(paymentRepository.findByUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(txn));

        // 3. Project & Task completion
        Project proj = new Project();
        proj.setId(5L);
        proj.setName("Demo App");
        proj.setStatus(ProjectStatus.ANALYZED);
        proj.setCreatedAt(LocalDateTime.now().minusHours(3));
        when(projectRepository.findByOwnerUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(proj));

        UserActivityLog act = new UserActivityLog();
        act.setId(3L);
        act.setUserId(10L);
        act.setActionType(ActivityAction.GENERATE_UNIT_TEST);
        act.setRelatedProjectId(5L);
        act.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        when(activityRepository.findByUserIdOrderByCreatedAtDesc(eq(10L), any(Pageable.class))).thenReturn(List.of(act));

        List<UserNotificationDto> result = notificationService.getUserNotifications(user);

        assertThat(result).hasSize(4);
        // Sorted newest first: act (-30m) -> fb (-1h) -> proj (-3h) -> txn (-1d)
        assertThat(result.get(0).type()).isEqualTo("TASK");
        assertThat(result.get(0).title()).contains("Sinh Unit Test");
        assertThat(result.get(0).message()).contains("Demo App");

        assertThat(result.get(1).type()).isEqualTo("ADMIN");
        assertThat(result.get(1).title()).contains("Phản hồi từ Quản trị viên");
        assertThat(result.get(1).message()).contains("Đã sửa lỗi");

        assertThat(result.get(2).type()).isEqualTo("TASK");
        assertThat(result.get(2).title()).contains("Phân tích mã nguồn hoàn tất");

        assertThat(result.get(3).type()).isEqualTo("PAYMENT");
        assertThat(result.get(3).title()).contains("Thanh toán gói PRO thành công");
    }

    @Test
    void ignoresPendingPaymentsAndUploadedProjects() {
        AuthUser user = new AuthUser();
        user.setId(20L);

        // Pending payment should be skipped
        PaymentTransaction pendingTxn = new PaymentTransaction();
        pendingTxn.setId(99L);
        pendingTxn.setUserId(20L);
        pendingTxn.setStatus(PaymentStatus.PENDING);
        pendingTxn.setCreatedAt(LocalDateTime.now());
        when(paymentRepository.findByUserIdOrderByCreatedAtDesc(20L)).thenReturn(List.of(pendingTxn));

        // UPLOADED and FAILED projects should not trigger analysis complete notification
        Project uploadedProj = new Project();
        uploadedProj.setId(101L);
        uploadedProj.setStatus(ProjectStatus.UPLOADED);
        uploadedProj.setCreatedAt(LocalDateTime.now());

        Project failedProj = new Project();
        failedProj.setId(102L);
        failedProj.setStatus(ProjectStatus.FAILED);
        failedProj.setCreatedAt(LocalDateTime.now());

        Project brReviewProj = new Project();
        brReviewProj.setId(103L);
        brReviewProj.setName("BR Proj");
        brReviewProj.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        LocalDateTime updateTime = LocalDateTime.now().minusMinutes(5);
        brReviewProj.setCreatedAt(LocalDateTime.now().minusDays(10));
        brReviewProj.setUpdatedAt(updateTime);

        when(projectRepository.findByOwnerUserIdOrderByCreatedAtDesc(20L))
                .thenReturn(List.of(uploadedProj, failedProj, brReviewProj));

        List<UserNotificationDto> result = notificationService.getUserNotifications(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("project-analysis-103");
        assertThat(result.get(0).createdAt()).isEqualTo(updateTime);
        assertThat(result.get(0).title()).isEqualTo("Phân tích mã nguồn hoàn tất");
    }

    @Test
    void filtersOutUnansweredFeedbackAndFormatsDynamicNotifId() {
        AuthUser user = new AuthUser();
        user.setId(30L);

        // Feedback mới chưa có phản hồi của admin -> không sinh thông báo
        UserFeedback unhandledFb = new UserFeedback();
        unhandledFb.setId(10L);
        unhandledFb.setUserId(30L);
        unhandledFb.setStatus("NEW");
        unhandledFb.setAdminNote(null);
        unhandledFb.setCreatedAt(LocalDateTime.now().minusHours(1));

        // Feedback mới nhưng admin đã phản hồi note -> sinh thông báo "Đã phản hồi"
        UserFeedback repliedFb = new UserFeedback();
        repliedFb.setId(11L);
        repliedFb.setUserId(30L);
        repliedFb.setStatus("NEW");
        repliedFb.setAdminNote("Đội ngũ kỹ thuật đang kiểm tra");
        LocalDateTime fbUpdatedAt = LocalDateTime.now().minusMinutes(10);
        repliedFb.setCreatedAt(LocalDateTime.now().minusHours(2));
        repliedFb.setUpdatedAt(fbUpdatedAt);

        when(feedbackRepository.findByUserIdOrderByCreatedAtDesc(30L))
                .thenReturn(List.of(repliedFb, unhandledFb));

        List<UserNotificationDto> result = notificationService.getUserNotifications(user);

        assertThat(result).hasSize(1);
        UserNotificationDto notif = result.get(0);
        assertThat(notif.type()).isEqualTo("ADMIN");
        assertThat(notif.title()).isEqualTo("Phản hồi từ Quản trị viên (Đã phản hồi)");
        assertThat(notif.message()).contains("Đội ngũ kỹ thuật đang kiểm tra");
        assertThat(notif.id()).startsWith("feedback-11-");
    }
}
