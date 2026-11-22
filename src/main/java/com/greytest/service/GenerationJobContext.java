package com.greytest.service;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import com.greytest.service.agent.LlmResponseException;
import com.greytest.service.agent.LlmTokenUsage;

/** Truyền callback log của job theo worker thread mà không làm LLM client phụ thuộc module nghiệp vụ. */
public final class GenerationJobContext {

    private static final ThreadLocal<Consumer<String>> LOG_CONSUMER = new ThreadLocal<>();
    private static final ThreadLocal<Long> ACTOR_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Map<Object, Object>> JOB_INPUTS = new ThreadLocal<>();
    private static final ThreadLocal<Integer> LLM_CALLS_REMAINING = new ThreadLocal<>();
    private static final ThreadLocal<Long> SOURCE_UPDATE_ID = new ThreadLocal<>();
    private static final ThreadLocal<JobMetrics> METRICS = new ThreadLocal<>();
    private static final Object REPAIR_QUOTA_EXHAUSTED = new Object();

    public static void markRepairQuotaExhausted() {
        Map<Object, Object> inputs = JOB_INPUTS.get();
        if (inputs != null) inputs.put(REPAIR_QUOTA_EXHAUSTED, true);
    }

    public static boolean repairQuotaExhausted() {
        return Boolean.TRUE.equals(scopedValue(REPAIR_QUOTA_EXHAUSTED));
    }

    private GenerationJobContext() {}

    public static void bind(Long actorUserId, Consumer<String> logConsumer) {
        bind(actorUserId, logConsumer, System::nanoTime);
    }

    static void bind(Long actorUserId, Consumer<String> logConsumer, LongSupplier nanoTime) {
        bind(actorUserId, logConsumer, new ConcurrentHashMap<>());
        METRICS.set(new JobMetrics(nanoTime));
        SOURCE_UPDATE_ID.remove();
    }

    private static void bind(Long actorUserId, Consumer<String> logConsumer, Map<Object, Object> inputs) {
        ACTOR_USER_ID.set(actorUserId);
        LOG_CONSUMER.set(logConsumer);
        JOB_INPUTS.set(inputs);
    }

    /** Chỉ giữ dữ liệu source/build ổn định trong job; artifact đang sinh phải đọc mới từ CSDL. */
    @SuppressWarnings("unchecked")
    public static <T> T memoize(Object key, Supplier<T> loader) {
        Map<Object, Object> inputs = JOB_INPUTS.get();
        return inputs == null ? loader.get() : (T) inputs.computeIfAbsent(key, ignored -> loader.get());
    }

    public static Long actorUserId() {
        return ACTOR_USER_ID.get();
    }

    @SuppressWarnings("unchecked")
    public static <T> T scopedValue(Object key) {
        Map<Object, Object> inputs = JOB_INPUTS.get();
        return inputs == null ? null : (T) inputs.get(key);
    }

    /** Binding tạm của run không được để lease cũ tồn tại trong job ngoài. */
    public static <T> T withValue(Object key, Object value, Supplier<T> task) {
        if (JOB_INPUTS.get() == null) {
            try (var scope = openScope(actorUserId())) { return withValue(key, value, task); }
        }
        Map<Object, Object> inputs = JOB_INPUTS.get();
        Object previous = inputs.put(key, java.util.Objects.requireNonNull(value));
        try { return task.get(); }
        finally {
            if (previous == null) inputs.remove(key);
            else inputs.put(key, previous);
        }
    }

    /** Scope lồng nhau giữ actor, callback và nguồn đã chia sẻ của request ngoài. */
    public static Scope openScope(Long actorUserId) {
        Scope previous = new Scope();
        if (JOB_INPUTS.get() == null) bind(actorUserId, LOG_CONSUMER.get());
        return previous;
    }

    public static void setSourceUpdateId(Long updateId) { SOURCE_UPDATE_ID.set(updateId); }
    public static Long sourceUpdateId() { return SOURCE_UPDATE_ID.get(); }

    public static void increment(String name) { addMetric(name, 1L); }
    public static void count(String name, long value) { addMetric(name, value); }
    public static void recordDuration(String name, long nanos) { addMetric(name + "Nanos", nanos); }

    /** Đo cả nhánh lỗi để baseline không che mất chi phí retry/validation. */
    public static <T> T measure(String name, Supplier<T> task) {
        long started = System.nanoTime();
        try { return task.get(); }
        finally { recordDuration(name, System.nanoTime() - started); }
    }

