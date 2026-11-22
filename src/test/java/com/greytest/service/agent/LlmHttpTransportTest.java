package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.greytest.service.GenerationJobContext;

/** Kiểm tra deadline hủy I/O và giữ nguyên nguyên nhân lỗi cho chính sách retry. */
class LlmHttpTransportTest {
    private final HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/unused")).build();

    @Test void cancelsAnIncompleteResponseOnDeadline() {
        HttpClient client = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<String>>();
        when(client.sendAsync(eq(request), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        try (var scope = GenerationJobContext.openScope(1L)) {
            assertThatThrownBy(() -> LlmHttpTransport.send(client, request, Duration.ofMillis(30)))
                    .isInstanceOf(HttpTimeoutException.class);
            // Request hết hạn là lỗi API, không tính vào thời gian chờ AI.
            assertThat(GenerationJobContext.metrics()).doesNotContainKey("llmHttpWaitNanos").containsEntry("llmFailedRequests", 1L);
        }
        assertThat(pending.isCancelled()).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test void countsOnlySuccessfulResponsesAsAiWait() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        HttpResponse<String> unavailable = mock(HttpResponse.class);
        when(unavailable.statusCode()).thenReturn(504);
        when(client.sendAsync(eq(request), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(unavailable), CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    return ok;
                }));
        try (var scope = GenerationJobContext.openScope(1L)) {
            LlmHttpTransport.send(client, request, Duration.ofSeconds(1));
            assertThat(GenerationJobContext.metrics()).doesNotContainKey("llmHttpWaitNanos").containsEntry("llmFailedRequests", 1L);
            LlmHttpTransport.send(client, request, Duration.ofSeconds(1));
            assertThat((Long) GenerationJobContext.metrics().get("llmHttpWaitNanos")).isPositive();
        }
    }

    @Test void preservesIoFailureInsteadOfHidingItInExecutionException() {
        HttpClient client = mock(HttpClient.class);
        var failure = new IOException("local fixture failure");
        var pending = CompletableFuture.<HttpResponse<String>>failedFuture(failure);
        when(client.sendAsync(eq(request), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        assertThatThrownBy(() -> LlmHttpTransport.send(client, request, Duration.ofSeconds(1))).isSameAs(failure);
    }

    @Test void cancelsTheRequestWhenTheWaitingThreadIsInterrupted() {
        HttpClient client = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<String>>();
        when(client.sendAsync(eq(request), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> LlmHttpTransport.send(client, request, Duration.ofSeconds(1)))
                    .isInstanceOf(InterruptedException.class);
            assertThat(pending.isCancelled()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
