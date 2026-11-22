package com.greytest.service.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * LLM client that calls an OpenAI-compatible Chat Completions API.
 */
@Service
@ConditionalOnProperty(prefix = "llm", name = "provider", havingValue = "openai")
public class OpenAiLlmClient implements LlmClient {

    private static final int ERROR_BODY_LIMIT = 500;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final List<String> apiKeys = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger keyIndex = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean runtimeKeysConfigured = false;
    private volatile String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final URI endpoint;
    private final boolean jsonMode;
    private final LlmUsageRecorder usageRecorder;

    @Autowired
    public OpenAiLlmClient(
            ObjectMapper objectMapper,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.openai-model:${llm.model:gpt-4o-mini}}") String model,
            @Value("${llm.temperature:0.3}") double temperature,
            @Value("${llm.max-tokens:4096}") int maxTokens,
            @Value("${llm.timeout-seconds:180}") long timeoutSeconds,
            @Value("${llm.openai-url:https://api.openai.com/v1/chat/completions}") String endpoint,
            @Value("${llm.openai-json-mode:true}") boolean jsonMode,
            ObjectProvider<LlmUsageRecorder> usageRecorderProvider) {
        this(
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build(),
                apiKey,
                model,
                temperature,
                maxTokens,
                Duration.ofSeconds(timeoutSeconds),
                URI.create(endpoint),
                jsonMode,
                usageRecorderProvider.getIfAvailable(LlmUsageRecorder::noop));
    }

    OpenAiLlmClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String apiKey,
            String model,
            double temperature,
            int maxTokens,
            Duration timeout,
            URI endpoint) {
        this(objectMapper, httpClient, apiKey, model, temperature, maxTokens, timeout, endpoint,
                true, LlmUsageRecorder.noop());
    }

    OpenAiLlmClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String apiKey,
            String model,
            double temperature,
            int maxTokens,
            Duration timeout,
            URI endpoint,
            LlmUsageRecorder usageRecorder) {
        this(objectMapper, httpClient, apiKey, model, temperature, maxTokens, timeout, endpoint,
                true, usageRecorder);
    }

    OpenAiLlmClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String apiKey,
            String model,
            double temperature,
            int maxTokens,
            Duration timeout,
            URI endpoint,
            boolean jsonMode,
            LlmUsageRecorder usageRecorder) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.endpoint = endpoint;
        this.jsonMode = jsonMode;
        this.usageRecorder = usageRecorder;
        if (apiKey != null && !apiKey.isBlank()) {
            this.apiKeys.add(apiKey);
        }
    }

    public synchronized void setApiKeys(java.util.List<String> keys) {
        this.runtimeKeysConfigured = true;
        this.apiKeys.clear();
        if (keys != null) {
            keys.stream().filter(k -> k != null && !k.isBlank()).forEach(this.apiKeys::add);
        }
    }

    public java.util.List<String> getApiKeys() {
        return java.util.List.copyOf(apiKeys);
    }

    public void setModel(String model) {
        if (model != null && !model.isBlank()) {
            this.model = model.trim();
        }
    }

    public String getModel() {
        return this.model;
    }

    private String resolveApiKey() {
        if (!apiKeys.isEmpty()) {
            int idx = Math.floorMod(keyIndex.getAndIncrement(), apiKeys.size());
            return apiKeys.get(idx);
        }
        return runtimeKeysConfigured ? null : this.apiKey;
    }

    @Override
    public String complete(String prompt) {
        return complete(prompt, new LlmRequestOptions(maxTokens));
    }

    @Override
    public String complete(String prompt, LlmRequestOptions options) {
        String activeKey = resolveApiKey();
        if (activeKey == null || activeKey.isBlank()) {
            throw new LlmResponseException("LLM_API_KEY chưa được cấu hình.");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + activeKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt, options)))
                .build();

        try {
            long startedAt = System.nanoTime();
            HttpResponse<String> response = LlmHttpTransport.send(httpClient, request, timeout);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                int status = response.statusCode();
                String message = "OpenAI API lỗi HTTP " + status + ": " + snippet(response.body());
                if (status == 429 && isInsufficientQuota(response.body())) {
                    // Hết credit là lỗi vĩnh viễn: retry chỉ tốn thời gian, vẫn tính là lỗi provider để hoàn lượt gọi.
                    throw new LlmResponseException("Tài khoản LLM đã hết credit (insufficient_quota). " + message,
                            null, false, 0, LlmResponseException.ErrorClassification.PROVIDER_UNAVAILABLE);
                }
                if (status == 429 || status >= 500) {
                    throw LlmResponseException.provider(message, null, LlmHttpTransport.retryAfterMillis(response));
                }
                throw new LlmResponseException(message, false);
            }
            JsonNode root = responseRoot(response.body());
            String output = "";
            try {
                JsonNode choice = root.path("choices").path(0);
                output = choice.path("message").path("content").asText("");
                if ("length".equals(choice.path("finish_reason").asText())) {
                    throw new LlmResponseException.OutputTruncated("OpenAI đã cắt output vì đạt giới hạn max tokens.");
                }
                output = outputText(response.body());
                return output;
            } finally {
                usageRecorder.record(
                        prompt,
                        output,
                        "openai",
                        root.path("model").asText(model),
                        LlmTokenUsage.fromOpenAi(root, prompt, output),
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            }
        } catch (IOException exception) {
            throw LlmResponseException.provider("Không gọi được OpenAI API: " + exception.getMessage(), exception, 0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmResponseException("Bi gian doan khi goi OpenAI API.", exception);
        }
    }

    private String requestBody(String prompt, LlmRequestOptions options) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        var messages = root.putArray("messages");
        String prefix = String.join("", options.cacheablePrefixes());
        if ("gpt-6-luna".equals(model) && !prefix.isEmpty() && prompt.startsWith(prefix)) {
            // Chỉ cache phần dùng chung; không ghi lại suffix thay đổi ở mỗi batch/retry.
            root.putObject("prompt_cache_options").put("mode", "explicit");
            for (String part : options.cacheablePrefixes()) {
                var text = messages.addObject().put("role", "user").putArray("content").addObject();
                text.put("type", "text").put("text", part);
                text.putObject("prompt_cache_breakpoint").put("mode", "explicit");
            }
            messages.addObject().put("role", "user").put("content", prompt.substring(prefix.length()));
        } else if (model != null && model.startsWith("claude") && !prefix.isEmpty() && prompt.startsWith(prefix)) {
            // Claude qua proxy tương thích OpenAI: prefix chỉ gồm phần ổn định giữa các batch (hướng dẫn, policy theo chủ đề;
            // source cắt theo batch nằm sau prefix) nên đánh dấu cache_control cho từng prefix (≤ 4 breakpoint của Anthropic).
            var parts = messages.addObject().put("role", "user").putArray("content");
            for (String part : options.cacheablePrefixes()) {
                parts.addObject().put("type", "text").put("text", part)
                        .putObject("cache_control").put("type", "ephemeral");
            }
            parts.addObject().put("type", "text").put("text", prompt.substring(prefix.length()));
        } else {
            messages.addObject().put("role", "user").put("content", prompt);
        }
        root.put("temperature", temperature);
        if ("gpt-6-luna".equals(model)) {
            // Luna cần tham số token mới; tắt reasoning để giữ temperature hiện tại.
            root.put("max_completion_tokens", options.maxTokens());
            root.put("reasoning_effort", "none");
        } else {
            root.put("max_tokens", options.maxTokens());
        }
        if (jsonMode && options.responseSchema() != null && model != null && model.startsWith("gpt")) {
            // Structured Outputs strict của OpenAI: output luôn khớp schema stage, bỏ được lượt sửa do JSON sai schema.
            var format = root.putObject("response_format").put("type", "json_schema").putObject("json_schema");
            format.put("name", options.schemaName()).put("strict", true).set("schema", options.responseSchema());
        } else if (jsonMode) {
            root.putObject("response_format").put("type", "json_object");
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new LlmResponseException("Không tạo được request OpenAI.", exception);
        }
    }

    private boolean isInsufficientQuota(String responseBody) {
        try {
            JsonNode error = objectMapper.readTree(responseBody == null ? "" : responseBody).path("error");
            return "insufficient_quota".equals(error.path("type").asText())
                    || "insufficient_quota".equals(error.path("code").asText());
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private JsonNode responseRoot(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw LlmResponseException.provider("OpenAI response không phải JSON hợp lệ.", exception, 0);
        }
    }

    private String outputText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String outputText = root.path("choices").path(0).path("message").path("content").asText("");
            if (outputText.isBlank()) outputText = root.path("output_text").asText("");
            if (!outputText.isBlank()) return outputText;
            throw LlmResponseException.provider("OpenAI response không có text output.", null, 0);
        } catch (JsonProcessingException exception) {
            throw LlmResponseException.provider("OpenAI response không phải JSON hợp lệ.", exception, 0);
        }
    }

    private String snippet(String body) {
        if (body == null) return "";
        String sanitized = body.replaceAll("(?i)(api[-_ ]?key|authorization|token|password)\\s*[:=]\\s*[\\\"']?[^,\\\"'\\s}]+", "$1=[redacted]")
                .replaceAll("[\\r\\n\\t]+", " ");
        if (sanitized.length() <= ERROR_BODY_LIMIT) return sanitized;
        return sanitized.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
