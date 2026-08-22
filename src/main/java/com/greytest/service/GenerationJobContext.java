package com.greytest.service;

import java.util.function.Consumer;

/** Truyền callback log của job theo worker thread mà không làm LLM client phụ thuộc module nghiệp vụ. */
public final class GenerationJobContext {

    private static final ThreadLocal<Consumer<String>> LOG_CONSUMER = new ThreadLocal<>();
    private static final ThreadLocal<Long> ACTOR_USER_ID = new ThreadLocal<>();

    private GenerationJobContext() {}

    public static void bind(Long actorUserId, Consumer<String> logConsumer) {
        ACTOR_USER_ID.set(actorUserId);
        LOG_CONSUMER.set(logConsumer);
    }

    public static Long actorUserId() {
        return ACTOR_USER_ID.get();
    }

    public static void log(String message) {
        Consumer<String> consumer = LOG_CONSUMER.get();
        if (consumer != null) consumer.accept(message);
    }

    public static void clear() {
        LOG_CONSUMER.remove();
        ACTOR_USER_ID.remove();
    }
}
