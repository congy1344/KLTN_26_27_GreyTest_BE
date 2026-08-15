package com.greytest.service;

import java.util.function.Consumer;

/** Truyền callback log của job theo worker thread mà không làm LLM client phụ thuộc module nghiệp vụ. */
public final class GenerationJobContext {

    private static final ThreadLocal<Consumer<String>> LOG_CONSUMER = new ThreadLocal<>();

    private GenerationJobContext() {}

    public static void bind(Consumer<String> logConsumer) {
        LOG_CONSUMER.set(logConsumer);
    }

    public static void log(String message) {
        Consumer<String> consumer = LOG_CONSUMER.get();
        if (consumer != null) consumer.accept(message);
    }

    public static void clear() {
        LOG_CONSUMER.remove();
    }
}
