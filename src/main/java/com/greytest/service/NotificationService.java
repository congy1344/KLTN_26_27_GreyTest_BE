package com.greytest.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.greytest.dto.UserNotificationDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.PaymentTransaction;
import com.greytest.entity.Project;
import com.greytest.entity.UserActivityLog;
import com.greytest.entity.UserFeedback;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.PaymentStatus;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.repository.PaymentTransactionRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.UserActivityLogRepository;
import com.greytest.repository.UserFeedbackRepository;

/**
 * Service tổng hợp thông báo cho người dùng:
 * 1. ADMIN: Phản hồi từ quản trị viên, thông báo hệ thống, thay đổi quyền/hạn mức.
 * 2. PAYMENT: Kết quả giao dịch thanh toán, nâng cấp hoặc hủy gói dịch vụ.
 * 3. TASK: Các tác vụ sinh test, phân tích mã nguồn AST, hoặc JaCoCo hoàn thành.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final UserFeedbackRepository feedbackRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final UserActivityLogRepository activityRepository;
    private final ProjectRepository projectRepository;

    @Autowired
    public NotificationService(
            @Autowired(required = false) UserFeedbackRepository feedbackRepository,
            PaymentTransactionRepository paymentRepository,
            UserActivityLogRepository activityRepository,
            ProjectRepository projectRepository) {
        this.feedbackRepository = feedbackRepository;
        this.paymentRepository = paymentRepository;
        this.activityRepository = activityRepository;
        this.projectRepository = projectRepository;
    }

    public List<UserNotificationDto> getUserNotifications(AuthUser user) {
        if (user == null || user.getId() == null) {
            return List.of();
        }
        Long userId = user.getId();
        List<UserNotificationDto> notifications = new ArrayList<>();

        // 1. Phản hồi và thông báo từ Quản trị viên (Admin)
        if (feedbackRepository != null) {
            try {
                List<UserFeedback> feedbacks = feedbackRepository.findByUserIdOrderByCreatedAtDesc(userId);
                for (UserFeedback fb : feedbacks) {
                    boolean hasAdminResponse = (fb.getAdminNote() != null && !fb.getAdminNote().isBlank())
                            || (fb.getStatus() != null && !"NEW".equalsIgnoreCase(fb.getStatus()));
                    if (hasAdminResponse) {
                        String note = fb.getAdminNote() != null && !fb.getAdminNote().isBlank()
                                ? ": " + fb.getAdminNote().trim()
                                : "";
                        String statusText;
                        if ("RESOLVED".equalsIgnoreCase(fb.getStatus())) {
                            statusText = "Đã giải quyết";
                        } else if ("IN_PROGRESS".equalsIgnoreCase(fb.getStatus())) {
                            statusText = "Đang xử lý";
                        } else if ("REJECTED".equalsIgnoreCase(fb.getStatus())) {
                            statusText = "Từ chối";
                        } else if ("CLOSED".equalsIgnoreCase(fb.getStatus())) {
                            statusText = "Đã đóng";
                        } else if ("NEW".equalsIgnoreCase(fb.getStatus())) {
                            statusText = "Đã phản hồi";
                        } else {
                            statusText = fb.getStatus() != null ? fb.getStatus() : "Đã cập nhật";
                        }
                        String notifId = "feedback-" + fb.getId()
                                + (fb.getUpdatedAt() != null
                                        ? "-" + fb.getUpdatedAt().toString().replaceAll("[^a-zA-Z0-9]", "")
                                        : "-" + (fb.getStatus() != null ? fb.getStatus().toLowerCase() : "new"));
                        String feedbackTitle = fb.getTitle() != null && !fb.getTitle().isBlank()
                                ? fb.getTitle()
                                : "Phản hồi";
                        notifications.add(new UserNotificationDto(
                                notifId,
                                "ADMIN",
                                "Phản hồi từ Quản trị viên (" + statusText + ")",
                                feedbackTitle + note,
                                "/settings?tab=feedback",
                                fb.getUpdatedAt() != null ? fb.getUpdatedAt() : fb.getCreatedAt(),
                                false
                        ));
                    }
                }
            } catch (Exception e) {
                log.warn("Không thể tải thông báo phản hồi người dùng: {}", e.getMessage());
            }
        }

        // 2. Giao dịch thanh toán & thay đổi gói dịch vụ
        if (paymentRepository != null) {
            try {
                List<PaymentTransaction> transactions = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId);
                for (PaymentTransaction txn : transactions) {
                    // Bỏ qua trạng thái PENDING đang chờ người dùng thanh toán trên cổng VNPay
                    if (txn.getStatus() == PaymentStatus.PENDING) {
                        continue;
                    }
                    boolean isSuccess = txn.getStatus() == PaymentStatus.SUCCESS;
                    String statusText = isSuccess ? "thành công" : "không thành công";
                    String title = "Thanh toán gói " + txn.getTier() + " " + statusText;
                    long amount = txn.getAmount() != null ? txn.getAmount() : 0L;
                    String message = String.format("Mã đơn hàng: %s (%,d đ qua %s)",
                            txn.getOrderId() != null ? txn.getOrderId() : "N/A",
                            amount,
                            txn.getVnpBankCode() != null ? txn.getVnpBankCode() : "VNPay");
                    notifications.add(new UserNotificationDto(
                            "payment-" + txn.getId(),
                            "PAYMENT",
                            title,
                            message,
                            "/settings?tab=subscription",
                            txn.getCreatedAt(),
                            false
                    ));
                }
            } catch (Exception e) {
                log.warn("Không thể tải thông báo giao dịch thanh toán: {}", e.getMessage());
            }
        }

        // 3. Tác vụ hệ thống thực hiện xong (Pipeline & Phân tích)
        Map<Long, String> projectNames = Map.of();
        if (projectRepository != null) {
            try {
                List<Project> userProjects = projectRepository.findByOwnerUserIdOrderByCreatedAtDesc(userId);
                projectNames = userProjects.stream()
                        .filter(p -> p.getId() != null && p.getName() != null)
                        .collect(Collectors.toMap(Project::getId, Project::getName, (existing, replacement) -> existing));

                for (Project p : userProjects) {
                    if (p.getStatus() != null && p.getStatus() != ProjectStatus.UPLOADED && p.getStatus() != ProjectStatus.FAILED) {
                        notifications.add(new UserNotificationDto(
                                "project-analysis-" + p.getId(),
                                "TASK",
                                "Phân tích mã nguồn hoàn tất",
                                "Dự án “" + (p.getName() != null ? p.getName() : "Dự án") + "” đã hoàn thành phân tích cấu trúc.",
                                "/projects/" + p.getId(),
                                p.getUpdatedAt() != null ? p.getUpdatedAt() : p.getCreatedAt(),
                                false
                        ));
                    }
                }
            } catch (Exception e) {
                log.warn("Không thể tải thông báo trạng thái dự án: {}", e.getMessage());
            }
        }

        if (activityRepository != null) {
            try {
                List<UserActivityLog> activities = activityRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 30));
                for (UserActivityLog logItem : activities) {
                    ActivityAction action = logItem.getActionType();
                    if (action == null) continue;

                    String projName = logItem.getRelatedProjectId() != null ? projectNames.get(logItem.getRelatedProjectId()) : null;
                    String projSuffix = projName != null ? " cho dự án “" + projName + "”" : "";
                    String link = logItem.getRelatedProjectId() != null ? "/projects/" + logItem.getRelatedProjectId() : "/projects";

                    switch (action) {
                        case GENERATE_BUSINESS_RULE -> notifications.add(new UserNotificationDto(
                                "act-" + logItem.getId(),
                                "TASK",
                                "Sinh Business Rules hoàn thành",
                                "Hệ thống đã trích xuất Business Rules" + projSuffix + ".",
                                link,
                                logItem.getCreatedAt(),
                                false
                        ));
                        case GENERATE_TEST_PLAN -> notifications.add(new UserNotificationDto(
                                "act-" + logItem.getId(),
                                "TASK",
                                "Sinh Test Plan hoàn thành",
                                "Kế hoạch kiểm thử đã được tạo" + projSuffix + ".",
                                link,
                                logItem.getCreatedAt(),
                                false
                        ));
                        case GENERATE_TEST_CASE -> notifications.add(new UserNotificationDto(
                                "act-" + logItem.getId(),
                                "TASK",
                                "Sinh Test Cases hoàn thành",
                                "Danh sách ca kiểm thử đã được tạo" + projSuffix + ".",
                                link,
                                logItem.getCreatedAt(),
                                false
                        ));
                        case GENERATE_UNIT_TEST -> notifications.add(new UserNotificationDto(
                                "act-" + logItem.getId(),
                                "TASK",
                                "Sinh Unit Test (JUnit 5) hoàn thành",
                                "Bộ mã kiểm thử Unit Test đã được sinh" + projSuffix + ".",
                                link,
                                logItem.getCreatedAt(),
                                false
                        ));
                        case COVERAGE_REFINEMENT -> notifications.add(new UserNotificationDto(
                                "act-" + logItem.getId(),
                                "TASK",
                                "Tối ưu độ phủ JaCoCo hoàn thành",
                                "Đã phân tích độ phủ JaCoCo" + projSuffix + ".",
                                link,
                                logItem.getCreatedAt(),
                                false
                        ));
                        case USER_UPGRADE_TIER -> notifications.add(new UserNotificationDto(
                                "act-" + logItem.getId(),
                                "PAYMENT",
                                "Nâng cấp gói dịch vụ thành công",
                                "Tài khoản của bạn đã được nâng cấp.",
                                "/settings?tab=subscription",
                                logItem.getCreatedAt(),
                                false
                        ));
                        case USER_CANCEL_TIER -> notifications.add(new UserNotificationDto(
                                "act-" + logItem.getId(),
                                "PAYMENT",
                                "Hủy gói dịch vụ thành công",
                                "Tài khoản của bạn đã được chuyển về gói FREE.",
                                "/settings?tab=subscription",
                                logItem.getCreatedAt(),
                                false
                        ));
                        default -> {}
                    }
                }
            } catch (Exception e) {
                log.warn("Không thể tải thông báo nhật ký hoạt động: {}", e.getMessage());
            }
        }

        // Sắp xếp thời gian mới nhất lên đầu, giới hạn 50 thông báo gần nhất
        return notifications.stream()
                .filter(n -> n.createdAt() != null)
                .sorted(Comparator.comparing(UserNotificationDto::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(50)
                .toList();
    }
}
