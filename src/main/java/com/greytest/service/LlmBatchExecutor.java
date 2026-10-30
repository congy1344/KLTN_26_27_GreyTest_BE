package com.greytest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.AuthUserRepository;
import com.greytest.service.agent.LlmResponseException;

/**
 * Chạy batch LLM theo giới hạn gói của người thực hiện, giới hạn provider và giữ thứ tự kết quả.
 */
@Service
public class LlmBatchExecutor {

    private static final long MAX_RETRY_DELAY_MILLIS = 2_000L;

    private final int concurrency;
    private final int retryAttempts;
    private final long retryDelayMillis;
    private final ExecutorService executor;
    private final AuthUserRepository users;

    private static final class BatchTask<I> {
        final int index;
        final int batchNumber;
        final I input;
        int attempt;
        final AtomicInteger remainingBudget;

        BatchTask(int index, int batchNumber, I input) {
            this.index = index;
            this.batchNumber = batchNumber;
            this.input = input;
            this.attempt = 0;
            this.remainingBudget = new AtomicInteger(3);
        }
    }

    public LlmBatchExecutor(int concurrency) {
        this(concurrency, 2, 200L);
    }

    public LlmBatchExecutor(int concurrency, AuthUserRepository users) {
        this(concurrency, 2, 500L, users);
    }

