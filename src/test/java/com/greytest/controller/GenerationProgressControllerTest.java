package com.greytest.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.greytest.dto.GenerationProgressDto;
import com.greytest.dto.GenerationProgressLogDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.dto.GenerationProgressStepDto;
import com.greytest.dto.GenerationProgressStepStatus;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.service.AuthService;
import com.greytest.service.GenerationProgressService;
import com.greytest.service.ProjectService;

@WebMvcTest(GenerationProgressController.class)
class GenerationProgressControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private GenerationProgressService progressService;
    @MockBean private AuthService authService;
    @MockBean private ProjectService projectService;

    @Test
    void returnsAuthorizedProjectProgressWithLogs() throws Exception {
        AuthUser user = new AuthUser();
        user.setId(10L);
        user.setRole(UserRole.USER);
        user.setEnabled(true);
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(progressService.get(1L, GenerationProgressStage.TEST_PLAN)).thenReturn(
                new GenerationProgressDto(
                        GenerationProgressStage.TEST_PLAN,
                        GenerationProgressStatus.RUNNING,
                        50,
                        1,
                        2,
                        List.of(
                                new GenerationProgressStepDto(
                                        1, "Sinh Test Plan - batch 1/1",
                                        GenerationProgressStepStatus.COMPLETED, 100, null),
                                new GenerationProgressStepDto(
                                        2, "Kiểm tra và lưu Test Plan",
                                        GenerationProgressStepStatus.RUNNING, 0, null)),
                        List.of(new GenerationProgressLogDto(
                                Instant.parse("2026-08-14T12:00:00Z"), "Đã xử lý batch 1/1."))));

        mockMvc.perform(get("/api/projects/1/generation-progress/TEST_PLAN")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(50))
                .andExpect(jsonPath("$.completedSteps").value(1))
                .andExpect(jsonPath("$.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[1].label").value("Kiểm tra và lưu Test Plan"))
                .andExpect(jsonPath("$.logs[0].message").value("Đã xử lý batch 1/1."));

        verify(projectService).requireAccess(1L, user);
    }

    @Test
    void requiresAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/projects/1/generation-progress/TEST_PLAN"))
                .andExpect(status().isUnauthorized());
    }
}