    private static void addMetric(String name, long value) {
        JobMetrics metrics = METRICS.get();
        if (metrics != null) metrics.counters.computeIfAbsent(name, ignored -> new LongAdder()).add(value);
    }

    /** Một lượt chờ phản hồi AI: run() khi provider trả kết quả, fail() khi request lỗi (timeout, HTTP lỗi). */
    public interface LlmWait extends Runnable {
        void fail();
    }

    /** Bắt đầu chờ phản hồi AI. Caller phải gọi run() hoặc fail() trong finally. */
    public static LlmWait startLlmWait() {
        JobMetrics metrics = METRICS.get();
        if (metrics == null) return new LlmWait() {
            public void run() { }
            public void fail() { }
        };
        return metrics.startLlmWait();
    }

    public static Map<String, Object> metrics() {
        return snapshotMetrics(METRICS.get());
    }

    /** Giữ đúng bộ đếm của job khi frontend đọc tiến độ từ thread khác. */
    public static Supplier<Map<String, Object>> metricsSupplier() {
        JobMetrics metrics = METRICS.get();
        return () -> snapshotMetrics(metrics);
    }

    public static void recordLlmUsage(LlmTokenUsage usage) {
        increment("llmRecordedCalls");
        addMetric("llmInputTokens", usage.inputTokens());
        addMetric("llmOutputTokens", usage.outputTokens());
        addMetric("llmTotalTokens", usage.totalTokens());
        if (usage.source() == LlmTokenUsage.UsageSource.ESTIMATED) increment("llmEstimatedCalls");
    }

    private static Map<String, Object> snapshotMetrics(JobMetrics metrics) {
        return metrics == null ? Map.of() : metrics.snapshot();
    }

    private static final class JobMetrics {
        private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        private final LongSupplier nanoTime;
        // Khoảng [bắt đầu, kết thúc] của request thành công; request lỗi không phải thời gian AI sinh output.
        private final java.util.List<long[]> succeeded = new java.util.ArrayList<>();
        private final java.util.Set<long[]> pending = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        private JobMetrics(LongSupplier nanoTime) {
            this.nanoTime = nanoTime;
        }

        private synchronized LlmWait startLlmWait() {
            long[] open = {nanoTime.getAsLong()};
            pending.add(open);
            return new LlmWait() {
                public void run() { finish(open, true); }
                public void fail() { finish(open, false); }
            };
        }

        private synchronized void finish(long[] open, boolean success) {
            if (!pending.remove(open)) return;
            if (success) succeeded.add(new long[] {open[0], nanoTime.getAsLong()});
            else counters.computeIfAbsent("llmFailedRequests", ignored -> new LongAdder()).increment();
        }

        /** Hợp các khoảng (request song song chỉ tính một lần, bỏ khoảng trống không chờ AI). */
        private long unionNanos(long now) {
            var intervals = new java.util.ArrayList<>(succeeded);
            pending.forEach(open -> intervals.add(new long[] {open[0], now}));
            intervals.sort(java.util.Comparator.comparingLong(interval -> interval[0]));
            long total = 0, start = 0, end = Long.MIN_VALUE;
            for (long[] interval : intervals) {
                if (interval[0] > end) {
                    if (end != Long.MIN_VALUE) total += end - start;
                    start = interval[0];
                    end = interval[1];
                } else end = Math.max(end, interval[1]);
            }
            return end == Long.MIN_VALUE ? 0 : total + end - start;
        }

        private synchronized Map<String, Object> snapshot() {
            Map<String, Object> result = new java.util.TreeMap<>();
            counters.forEach((name, value) -> result.put(name, value.sum()));
            long waitNanos = unionNanos(nanoTime.getAsLong());
            if (waitNanos > 0) result.put("llmHttpWaitNanos", waitNanos);
            return Map.copyOf(result);
        }
    }

    /** Khôi phục cả budget của caller khi worker chạy ngay trên cùng thread. */
    public static final class Scope implements AutoCloseable {
        private final Consumer<String> logger = LOG_CONSUMER.get();
        private final Long actor = ACTOR_USER_ID.get();
        private final Map<Object, Object> inputs = JOB_INPUTS.get();
        private final Integer budget = LLM_CALLS_REMAINING.get();
        private final Long updateId = SOURCE_UPDATE_ID.get();
        private final JobMetrics metrics = METRICS.get();
        private boolean closed;