    public LlmBatchExecutor(int concurrency, int retryAttempts, long retryDelayMillis) {
        this(concurrency, retryAttempts, retryDelayMillis, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LlmBatchExecutor(
            @Value("${greytest.llm-batch.concurrency:4}") int concurrency,
            @Value("${greytest.llm-batch.retry-attempts:2}") int retryAttempts,
            @Value("${greytest.llm-batch.retry-delay-millis:200}") long retryDelayMillis,
            AuthUserRepository users) {
        if (concurrency <= 0) throw new IllegalArgumentException("LLM batch concurrency phải lớn hơn 0.");
        if (retryAttempts < 0) throw new IllegalArgumentException("LLM batch retry attempts không được âm.");
        if (retryDelayMillis < 0) throw new IllegalArgumentException("LLM batch retry delay không được âm.");
        this.concurrency = concurrency;
        this.retryAttempts = retryAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.users = users;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "greytest-llm-batch-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public <I, O> List<O> map(List<I> inputs, Function<I, O> worker) {
        return map(inputs, null, worker, (batchNumber, output) -> { });
    }

    public <I, O> List<O> map(
            List<I> inputs,
            Function<I, O> worker,
            BiConsumer<Integer, O> onCompleted) {
        return map(inputs, null, worker, onCompleted);
    }

    public <I, O> List<O> map(
            List<I> inputs,
            java.util.function.BooleanSupplier shouldStop,
            Function<I, O> worker,
            BiConsumer<Integer, O> onCompleted) {
        if (inputs.isEmpty()) return List.of();
        int batchConcurrency = concurrencyForActor();
        int workerCount = Math.min(inputs.size(), batchConcurrency);

        Locale locale = LocaleContextHolder.getLocale();
        Object completionLock = new Object();
        java.util.concurrent.BlockingQueue<BatchTask<I>> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        for (int i = 0; i < inputs.size(); i++) {
            queue.offer(new BatchTask<>(i, i + 1, inputs.get(i)));
        }

        List<O> outputs = java.util.Collections.synchronizedList(new ArrayList<>(java.util.Collections.nCopies(inputs.size(), null)));
        AtomicInteger remainingTasks = new AtomicInteger(inputs.size());
        java.util.concurrent.atomic.AtomicReference<RuntimeException> fatalError = new java.util.concurrent.atomic.AtomicReference<>();

        List<CompletableFuture<Void>> workers = new ArrayList<>(workerCount);
        for (int w = 0; w < workerCount; w++) {
            CompletableFuture<Void> workerFuture = CompletableFuture.supplyAsync(GenerationJobContext.wrap(() -> {
                LocaleContextHolder.setLocale(locale);
                try {
                    while (remainingTasks.get() > 0) {
                        if (shouldStop != null && shouldStop.getAsBoolean()) {
                            break;
                        }
                        BatchTask<I> task;
                        try {
                            task = queue.poll(30, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (task == null) {
                            if (shouldStop != null && shouldStop.getAsBoolean()) break;
                            continue;
                        }

                        O output;
                        try {
                            output = GenerationJobContext.withLlmCallBudget(task.remainingBudget, () -> worker.apply(task.input));
                        } catch (BatchExecutionException exception) {
                            recordFailure(fatalError, exception);
                            remainingTasks.decrementAndGet();
                            continue;
                        } catch (LlmResponseException exception) {
                            if (!exception.isRetryable() || task.attempt >= retryAttempts) {
                                recordFailure(fatalError, new BatchExecutionException(task.batchNumber, exception));
                                remainingTasks.decrementAndGet();
                            } else {
                                task.attempt++;
                                GenerationJobContext.log("Batch " + task.batchNumber
                                        + " gặp lỗi tạm thời từ AI; tự động đưa lại vào hàng đợi thử lại lần "
                                        + (task.attempt + 1) + "/" + (retryAttempts + 1)
                                        + ". Nguyên nhân: " + exception.getMessage());
                                waitBeforeRetry(task.batchNumber, exception);
                                queue.offer(task);
                            }
                            continue;
                        } catch (RuntimeException exception) {
                            recordFailure(fatalError, new BatchExecutionException(task.batchNumber, exception));
                            remainingTasks.decrementAndGet();
                            continue;
                        }

                        try {
                            synchronized (completionLock) {
                                onCompleted.accept(task.batchNumber, output);
                            }
                            outputs.set(task.index, output);
                            remainingTasks.decrementAndGet();
                        } catch (RuntimeException callbackException) {
                            recordFailure(fatalError, new BatchExecutionException(task.batchNumber, callbackException));
                            remainingTasks.decrementAndGet();
                        }
                    }
                    return null;
                } finally {
                    LocaleContextHolder.resetLocaleContext();
                }
            }), executor);
            workers.add(workerFuture);
        }

        try {
            CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }

        if (fatalError.get() != null) {
            throw fatalError.get();
        }

        return outputs.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** Giữ lỗi riêng của mọi batch theo thứ tự batch nhỏ nhất để giao diện thông báo xác định. */
    private static void recordFailure(java.util.concurrent.atomic.AtomicReference<RuntimeException> failures,
            RuntimeException failure) {
        failures.accumulateAndGet(failure, (current, next) -> {
            if (current == null) return next;
            if (current == next) return current;
            if (current instanceof BatchExecutionException cb && next instanceof BatchExecutionException nb) {
                if (nb.batchNumber() < cb.batchNumber()) {
                    nb.addSuppressed(cb);
                    return nb;
                }
            }
            current.addSuppressed(next);
            return current;
        });
    }

    private int concurrencyForActor() {
        if (users == null) return concurrency;
        Long actorUserId = GenerationJobContext.actorUserId();
        UserTier tier = actorUserId == null ? UserTier.FREE
                : users.findById(actorUserId).map(AuthUser::getTier).orElse(UserTier.FREE);
        int tierConcurrency = switch (tier) {
            case FREE -> 2;
            case PLUS -> 3;
            case PRO -> 4;
        };
        return Math.min(concurrency, tierConcurrency);
    }

    /** Giữ tương thích cho unit test/service được khởi tạo thủ công ngoài Spring. */
    public static <I, O> List<O> mapOrSequential(
            LlmBatchExecutor executor,
            List<I> inputs,
            Function<I, O> worker) {
        return mapOrSequential(executor, inputs, null, worker, (batchNumber, output) -> { });
    }

    public static <I, O> List<O> mapOrSequential(
            LlmBatchExecutor executor,
            List<I> inputs,
            Function<I, O> worker,
            BiConsumer<Integer, O> onCompleted) {
        return mapOrSequential(executor, inputs, null, worker, onCompleted);
    }

    public static <I, O> List<O> mapOrSequential(
            LlmBatchExecutor executor,
            List<I> inputs,
            java.util.function.BooleanSupplier shouldStop,
            Function<I, O> worker,
            BiConsumer<Integer, O> onCompleted) {
        if (executor != null) return executor.map(inputs, shouldStop, worker, onCompleted);
        List<O> outputs = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            if (shouldStop != null && shouldStop.getAsBoolean()) {
                break;
            }
            O output = executeBatchWithoutRetry(index + 1, inputs.get(index), worker, onCompleted);
            outputs.add(output);
        }
        return outputs;
    }

    private static <I, O> O executeBatchWithoutRetry(
            int batchNumber,
            I input,
            Function<I, O> worker,
            BiConsumer<Integer, O> onCompleted) {
        return GenerationJobContext.withLlmCallBudget(3,
                () -> executeBatchCallback(batchNumber, input, worker, onCompleted));
    }

    private static <I, O> O executeBatchCallback(
            int batchNumber, I input, Function<I, O> worker, BiConsumer<Integer, O> onCompleted) {
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

    private <I, O> O executeBatch(
            int batchNumber,
            I input,
            Function<I, O> worker,
            BiConsumer<Integer, O> onCompleted) {
        return GenerationJobContext.withLlmCallBudget(3,
                () -> executeBatchWithRetry(batchNumber, input, worker, onCompleted));
    }

    private <I, O> O executeBatchWithRetry(
            int batchNumber, I input, Function<I, O> worker, BiConsumer<Integer, O> onCompleted) {
        int attempt = 0;
        while (true) {
            try {
                O output = worker.apply(input);
                try {
                    onCompleted.accept(batchNumber, output);
                } catch (RuntimeException exception) {
                    // Persistence/progress callbacks are not safe to repeat by invoking the LLM again.
                    throw new BatchExecutionException(batchNumber, exception);
                }
                return output;
            } catch (BatchExecutionException exception) {
                throw exception;
            } catch (LlmResponseException exception) {
                if (!exception.isRetryable() || exception.getRetryAfterMillis() > MAX_RETRY_DELAY_MILLIS
                        || attempt >= retryAttempts) {
                    throw new BatchExecutionException(batchNumber, exception);
                }
                attempt++;
                GenerationJobContext.log("Batch " + batchNumber + " gặp lỗi tạm thời từ AI; tự động thử lại lần "
                        + (attempt + 1) + "/" + (retryAttempts + 1) + ".");
                waitBeforeRetry(batchNumber, exception);
            } catch (RuntimeException exception) {
                throw new BatchExecutionException(batchNumber, exception);
            }
        }
    }

    private void waitBeforeRetry(int batchNumber, LlmResponseException exception) {
        long delay = Math.min(MAX_RETRY_DELAY_MILLIS,
                Math.max(retryDelayMillis, exception.getRetryAfterMillis()));
        if (delay <= 0) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BatchExecutionException(
                    batchNumber, new LlmResponseException("Bị gián đoạn trong khi chờ thử lại batch AI.", interrupted));
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
