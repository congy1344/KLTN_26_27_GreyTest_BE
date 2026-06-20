package com.greytest.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Test thuần, không load Spring context để khỏi cần DB khi chạy CI lần đầu.
class HealthControllerTest {

    @Test
    void pingReturnsOk() {
        assertThat(new HealthController().ping())
                .containsEntry("status", "ok")
                .containsEntry("service", "greytest");
    }
}
