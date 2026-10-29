package com.greytest.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.greytest.dto.UserQuotaResponse;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserTier;
import com.greytest.exception.AuthException;
import com.greytest.exception.GlobalExceptionHandler;
import com.greytest.service.AuthService;
import com.greytest.service.UserService;

class UserControllerTest {
    private final AuthService authService = mock(AuthService.class);
    private final UserService userService = mock(UserService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UserController(userService, authService))
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
}