        @Override public void close() {
            if (closed) return;
            closed = true;
            clear();
            if (logger != null) LOG_CONSUMER.set(logger);
            if (actor != null) ACTOR_USER_ID.set(actor);
            if (inputs != null) JOB_INPUTS.set(inputs);
            if (budget != null) LLM_CALLS_REMAINING.set(budget);
            if (updateId != null) SOURCE_UPDATE_ID.set(updateId);
            if (metrics != null) METRICS.set(metrics);
        }
    }

    /** Giữ ngân sách riêng cho batch, dùng chung qua các tầng retry và correction. */
    public static <T> T withLlmCallBudget(int limit, Supplier<T> task) {
        return withLlmCallBudget(new java.util.concurrent.atomic.AtomicInteger(limit), task);
    }

    public static <T> T withLlmCallBudget(java.util.concurrent.atomic.AtomicInteger budgetHolder, Supplier<T> task) {
        if (LLM_CALLS_REMAINING.get() != null) return task.get();
        LLM_CALLS_REMAINING.set(budgetHolder != null ? budgetHolder.get() : 3);
        try {
            return task.get();
        } finally {
            Integer remaining = LLM_CALLS_REMAINING.get();
            if (remaining != null && budgetHolder != null) {
                budgetHolder.set(remaining);
            }
            LLM_CALLS_REMAINING.remove();
        }
    }

    /** Chỉ tính lượt khi thực sự chuẩn bị gọi LLM; cache hit không chiếm ngân sách. */
    public static void reserveLlmCall() {
        Integer remaining = LLM_CALLS_REMAINING.get();
        if (remaining == null) return;
        if (remaining <= 0) {
            throw new LlmResponseException("Batch AI đã hết ngân sách gọi AI. Các batch đã lưu được giữ lại; hãy tiếp tục phần còn thiếu.");
        }
        LLM_CALLS_REMAINING.set(remaining - 1);
    }

    /** Lượt hỏng do hạ tầng hoặc output bị cắt không được ăn vào ngân sách sửa output của batch. */
    public static void refundLlmCall() {
        Integer remaining = LLM_CALLS_REMAINING.get();
        if (remaining != null) LLM_CALLS_REMAINING.set(remaining + 1);
    }

    public static boolean llmCallBudgetExhausted() {
        Integer remaining = LLM_CALLS_REMAINING.get();
        return remaining != null && remaining <= 0;
    }

    /** Giữ actor cho tác vụ đồng bộ và khôi phục context cũ khi kết thúc. */
    public static <T> T withActor(Long actorUserId, Supplier<T> task) {
        Long previousActor = ACTOR_USER_ID.get();
        ACTOR_USER_ID.set(actorUserId);
        try {
            return task.get();
        } finally {
            if (previousActor == null) ACTOR_USER_ID.remove();
            else ACTOR_USER_ID.set(previousActor);
        }
    }

    public static void log(String message) {
        Consumer<String> consumer = LOG_CONSUMER.get();
        if (consumer != null) consumer.accept(message);
    }

    /** Mỗi stage cập nhật source cần nhận log retry đúng stage và khôi phục callback của job ngoài. */
    public static <T> T withLogConsumer(Consumer<String> consumer, Supplier<T> task) {
        if (consumer == null) return task.get();
        Consumer<String> previous = LOG_CONSUMER.get();
        LOG_CONSUMER.set(consumer);
        try {
            return task.get();
        } finally {
            if (previous == null) LOG_CONSUMER.remove();
            else LOG_CONSUMER.set(previous);
        }
    }

    public static void clear() {
        LOG_CONSUMER.remove();
        ACTOR_USER_ID.remove();
        JOB_INPUTS.remove();
        LLM_CALLS_REMAINING.remove();
        SOURCE_UPDATE_ID.remove();
        METRICS.remove();
    }

    /**
     * Sao chép actor và callback log sang worker khác, sau đó luôn dọn ThreadLocal.
     */
    public static <T> Supplier<T> wrap(Supplier<T> task) {
        Long actorUserId = ACTOR_USER_ID.get();
        Consumer<String> logConsumer = LOG_CONSUMER.get();
        Map<Object, Object> inputs = JOB_INPUTS.get();
        JobMetrics metrics = METRICS.get();
        Long updateId = SOURCE_UPDATE_ID.get();
        return () -> {
            Scope previous = new Scope();
            bind(actorUserId, logConsumer, inputs);
            METRICS.set(metrics);
            SOURCE_UPDATE_ID.set(updateId);
            try {
                return task.get();
            } finally {
                previous.close();
            }
        };
    }
}
