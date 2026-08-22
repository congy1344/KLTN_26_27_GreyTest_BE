package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LlmResponseCacheTest {

    @Test
    void returnsCachedResponseForTheSamePromptAndModel() {
        LlmResponseCache cache = new LlmResponseCache("google", "gemini-test", "", 10);

        cache.put("same prompt", "response");

        assertThat(cache.get("same prompt")).contains("response");
        assertThat(cache.get("different prompt")).isEmpty();
    }

    @Test
    void evictsLeastRecentlyUsedEntryWhenCapacityIsReached() {
        LlmResponseCache cache = new LlmResponseCache("google", "gemini-test", "", 2);
        cache.put("prompt-1", "response-1");
        cache.put("prompt-2", "response-2");
        cache.get("prompt-1");

        cache.put("prompt-3", "response-3");

        assertThat(cache.get("prompt-1")).isPresent();
        assertThat(cache.get("prompt-2")).isEmpty();
        assertThat(cache.get("prompt-3")).isPresent();
    }

    @Test
    void storesResponseOnlyAfterActiveTransactionCommits() {
        LlmResponseCache cache = new LlmResponseCache("google", "gemini-test", "", 10);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.putAfterSuccessfulTransaction("prompt", "response");

            assertThat(cache.get("prompt")).isEmpty();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());
            assertThat(cache.get("prompt")).contains("response");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void doesNotStoreUnvalidatedResponseOutsideTransaction() {
        LlmResponseCache cache = new LlmResponseCache("google", "gemini-test", "", 10);

        cache.putAfterSuccessfulTransaction("prompt", "response");

        assertThat(cache.get("prompt")).isEmpty();
    }
}
