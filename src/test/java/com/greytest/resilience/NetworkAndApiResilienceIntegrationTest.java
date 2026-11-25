package com.greytest.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.ErrorResponse;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.exception.GlobalExceptionHandler;
import com.greytest.exception.StorageException;
import com.greytest.service.agent.LlmResponseException;

/**
 * Phase 6.3 Workstream F: Network & API Resilience Tests.
 * F1 & F2: Network timeout / connection reset khi gọi LLM -> Chuẩn hóa mã lỗi LLM_ERROR, HTTP 502 Bad Gateway.
 * F4 & F5: Client retry liên tục khi đang xử lý -> Chuẩn hóa mã lỗi GENERATION_IN_PROGRESS, HTTP 409 Conflict.
 * F6: API Error Sanitization:
 * - DTO phản hồi chuẩn ErrorResponse(code, message).
 * - Không để lộ stack trace nội bộ ra payload client.
 * - Không để lộ đường dẫn tệp máy chủ (C:\Users\..., /var/...) trong thông báo lỗi lưu trữ.
 * - Ngoại lệ chung (Exception) được che chắn an toàn với INTERNAL_ERROR.
 */
public class NetworkAndApiResilienceIntegrationTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @RestController
    static class FaultyTestController {
        @GetMapping("/test/llm-timeout")
        public void llmTimeout() {
            throw new LlmResponseException("Read timeout to AI Provider at https://api.openai.com/v1 after 30000ms");
        }

        @GetMapping("/test/storage-leak-risk")
        public void storageLeak() {
            throw new StorageException("Corrupted disk block at C:\\Users\\Administrator\\AppData\\Local\\Temp\\secret.key");
        }

        @GetMapping("/test/unexpected-crash")
        public void unexpectedCrash() {
            throw new NullPointerException("Fatal memory pointer dereference in internal pipeline");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new FaultyTestController())
            .setControllerAdvice(exceptionHandler)
            .build();

    @Test
    @DisplayName("F1 & F2: Network timeout / connection failure khi gọi LLM trả về HTTP 502 Bad Gateway và LLM_ERROR")
    void testLlmTimeoutReturnsBadGatewaySafely() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/test/llm-timeout"))
                .andExpect(MockMvcResultMatchers.status().isBadGateway())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("LLM_ERROR"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("Read timeout to AI Provider at https://api.openai.com/v1 after 30000ms"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    @DisplayName("F4 & F5: Xung đột tiến trình khi client retry trả về HTTP 409 Conflict với GENERATION_IN_PROGRESS")
    void testClientRetryConflictReturnsSafeCode() {
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleGenerationInProgress(
                new GenerationInProgressException("Project 100 đang có tác vụ đang chạy. Vui lòng chờ."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("GENERATION_IN_PROGRESS");
        assertThat(response.getBody().message()).contains("đang có tác vụ đang chạy");
    }

    @Test
    @DisplayName("F6: Lỗi Storage không để lộ đường dẫn tệp hệ điều hành hoặc stack trace ra ngoài")
    void testStorageErrorSanitization() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/test/storage-leak-risk"))
                .andExpect(MockMvcResultMatchers.status().isInternalServerError())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("STORAGE_ERROR"))
                // Thông báo trả về người dùng đã được che chắn an toàn thành thông điệp chung
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("Có lỗi khi xử lý file source"))
                .andExpect(MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("C:\\Users\\"))))
                .andExpect(MockMvcResultMatchers.jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    @DisplayName("F6: Ngoại lệ không kiểm soát (Exception) được che chắn thành INTERNAL_ERROR an toàn")
    void testUnexpectedCrashSanitization() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/test/unexpected-crash"))
                .andExpect(MockMvcResultMatchers.status().isInternalServerError())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("Có lỗi xảy ra"))
                .andExpect(MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("NullPointerException"))))
                .andExpect(MockMvcResultMatchers.jsonPath("$.stackTrace").doesNotExist());
    }
}
