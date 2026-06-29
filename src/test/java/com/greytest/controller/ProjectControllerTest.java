package com.greytest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.greytest.dto.ProjectDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.entity.enums.UserRole;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;

@WebMvcTest(ProjectController.class)
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectService projectService;

    @MockBean
    private AuthService authService;

    private ProjectDto sampleDto() {
        return new ProjectDto(1L, "demo", SourceType.ZIP, null, ProjectStatus.ANALYZED, LocalDateTime.now(), null, true);
    }

    private AuthUser user() {
        AuthUser user = new AuthUser();
        user.setId(10L);
        user.setRole(UserRole.USER);
        user.setEnabled(true);
        return user;
    }

    @Test
    void uploadReturnsCreated() throws Exception {
        when(authService.optionalCurrentUser(any())).thenReturn(Optional.empty());
        when(projectService.createFromZip(any(), any())).thenReturn(sampleDto());

        mockMvc.perform(multipart("/api/projects/upload")
                        .file(new MockMultipartFile("file", "demo.zip", "application/zip", new byte[] {1})))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ANALYZED"));
    }

    @Test
    void githubRejectsBlankUrl() throws Exception {
        mockMvc.perform(post("/api/projects/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getReturnsProject() throws Exception {
        AuthUser user = user();
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(projectService.getById(1L, user)).thenReturn(sampleDto());

        mockMvc.perform(get("/api/projects/1").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("demo"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        AuthUser user = user();
        when(authService.currentUser("Bearer token")).thenReturn(user);

        mockMvc.perform(delete("/api/projects/1").header("Authorization", "Bearer token"))
                .andExpect(status().isNoContent());
    }
}
