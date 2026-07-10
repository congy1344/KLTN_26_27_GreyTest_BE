package com.greytest.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.greytest.dto.TestPlanDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.TestType;
import com.greytest.entity.enums.UserRole;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.TestPlanService;

@WebMvcTest(TestPlanController.class)
class TestPlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TestPlanService testPlanService;

    @MockBean
    private AuthService authService;

    @MockBean
    private ProjectService projectService;

    @Test
    void listReturnsTestPlans() throws Exception {
        AuthUser user = user();
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(testPlanService.list(1L)).thenReturn(List.of(plan()));

        mockMvc.perform(get("/api/projects/1/test-plans").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].planCode").value("TP-001"));

        verify(projectService).requireAccess(1L, user);
    }

    @Test
    void generateReturnsTestPlans() throws Exception {
        AuthUser user = user();
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(testPlanService.generate(1L)).thenReturn(List.of(plan()));

        mockMvc.perform(post("/api/projects/1/test-plans/generate").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].testType").value("HAPPY_PATH"));

        verify(projectService).requireAccess(1L, user);
    }

    @Test
    void createRejectsBlankTitle() throws Exception {
        mockMvc.perform(post("/api/projects/1/test-plans")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "businessRuleId": 7,
                                  "title": "",
                                  "description": "Mo ta",
                                  "testType": "HAPPY_PATH"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateUsesOwningProjectForAccessCheck() throws Exception {
        AuthUser user = user();
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(testPlanService.projectIdForPlan(2L)).thenReturn(1L);
        when(testPlanService.update(org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan());

        mockMvc.perform(put("/api/test-plans/2")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "businessRuleId": 7,
                                  "title": "Happy path",
                                  "description": "Du lieu hop le thi thanh cong.",
                                  "testType": "HAPPY_PATH"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2));

        verify(projectService).requireAccess(1L, user);
    }

    @Test
    void listRequiresAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/projects/1/test-plans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ERROR"));
    }

    private TestPlanDto plan() {
        return new TestPlanDto(
                2L,
                1L,
                7L,
                "TP-001",
                "Happy path",
                "Du lieu hop le thi thanh cong.",
                TestType.HAPPY_PATH,
                ReviewStatus.PENDING_REVIEW,
                false,
                null);
    }

    private AuthUser user() {
        AuthUser user = new AuthUser();
        user.setId(10L);
        user.setRole(UserRole.USER);
        user.setEnabled(true);
        return user;
    }
}
