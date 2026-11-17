package com.greytest.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.greytest.dto.UserQuotaResponse;
import com.greytest.dto.admin.AdminDtos;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserTier;
import com.greytest.exception.AuthException;
import com.greytest.exception.GlobalExceptionHandler;
import com.greytest.service.AdminService;
import com.greytest.service.AuthService;
import com.greytest.service.NotificationService;
import com.greytest.service.UserService;

class UserControllerTest {
    private final AuthService authService = mock(AuthService.class);
    private final UserService userService = mock(UserService.class);
    private final AdminService adminService = mock(AdminService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UserController(userService, authService, adminService, notificationService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void cancellationUsesAuthenticatedUserInsteadOfUserIdFromBody() throws Exception {
        AuthUser user = new AuthUser();
        user.setId(42L);
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(userService.cancelSubscription(user)).thenReturn(new UserQuotaResponse(
                UserTier.FREE, 100, 25, 75, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 11, 1), false));

        mvc.perform(post("/api/user/cancel-subscription").header("Authorization", "Bearer token")
                .contentType("application/json").content("{\"userId\":99,\"tier\":\"PRO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.remaining").value(75));
        verify(userService).cancelSubscription(user);
        verifyNoMoreInteractions(userService);
    }

    @Test
    void cancellationRequiresAuthorizationHeader() throws Exception {
        mvc.perform(post("/api/user/cancel-subscription")).andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);
    }

    @Test
    void invalidSessionCannotCancelSubscription() throws Exception {
        when(authService.currentUser("Bearer invalid"))
                .thenThrow(new AuthException("AUTH_ERROR", "Token không hợp lệ", HttpStatus.UNAUTHORIZED));
        mvc.perform(post("/api/user/cancel-subscription").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);
    }

    @Test
    void submitUserFeedbackSuccessfully() throws Exception {
        AuthUser user = new AuthUser();
        user.setId(42L);
        user.setEmail("user@example.com");
        user.setFullName("User Test");
        when(authService.currentUser("Bearer token")).thenReturn(user);

        AdminDtos.UserFeedbackDto responseDto = new AdminDtos.UserFeedbackDto(
                1L, 42L, "user@example.com", "User Test", "BUG_GENERATION",
                "Lỗi sinh test service", "Mô tả chi tiết lỗi", "NEW", null,
                LocalDateTime.now(), null, "Project A");

        when(adminService.createFeedback(eq(user), any(AdminDtos.SaveFeedbackRequest.class)))
                .thenReturn(responseDto);

        mvc.perform(post("/api/user/feedback")
                .header("Authorization", "Bearer token")
                .contentType("application/json")
                .content("{\"category\":\"BUG_GENERATION\",\"title\":\"Lỗi sinh test service\",\"content\":\"Mô tả chi tiết lỗi\",\"projectName\":\"Project A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Lỗi sinh test service"))
                .andExpect(jsonPath("$.category").value("BUG_GENERATION"))
                .andExpect(jsonPath("$.status").value("NEW"));

        verify(adminService).createFeedback(eq(user), any(AdminDtos.SaveFeedbackRequest.class));
    }

    @Test
    void getUserFeedbacksReturnsUserList() throws Exception {
        AuthUser user = new AuthUser();
        user.setId(42L);
        when(authService.currentUser("Bearer token")).thenReturn(user);

        AdminDtos.UserFeedbackDto dto = new AdminDtos.UserFeedbackDto(
                1L, 42L, "user@example.com", "User Test", "OTHER",
                "Góp ý giao diện", "Thêm nút quay lại", "RESOLVED", "Admin ghi chú đã xử lý",
                LocalDateTime.now(), LocalDateTime.now(), null);

        when(adminService.getUserFeedbacks(42L)).thenReturn(List.of(dto));

        mvc.perform(get("/api/user/feedbacks").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$[0].adminNote").value("Admin ghi chú đã xử lý"));

        verify(adminService).getUserFeedbacks(42L);
    }

    @Test
    void getNotificationsReturnsList() throws Exception {
        AuthUser user = new AuthUser();
        user.setId(42L);
        when(authService.currentUser("Bearer token")).thenReturn(user);

        com.greytest.dto.UserNotificationDto notif = new com.greytest.dto.UserNotificationDto(
                "notif-1", "PAYMENT", "Thanh toán thành công", "Gói PRO", "/settings?tab=subscription",
                LocalDateTime.now(), false);

        when(notificationService.getUserNotifications(user)).thenReturn(List.of(notif));

        mvc.perform(get("/api/user/notifications").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("notif-1"))
                .andExpect(jsonPath("$[0].type").value("PAYMENT"))
                .andExpect(jsonPath("$[0].title").value("Thanh toán thành công"));

        verify(notificationService).getUserNotifications(user);
    }

    @Test
    void getNotificationsRequiresAuthorizationHeader() throws Exception {
        mvc.perform(get("/api/user/notifications")).andExpect(status().isUnauthorized());
        verifyNoInteractions(notificationService);
    }
}
