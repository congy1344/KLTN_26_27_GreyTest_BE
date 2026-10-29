package com.greytest.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void reviewedOutputConflictReturnsConflictEvenInsideBatch(boolean batched) throws Exception {
        RuntimeException failure = new com.greytest.service.generation.GenerationConflictException(14L, "{}");
        if (batched) {
            var executor = new com.greytest.service.LlmBatchExecutor(1, 1, 0);
            RuntimeException conflict = failure;
            failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> executor.map(java.util.List.of(1), value -> { throw conflict; }), RuntimeException.class);
        }
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ConflictEndpoint(failure))
                .setControllerAdvice(handler).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/conflict"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("GENERATION_CONFLICT"));
    }

    @org.springframework.web.bind.annotation.RestController
    static class ConflictEndpoint {
        private final RuntimeException failure;
        ConflictEndpoint(RuntimeException failure) { this.failure = failure; }
        @org.springframework.web.bind.annotation.GetMapping("/conflict")
        public void conflict() { throw failure; }
    }

    @Test
    void optimisticLockConflictPreservesItsHttpStatus() throws Exception {
        var failure = new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "Draft version changed");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ConflictEndpoint(failure))
                .setControllerAdvice(handler).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/conflict"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Draft version changed"));
    }

    @Test
    void batchProviderFailureReturnsBadGateway() throws Exception {
        var executor = new com.greytest.service.LlmBatchExecutor(1, 1, 0);
        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> executor.map(java.util.List.of(1), value -> { throw new com.greytest.service.agent.LlmResponseException("Provider timed out"); }), RuntimeException.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ConflictEndpoint(failure))
                .setControllerAdvice(handler).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/conflict"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("LLM_ERROR"));
    }

    @Test
    void missingAuthorizationHeaderReturnsUnauthorized() {
        var response = handler.handleMissingHeader(new MissingRequestHeaderException("Authorization", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("AUTH_ERROR");
    }

    @Test
    void missingEndpointReturnsNotFound() {
        var response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "/api/health"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void concurrentGenerationReturnsConflict() {
        var response = handler.handleGenerationInProgress(
                new GenerationInProgressException("Tác vụ đang chạy."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("GENERATION_IN_PROGRESS");
    }

    @Test
    void exceededUsageQuotaReturnsTooManyRequests() {
        var response = handler.handleUsageQuotaExceeded(new UsageQuotaExceededException("Đã hết quota"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().code()).isEqualTo("USAGE_QUOTA_EXCEEDED");
    }
}
