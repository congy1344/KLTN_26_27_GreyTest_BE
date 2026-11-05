package com.greytest.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.greytest.service.LlmBatchExecutor;

class GlobalExceptionHandlerBatchTest {

    @Test
    void quotaExceededInsideBatchReturns429WithOriginalMessage() {
        LlmBatchExecutor executor = new LlmBatchExecutor(1, 1, 0);
        var failure = catchThrowableOfType(() -> executor.map(List.of(1), input -> {
            throw new UsageQuotaExceededException("Bạn đã sử dụng hết quota LLM.");
        }), LlmBatchExecutor.BatchExecutionException.class);

        var response = new GlobalExceptionHandler().handleBatchFailure(failure);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().code()).isEqualTo("USAGE_QUOTA_EXCEEDED");
        assertThat(response.getBody().message()).contains("hết quota LLM");
    }
}
