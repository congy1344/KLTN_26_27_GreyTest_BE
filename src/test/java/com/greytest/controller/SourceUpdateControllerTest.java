package com.greytest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.greytest.dto.SourceUpdateDto;
import com.greytest.dto.SourceUpdateItemDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.entity.enums.SourceUpdateStatus;
import com.greytest.entity.enums.SourceUpdateTargetType;
import com.greytest.entity.enums.UserRole;
import com.greytest.service.AuthService;
import com.greytest.service.SourceUpdateService;

@WebMvcTest(SourceUpdateController.class)
class SourceUpdateControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private SourceUpdateService sourceUpdateService;

        @MockBean
        private com.greytest.service.IncrementalGenerationService incrementalGenerationService;

        @MockBean
        private AuthService authService;

        @Test
        void uploadZipUpdateReturnsCreated() throws Exception {
                AuthUser user = user();
                when(authService.currentUser("token")).thenReturn(user);

                SourceUpdateDto dto = new SourceUpdateDto(
                                200L, 10L, 1L, 2L,
                                SourceUpdateStatus.DRAFT, 0, null, List.of(),
                                LocalDateTime.now(), LocalDateTime.now());
                when(sourceUpdateService.createZipUpdate(eq(10L), any(), any())).thenReturn(dto);

                MockMultipartFile file = new MockMultipartFile("file", "update.zip", "application/zip",
                                new byte[] { 1 });

                mockMvc.perform(multipart("/api/projects/10/source-updates/zip")
                                .file(file)
                                .header("Authorization", "token"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").value(200L))
                                .andExpect(status().is(201));
        }

        @Test
        void getUpdateReturnsUpdateDetails() throws Exception {
                AuthUser user = user();
                when(authService.currentUser("token")).thenReturn(user);

                SourceUpdateItemDto item = new SourceUpdateItemDto(
                                50L, 200L, SourceUpdateTargetType.METHOD, 101L, "com.demo.Service#doWork()",
                                SourceUpdateAction.UPDATE, "Changed signature", "{}", "{}",
                                SourceUpdateReviewStatus.PENDING, LocalDateTime.now(), LocalDateTime.now());

                SourceUpdateDto dto = new SourceUpdateDto(
                                200L, 10L, 1L, 2L,
                                SourceUpdateStatus.DRAFT, 1, null, List.of(item),
                                LocalDateTime.now(), LocalDateTime.now());
                when(sourceUpdateService.getUpdate(10L, 200L, user)).thenReturn(dto);

                mockMvc.perform(get("/api/projects/10/source-updates/200")
                                .header("Authorization", "token"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(200L))
                                .andExpect(jsonPath("$.items[0].id").value(50L))
                                .andExpect(jsonPath("$.items[0].action").value("UPDATE"));
        }

        @Test
        void patchItemReturnsUpdatedItem() throws Exception {
                AuthUser user = user();
                when(authService.currentUser("token")).thenReturn(user);

                SourceUpdateItemDto item = new SourceUpdateItemDto(
                                50L, 200L, SourceUpdateTargetType.METHOD, 101L, "com.demo.Service#doWork()",
                                SourceUpdateAction.KEEP, "Keep old version", "{}", "{}",
                                SourceUpdateReviewStatus.ACCEPTED, LocalDateTime.now(), LocalDateTime.now());
                when(sourceUpdateService.patchItem(eq(10L), eq(200L), eq(50L), any(), any())).thenReturn(item);

                String json = """
                                {
                                    "action": "KEEP",
                                    "reviewStatus": "ACCEPTED",
                                    "reason": "Keep old version"
                                }
                                """;

                mockMvc.perform(patch("/api/projects/10/source-updates/200/items/50")
                                .header("Authorization", "token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.action").value("KEEP"))
                                .andExpect(jsonPath("$.reviewStatus").value("ACCEPTED"));
        }

        @Test
        void analyzeUpdateEndpointReturnsAnalyzedDto() throws Exception {
                AuthUser user = user();
                when(authService.currentUser("token")).thenReturn(user);

                SourceUpdateDto dto = new SourceUpdateDto(
                                200L, 10L, 1L, 2L,
                                SourceUpdateStatus.ANALYZED, 1, "{}", List.of(),
                                LocalDateTime.now(), LocalDateTime.now());
                when(sourceUpdateService.analyzeUpdate(10L, 200L, user)).thenReturn(dto);

                mockMvc.perform(post("/api/projects/10/source-updates/200/analyze")
                                .header("Authorization", "token"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("ANALYZED"));
        }

        @Test
        void generateIncrementalEndpointCallsService() throws Exception {
                AuthUser user = user();
                when(authService.currentUser("token")).thenReturn(user);

                SourceUpdateDto dto = new SourceUpdateDto(
                                200L, 10L, 1L, 2L,
                                SourceUpdateStatus.READY_TO_APPLY, 1, "{}", List.of(),
                                LocalDateTime.now(), LocalDateTime.now());
                when(incrementalGenerationService.generateIncrementalStage(eq(10L), eq(200L), any(), eq(user)))
                                .thenReturn(dto);

                mockMvc.perform(post("/api/projects/10/source-updates/200/generate?stage=BUSINESS_RULE")
                                .header("Authorization", "token"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("READY_TO_APPLY"));
        }

        @Test
        void applyUpdateReturnsAppliedDto() throws Exception {
                AuthUser user = user();
                when(authService.currentUser("token")).thenReturn(user);

                SourceUpdateDto dto = new SourceUpdateDto(
                                200L, 10L, 1L, 2L,
                                SourceUpdateStatus.APPLIED, 1, "{}", List.of(),
                                LocalDateTime.now(), LocalDateTime.now());
                when(sourceUpdateService.applyUpdate(10L, 200L, user)).thenReturn(dto);

                mockMvc.perform(post("/api/projects/10/source-updates/200/apply")
                                .header("Authorization", "token"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("APPLIED"));
        }

        @Test
        void cancelUpdateReturnsNoContent() throws Exception {
                AuthUser user = user();
                when(authService.currentUser("token")).thenReturn(user);

                mockMvc.perform(post("/api/projects/10/source-updates/200/cancel")
                                .header("Authorization", "token"))
                                .andExpect(status().isNoContent());

                verify(sourceUpdateService).cancelUpdate(10L, 200L, user);
        }

        private AuthUser user() {
                AuthUser user = new AuthUser();
                user.setId(10L);
                user.setRole(UserRole.USER);
                user.setEnabled(true);
                return user;
        }
}
