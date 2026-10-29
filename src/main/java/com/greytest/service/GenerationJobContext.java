package com.greytest.service;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import com.greytest.service.agent.LlmResponseException;

/** Truyền callback log của job theo worker thread mà không làm LLM client phụ thuộc module nghiệp vụ. */
public final class GenerationJobContext {

    private static final ThreadLocal<Consumer<String>> LOG_CONSUMER = new ThreadLocal<>();
    private static final ThreadLocal<Long> ACTOR_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Map<Object, Object>> JOB_INPUTS = new ThreadLocal<>();
    private static final ThreadLocal<Integer> LLM_CALLS_REMAINING = new ThreadLocal<>();
    private static final ThreadLocal<Long> SOURCE_UPDATE_ID = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, LongAdder>> METRICS = new ThreadLocal<>();

    private GenerationJobContext() {}

    public static void bind(Long actorUserId, Consumer<String> logConsumer) {
        bind(actorUserId, logConsumer, new ConcurrentHashMap<>());
        METRICS.set(new ConcurrentHashMap<>());
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
    public static void recordDuration(String name, long nanos) { addMetric(name + "Nanos", nanos); }

    private static void addMetric(String name, long value) {
        Map<String, LongAdder> metrics = METRICS.get();
        if (metrics != null) metrics.computeIfAbsent(name, ignored -> new LongAdder()).add(value);
    }

    public static Map<String, Object> metrics() {
        Map<String, Object> result = new java.util.TreeMap<>();
        Map<String, LongAdder> metrics = METRICS.get();
        if (metrics != null) metrics.forEach((name, value) -> result.put(name, value.sum()));
        return Map.copyOf(result);
    }

    /** Khôi phục cả budget của caller khi worker chạy ngay trên cùng thread. */
    public static final class Scope implements AutoCloseable {
        private final Consumer<String> logger = LOG_CONSUMER.get();
        private final Long actor = ACTOR_USER_ID.get();
        private final Map<Object, Object> inputs = JOB_INPUTS.get();
        private final Integer budget = LLM_CALLS_REMAINING.get();
        private final Long updateId = SOURCE_UPDATE_ID.get();
        private final Map<String, LongAdder> metrics = METRICS.get();
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
        if (LLM_CALLS_REMAINING.get() != null) return task.get();
        LLM_CALLS_REMAINING.set(limit);
        try {
            return task.get();
        } finally {
            LLM_CALLS_REMAINING.remove();
        }
    }

    /** Chỉ tính lượt khi thực sự chuẩn bị gọi LLM; cache hit không chiếm ngân sách. */
    public static void reserveLlmCall() {
        Integer remaining = LLM_CALLS_REMAINING.get();
        if (remaining == null) return;
        if (remaining <= 0) {
            throw new LlmResponseException("Batch BR đã hết ngân sách gọi AI. Các batch đã lưu được giữ lại; hãy tiếp tục phần còn thiếu.");
        }
        LLM_CALLS_REMAINING.set(remaining - 1);
    }

    public static boolean llmCallBudgetExhausted() {
        Integer remaining = LLM_CALLS_REMAINING.get();
        return remaining != null && remaining <= 0;
    }

    public static void log(String message) {
        Consumer<String> consumer = LOG_CONSUMER.get();
        if (consumer != null) consumer.accept(message);
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
        Map<String, LongAdder> metrics = METRICS.get();
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
