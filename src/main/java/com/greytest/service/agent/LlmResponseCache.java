package com.greytest.service.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Luu tam response LLM da parse thanh cong de tranh goi lai cung mot prompt.
 */
@Service
public class LlmResponseCache {

    private final String modelIdentity;
    private final int maxEntries;
    private final Map<String, String> responses;

    public LlmResponseCache(
            @Value("${llm.provider:mock}") String provider,
            @Value("${llm.google-model:${llm.model:}}") String googleModel,
            @Value("${llm.openai-model:${llm.model:}}") String openAiModel,
            @Value("${greytest.llm-cache.max-entries:500}") int maxEntries) {
        this.modelIdentity = modelIdentity(provider, googleModel, openAiModel);
        this.maxEntries = Math.max(0, maxEntries);
        this.responses = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > LlmResponseCache.this.maxEntries;
            }
        };
    }

    public synchronized Optional<String> get(String prompt) {
        if (maxEntries == 0) return Optional.empty();
        return Optional.ofNullable(responses.get(key(prompt)));
    }

    public synchronized void put(String prompt, String response) {
        if (maxEntries == 0 || response == null || response.isBlank()) return;
        responses.put(key(prompt), response);
    }

    public void putAfterSuccessfulTransaction(String prompt, String response) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // Chi cache khi toan bo validation va luu du lieu nghiep vu da commit thanh cong.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    put(prompt, response);
                }
            });
        }
    }

    public synchronized void evict(String prompt) {
        responses.remove(key(prompt));
    }

    private String key(String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((modelIdentity + "\n" + prompt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM khong ho tro SHA-256.", exception);
        }
    }

    private static String modelIdentity(String provider, String googleModel, String openAiModel) {
        String normalizedProvider = provider == null ? "mock" : provider.trim().toLowerCase();
        String model = "google".equals(normalizedProvider) ? googleModel : openAiModel;
        return normalizedProvider + ":" + (model == null ? "" : model.trim());
    }
}
