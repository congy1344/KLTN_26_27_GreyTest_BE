package com.greytest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Chạy các batch gọi LLM song song với mức đồng thời cố định và giữ nguyên thứ tự kết quả.
 */
@Service
public class LlmBatchExecutor {

    private final int concurrency;
    private final ExecutorService executor;

    public LlmBatchExecutor(@Value("${greytest.llm-batch.concurrency:2}") int concurrency) {
        if (concurrency <= 0) throw new IllegalArgumentException("LLM batch concurrency phai lon hon 0.");
        this.concurrency = concurrency;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "greytest-llm-batch-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public <I, O> List<O> map(List<I> inputs, Function<I, O> worker) {
        return map(inputs, worker, (batchNumber, output) -> { });
    }

    public <I, O> List<O> map(
            List<I> inputs,
            Function<I, O> worker,
            BiConsumer<Integer, O> onCompleted) {
        if (inputs.isEmpty()) return List.of();
        if (concurrency == 1 || inputs.size() == 1) {
            List<O> outputs = new ArrayList<>(inputs.size());
            for (int index = 0; index < inputs.size(); index++) {
                O output = executeBatch(index + 1, inputs.get(index), worker, onCompleted);
                outputs.add(output);
            }
            return outputs;
        }

        Locale locale = LocaleContextHolder.getLocale();
        List<CompletableFuture<O>> futures = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            I input = inputs.get(index);
            int batchNumber = index + 1;
            Supplier<O> contextualTask = GenerationJobContext.wrap(() -> {
                LocaleContextHolder.setLocale(locale);
                try {
                    return executeBatch(batchNumber, input, worker, onCompleted);
                } finally {
                    LocaleContextHolder.resetLocaleContext();
                }
            });
            futures.add(CompletableFuture.supplyAsync(contextualTask, executor));
        }

        List<O> outputs = new ArrayList<>(inputs.size());
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            for (CompletableFuture<O> future : futures) outputs.add(future.join());
            return outputs;
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }

    /** Giữ tương thích cho unit test/service được khởi tạo thủ công ngoài Spring. */
    public static <I, O> List<O> mapOrSequential(
            LlmBatchExecutor executor,
            List<I> inputs,
            Function<I, O> worker) {
        return mapOrSequential(executor, inputs, worker, (batchNumber, output) -> { });
    }

    public static <I, O> List<O> mapOrSequential(
            LlmBatchExecutor executor,
            List<I> inputs,
            Function<I, O> worker,
            BiConsumer<Integer, O> onCompleted) {
        if (executor != null) return executor.map(inputs, worker, onCompleted);
        List<O> outputs = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            O output = executeBatch(index + 1, inputs.get(index), worker, onCompleted);
            outputs.add(output);
        }
        return outputs;
    }

    private static <I, O> O executeBatch(
            int batchNumber,
            I input,
            Function<I, O> worker,
            BiConsumer<Integer, O> onCompleted) {
        try {
            O output = worker.apply(input);
            onCompleted.accept(batchNumber, output);
            return output;
        } catch (BatchExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BatchExecutionException(batchNumber, exception);
        }
    }

    public static RuntimeException originalFailure(RuntimeException exception) {
        return exception instanceof BatchExecutionException batchException
                ? batchException.originalFailure() : exception;
    }

    public static int failedBatch(RuntimeException exception, int fallback) {
        return exception instanceof BatchExecutionException batchException
                ? batchException.batchNumber() : fallback;
    }

    /** Giữ số thứ tự batch nhưng không làm mất exception nghiệp vụ gốc. */
    public static final class BatchExecutionException extends RuntimeException {
        private final int batchNumber;
        private final RuntimeException originalFailure;

        private BatchExecutionException(int batchNumber, RuntimeException originalFailure) {
            super(originalFailure.getMessage(), originalFailure);
            this.batchNumber = batchNumber;
            this.originalFailure = originalFailure;
        }

        public int batchNumber() {
            return batchNumber;
        }

        public RuntimeException originalFailure() {
            return originalFailure;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
