package com.greytest.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.UserNotificationDto;
import com.greytest.dto.UserQuotaResponse;
import com.greytest.dto.admin.AdminDtos;
import com.greytest.entity.AuthUser;
import com.greytest.service.AdminService;
import com.greytest.service.AuthService;
import com.greytest.service.NotificationService;
import com.greytest.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final AuthService authService;
    private final AdminService adminService;
    private final NotificationService notificationService;

    public UserController(
            UserService userService,
            AuthService authService,
            AdminService adminService,
            NotificationService notificationService) {
        this.userService = userService;
        this.authService = authService;
        this.adminService = adminService;
        this.notificationService = notificationService;
    }

    /**
     * Nâng cấp tài khoản người dùng hiện tại lên gói PRO.
     */
    @PostMapping("/upgrade-pro")
    public UserQuotaResponse upgradeToPro(@RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return userService.upgradeToPro(user);
    }

    /**
     * Lấy thông tin hạn mức quota LLM của người dùng hiện tại.
     */
    @GetMapping("/quota")
    public UserQuotaResponse getQuota(@RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return userService.currentQuota(user);
    }

    /** Hủy gói nâng cấp của tài khoản đang đăng nhập và chuyển về Free ngay. */
    @PostMapping("/cancel-subscription")
    public UserQuotaResponse cancelSubscription(@RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return userService.cancelSubscription(user);
    }

    /** Người dùng gửi phản hồi / báo cáo sự cố cho admin xử lý. */
    @PostMapping("/feedback")
    public AdminDtos.UserFeedbackDto createFeedback(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody AdminDtos.SaveFeedbackRequest request) {
        AuthUser user = authService.currentUser(authorization);
        return adminService.createFeedback(user, request);
    }

    /** Lấy danh sách phản hồi mà tài khoản hiện tại đã gửi kèm trạng thái xử lý của Admin. */
    @GetMapping("/feedbacks")
    public List<AdminDtos.UserFeedbackDto> getUserFeedbacks(
            @RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return adminService.getUserFeedbacks(user.getId());
    }

    /** Lấy danh sách thông báo tổng hợp (admin, thanh toán, tác vụ hệ thống hoàn thành). */
    @GetMapping("/notifications")
    public List<UserNotificationDto> getNotifications(
            @RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return notificationService.getUserNotifications(user);
    }
}
