package com.greytest.service.agent;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Giới hạn thời gian nhận toàn bộ phản hồi HTTP cho các nhà cung cấp LLM. */
final class LlmHttpTransport {
    private LlmHttpTransport() { }

    static HttpResponse<String> send(HttpClient client, HttpRequest request, Duration timeout)
            throws IOException, InterruptedException {
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            // HttpRequest.timeout có thể hết hiệu lực sau headers, trong khi body vẫn treo.
            return response.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            response.cancel(true);
            throw new HttpTimeoutException("LLM response did not complete within " + timeout);
        } catch (InterruptedException error) {
            response.cancel(true);
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException io) throw io;
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("LLM HTTP request failed", error.getCause());
        }
    }
}
