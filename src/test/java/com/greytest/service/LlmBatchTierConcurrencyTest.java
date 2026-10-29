package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.AuthUserRepository;

class LlmBatchTierConcurrencyTest {

    private final AuthUserRepository users = mock(AuthUserRepository.class);
    private AnnotationConfigApplicationContext context;

    @AfterEach
    void cleanUp() {
        GenerationJobContext.clear();
        if (context != null) context.close();
    }

    @ParameterizedTest
    @CsvSource({"FREE,2", "PLUS,3", "PRO,4"})
    void runsOnlyTheAllowedNumberOfBatchesForTheActorTier(UserTier tier, int expected) throws Exception {
        AuthUser actor = new AuthUser();
        actor.setTier(tier);
        when(users.findById(42L)).thenReturn(Optional.of(actor));
        GenerationJobContext.bind(42L, ignored -> { });

        assertConcurrentBatches(executor(4), expected);
    }

    @Test
    void respectsTheProviderConcurrencyCapEvenForPro() throws Exception {
        AuthUser actor = new AuthUser();
        actor.setTier(UserTier.PRO);
        when(users.findById(42L)).thenReturn(Optional.of(actor));
        GenerationJobContext.bind(42L, ignored -> { });

        assertConcurrentBatches(executor(2), 2);
    }

    @Test
    void missingActorUsesFreeConcurrency() throws Exception {
        assertConcurrentBatches(executor(4), 2);
    }

    @Test
    void deletedActorUsesFreeConcurrency() throws Exception {
        when(users.findById(42L)).thenReturn(Optional.empty());
        GenerationJobContext.bind(42L, ignored -> { });

        assertConcurrentBatches(executor(4), 2);
    }

    private LlmBatchExecutor executor(int cap) {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "greytest.llm-batch.concurrency", cap,
                "greytest.llm-batch.retry-attempts", 0)));
        context.registerBean(AuthUserRepository.class, () -> users);
        context.register(LlmBatchExecutor.class);
        context.refresh();
        return context.getBean(LlmBatchExecutor.class);
    }

    private void assertConcurrentBatches(LlmBatchExecutor executor, int expected) throws Exception {
        CountDownLatch firstWave = new CountDownLatch(expected);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch excess = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Integer> inputs = IntStream.rangeClosed(1, 12).boxed().toList();
        CompletableFuture<List<Integer>> job = CompletableFuture.supplyAsync(GenerationJobContext.wrap(() ->
                executor.map(inputs, input -> {
                    int current = active.incrementAndGet();
                    maximum.accumulateAndGet(current, Math::max);
                    if (current > expected) excess.countDown();
                    firstWave.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Batch timed out");
                        return input;
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    } finally {
                        active.decrementAndGet();
                    }
                })));
        try {
            assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(excess.await(100, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            release.countDown();
            assertThat(job.get(5, TimeUnit.SECONDS)).containsExactlyElementsOf(inputs);
        }
        assertThat(maximum.get()).isEqualTo(expected);
    }
}
