package com.greytest.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}
