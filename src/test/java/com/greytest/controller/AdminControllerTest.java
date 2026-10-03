package com.greytest.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.greytest.config.AdminAuthorizationInterceptor;
import com.greytest.dto.admin.AdminDtos.OverviewDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.GlobalExceptionHandler;
import com.greytest.service.AdminService;
import com.greytest.service.AuthService;

class AdminControllerTest {

    private AdminService adminService;
    private AuthService authService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        authService = mock(AuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminController(adminService))
                .addInterceptors(new AdminAuthorizationInterceptor(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void rejectsNonAdminOnEveryAdminRoute() throws Exception {
        when(authService.currentUser("Bearer user-token")).thenReturn(user(UserRole.USER));

        mvc.perform(get("/api/admin/stats/overview").header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
    }

    @Test
    void returnsOverviewForAdmin() throws Exception {
        when(authService.currentUser("Bearer admin-token")).thenReturn(user(UserRole.ADMIN));
        when(adminService.overview()).thenReturn(new OverviewDto(10, 2, 5, 30, 45, 1));

        mvc.perform(get("/api/admin/stats/overview").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(10))
                .andExpect(jsonPath("$.totalLlmCalls").value(45))
                .andExpect(jsonPath("$.quotaAlerts").value(1));
    }

    private AuthUser user(UserRole role) {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    @Test
    void forwardsLockReasonToService() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(patch("/api/admin/users/2/status").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"Test account\"}"))
                .andExpect(status().isOk());

        verify(adminService).updateStatus(admin, 2L, false, "Test account");
    }

    @Test
    void rejectsLockReasonLongerThan500Characters() throws Exception {
        when(authService.currentUser("Bearer admin-token")).thenReturn(user(UserRole.ADMIN));

        mvc.perform(patch("/api/admin/users/2/status").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminService);
    }

    @Test
    void forwardsUnlimitedQuotaAsNull() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(patch("/api/admin/users/2/quota").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quotaLimit\":null}"))
                .andExpect(status().isOk());

        verify(adminService).updateQuota(admin, 2L, null);
    }

    @Test
    void forwardsTierUpdateToService() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(patch("/api/admin/users/2/tier").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tier\":\"PRO\"}"))
                .andExpect(status().isOk());

        verify(adminService).updateTier(admin, 2L, com.greytest.entity.enums.UserTier.PRO);
    }
}
