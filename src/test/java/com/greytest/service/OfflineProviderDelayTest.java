package com.greytest.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Kiểm tra độ trễ mô phỏng nằm tại provider boundary và không nuốt lỗi/interruption. */
class OfflineProviderDelayTest {
    @Test void zeroDelayPreservesResponse() throws Throwable {
        assertThat(OfflineSourceUpdateBenchmarkTest.delayed(0, () -> 42)).isEqualTo(42);
    }
    @Test void waitsBeforeReturningProviderResponse() throws Throwable {
        long started=System.nanoTime();
        assertThat(OfflineSourceUpdateBenchmarkTest.delayed(500, () -> 42)).isEqualTo(42);
        assertThat((System.nanoTime()-started)/1_000_000).isGreaterThanOrEqualTo(450);
    }
    @Test void rejectsUnspecifiedDelay() {
        assertThatThrownBy(()->OfflineSourceUpdateBenchmarkTest.delayed(-1,()->42)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void preservesProviderFailure() {
        var failure=new IllegalStateException("expected");
        assertThatThrownBy(()->OfflineSourceUpdateBenchmarkTest.delayed(0,()->{throw failure;})).isSameAs(failure);
    }
    @Test void interruptionDoesNotCallProvider() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->OfflineSourceUpdateBenchmarkTest.delayed(500,()->{throw new AssertionError("called");}))
                    .isInstanceOf(InterruptedException.class);
        } finally {Thread.interrupted();}
    }
}
