package com.greytest.security.waf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisRateLimitStoreTest {

    @Test
    @DisplayName("Khởi tạo RedisRateLimitStore với password không hợp lệ chuyển sang In-Memory Fallback an toàn")
    void constructorWithPasswordFallsBackGracefullyOnConnectionFailure() {
        InMemoryRateLimitStore memoryFallback = new InMemoryRateLimitStore();
        // Port 59998 không có redis lắng nghe
        RedisRateLimitStore store = new RedisRateLimitStore(memoryFallback, true, "127.0.0.1", 59998, 500, "wrong_password");
        
        assertThat(store.isRedisActive()).isFalse();
        
        // Thao tác vẫn an toàn qua In-Memory fallback
        boolean allowed = store.recordAndCheck("test-key", 60, 10);
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("Khởi tạo RedisRateLimitStore với password null/blank không ném lỗi NullPointerException")
    void constructorWithBlankPasswordDoesNotThrowNpe() {
        InMemoryRateLimitStore memoryFallback = new InMemoryRateLimitStore();
        RedisRateLimitStore store = new RedisRateLimitStore(memoryFallback, true, "127.0.0.1", 59998, 500, "");
        assertThat(store.isRedisActive()).isFalse();
        
        RedisRateLimitStore storeNull = new RedisRateLimitStore(memoryFallback, true, "127.0.0.1", 59998, 500, null);
        assertThat(storeNull.isRedisActive()).isFalse();
    }
}
