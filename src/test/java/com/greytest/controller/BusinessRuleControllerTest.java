package com.greytest.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.greytest.dto.BusinessRuleDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.UserRole;
import com.greytest.service.AuthService;
import com.greytest.service.BusinessRuleService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.ProjectService;

@WebMvcTest(BusinessRuleController.class)
class BusinessRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BusinessRuleService businessRuleService;

    @MockBean
    private AuthService authService;

    @MockBean
    private ProjectService projectService;

    @MockBean
    private GenerationJobService generationJobService;

    @Test
    void listChecksProjectAccess() throws Exception {
        AuthUser user = user();
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(businessRuleService.list(1L, "account-service")).thenReturn(List.of(rule()));

        mockMvc.perform(get("/api/projects/1/business-rules")
                        .param("servicePath", "account-service")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleCode").value("BR-001"));

        verify(projectService).requireAccess(1L, user);
        verify(businessRuleService).list(1L, "account-service");
    }

    @Test
    void updateUsesOwningProjectForAccessCheck() throws Exception {
        AuthUser user = user();
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(businessRuleService.projectIdForRule(2L)).thenReturn(1L);
        when(businessRuleService.update(org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(rule());
        when(generationJobService.executeMutation(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.<Supplier<BusinessRuleDto>>any()))
                .thenAnswer(invocation -> invocation.<Supplier<BusinessRuleDto>>getArgument(1).get());

        mockMvc.perform(put("/api/business-rules/2")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Moi endpoint REST phai co it nhat mot test case.",
                                  "status": "APPROVED",
                                  "reviewNote": "OK"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(1));

        verify(projectService).requireAccess(1L, user);
    }

    @Test
    void listRequiresAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/projects/1/business-rules"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ERROR"));
    }

    private BusinessRuleDto rule() {
        return new BusinessRuleDto(
                2L,
                1L,
                7L,
                "BR-001",
                "Moi endpoint REST phai co it nhat mot test case.",
                null,
                null,
                RuleSource.AI_GENERATED,
                ReviewStatus.PENDING_REVIEW,
                false,
                null,
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
