package com.greytest.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.greytest.dto.ExistingTestDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.analysis.ExistingTestService;

@WebMvcTest(ExistingTestController.class)
class ExistingTestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExistingTestService existingTestService;

    @MockBean
    private AuthService authService;

    @MockBean
    private ProjectService projectService;

    @Test
    void listChecksProjectAccess() throws Exception {
        AuthUser user = user();
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(existingTestService.list(1L)).thenReturn(List.of(testFile()));

        mockMvc.perform(get("/api/projects/1/existing-tests").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].testClassName").value("UserServiceTest"));

        verify(projectService).requireAccess(1L, user);
    }

    @Test
    void listRequiresAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/projects/1/existing-tests"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ERROR"));
    }

    private ExistingTestDto testFile() {
        return new ExistingTestDto(
                3L,
                1L,
                "src/test/java/demo/UserServiceTest.java",
                "demo",
                "UserServiceTest",
                null,
                null,
                List.of(),
                List.of(),
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
