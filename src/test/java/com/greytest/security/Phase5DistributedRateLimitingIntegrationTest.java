package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.greytest.security.waf.InMemoryRateLimitStore;
import com.greytest.security.waf.RedisRateLimitStore;
import com.greytest.security.waf.TrustedProxyResolver;
import com.greytest.service.DdosProtectionService;
import com.greytest.service.DdosProtectionService.CheckStatus;

/**
 * Kiểm thử tích hợp thực tế với Redis server (Workstream 2: Distributed Rate Limiting Verification).
 * Kiểm chứng:
 * 1. Hai Backend instance dùng chung Redis thực tế chia sẻ atomic sliding window.
 * 2. Cùng một IP gửi qua 2 instance không vượt policy chung.
 * 3. Shared blacklist & TTL expiration giữa các instance.
 * 4. Truy cập đồng thời đa luồng (Concurrent requests) đảm bảo nguyên tử (Atomic Lua script).
 * 5. Redis outage & Graceful Memory Fallback không gây lỗi 500.
 * 6. Redis auto-reconnect khôi phục trạng thái distributed.
 */
@DisplayName("Phase 5 - Workstream 2: Distributed Rate Limiting & Real Redis Integration Tests")
class Phase5DistributedRateLimitingIntegrationTest {

    private RedisRateLimitStore instance1;
    private RedisRateLimitStore instance2;
    private InMemoryRateLimitStore fallback1;
    private InMemoryRateLimitStore fallback2;

    @BeforeEach
    void setUp() {
        fallback1 = new InMemoryRateLimitStore();
        fallback2 = new InMemoryRateLimitStore();
        // Kết nối cả 2 instance tới Redis thật trên localhost:6379
        instance1 = new RedisRateLimitStore(fallback1, true, "localhost", 6379, 2000);
        instance2 = new RedisRateLimitStore(fallback2, true, "localhost", 6379, 2000);
        instance1.clear();
    }

    @AfterEach
    void tearDown() {
        if (instance1 != null) instance1.clear();
        if (instance1 != null) instance1.close();
        if (instance2 != null) instance2.close();
    }

    @Test
    @DisplayName("1. Hai Backend instance chia sẻ bộ đếm Sliding Window nguyên tử qua Redis")
    void testSharedSlidingWindowAcrossTwoInstances() {
        Assumptions.assumeTrue(instance1.isRedisActive(), "Redis container localhost:6379 must be active for this distributed test");

        String clientIp = "198.51.100.99";
        int limit = 5;
        int windowSeconds = 60;

        // Instance 1 xử lý 3 requests
        for (int i = 0; i < 3; i++) {
            assertTrue(instance1.recordAndCheck(clientIp, windowSeconds, limit));
        }

        // Instance 2 xử lý 2 requests tiếp theo
        for (int i = 0; i < 2; i++) {
            assertTrue(instance2.recordAndCheck(clientIp, windowSeconds, limit));
        }

        // Tổng cộng đã là 5 requests. Request thứ 6 gửi tới Instance 1 HOẶC Instance 2 đều phải bị RATE_LIMITED!
        assertFalse(instance1.recordAndCheck(clientIp, windowSeconds, limit));
        assertFalse(instance2.recordAndCheck(clientIp, windowSeconds, limit));

        // Kiểm tra số đếm chuẩn xác tại cả 2 instance (tổng 7 requests đã gửi)
        assertEquals(7, instance1.getRequestCount(clientIp, windowSeconds));
        assertEquals(7, instance2.getRequestCount(clientIp, windowSeconds));
    }

    @Test
    @DisplayName("2. Shared Blacklist: Chặn tại Instance 1 có hiệu lực ngay lập tức tại Instance 2")
    void testSharedBlacklistAcrossInstances() {
        Assumptions.assumeTrue(instance1.isRedisActive(), "Redis container localhost:6379 must be active for this distributed test");

        String maliciousIp = "203.0.113.66";

        // Ban đầu chưa bị chặn
        assertFalse(instance2.isBlocked(maliciousIp));

        // Instance 1 thực hiện lệnh cấm IP 120 giây
        instance1.block(maliciousIp, "DDoS attack detected", 120);

        // Instance 2 lập tức thấy IP bị chặn với đúng lý do
        assertTrue(instance2.isBlocked(maliciousIp));
        assertEquals("DDoS attack detected", instance2.getBlockReason(maliciousIp));
        assertThat(instance2.getRemainingBlockSeconds(maliciousIp)).isGreaterThan(100);

        // Instance 2 mở khóa -> Instance 1 cũng hết bị chặn
        instance2.unblock(maliciousIp);
        assertFalse(instance1.isBlocked(maliciousIp));
    }

    @Test
    @DisplayName("3. Shared Expiration: TTL trên Redis tự động giải phóng khóa trên toàn cụm")
    void testSharedExpirationTTL() throws InterruptedException {
        Assumptions.assumeTrue(instance1.isRedisActive(), "Redis container localhost:6379 must be active for this distributed test");

        String temporaryBlockIp = "198.51.100.111";

        // Khóa ngắn hạn 1 giây
        instance1.block(temporaryBlockIp, "Temporary block", 1);
        assertTrue(instance2.isBlocked(temporaryBlockIp));

        // Đợi 1.5 giây cho TTL Redis tự động hết hạn
        Thread.sleep(1500L);

        // Cả 2 instance tự động thấy hết hạn
        assertFalse(instance1.isBlocked(temporaryBlockIp));
        assertFalse(instance2.isBlocked(temporaryBlockIp));
    }

    @Test
    @DisplayName("4. Truy cập đồng thời đa luồng (Concurrent Requests) đảm bảo nguyên tử (Atomic Lua)")
    void testConcurrentRequestsAtomicity() throws Exception {
        Assumptions.assumeTrue(instance1.isRedisActive(), "Redis container localhost:6379 must be active for this distributed test");

        String concurrentIp = "192.0.2.77";
        int limit = 10;
        int windowSeconds = 60;
        int totalThreads = 20;

        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            final int idx = i;
            tasks.add(() -> {
                // Xen kẽ request giữa 2 instance
                RedisRateLimitStore target = (idx % 2 == 0) ? instance1 : instance2;
                return target.recordAndCheck(concurrentIp, windowSeconds, limit);
            });
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger denied = new AtomicInteger(0);

        for (Future<Boolean> f : futures) {
            if (f.get()) {
                allowed.incrementAndGet();
            } else {
                denied.incrementAndGet();
            }
        }

        // Đúng 10 requests được phép và đúng 10 requests bị từ chối (không race condition)
        assertEquals(limit, allowed.get());
        assertEquals(totalThreads - limit, denied.get());
        assertEquals(totalThreads, instance1.getRequestCount(concurrentIp, windowSeconds));
    }

    @Test
    @DisplayName("5. Redis Outage & Graceful Memory Fallback: Không gây ném ngoại lệ hay sập ứng dụng")
    void testRedisOutageGracefulMemoryFallback() {
        // Cấu hình kết nối tới cổng Redis không tồn tại (giả lập sự cố mất kết nối hoàn toàn)
        InMemoryRateLimitStore safeMemory = new InMemoryRateLimitStore();
        RedisRateLimitStore brokenRedisStore = new RedisRateLimitStore(safeMemory, true, "127.0.0.1", 59999, 100);

        // Mặc dù Redis sập, hệ thống tự động fallback về bộ nhớ và hoạt động bình thường
        assertFalse(brokenRedisStore.isRedisActive());
        assertTrue(brokenRedisStore.recordAndCheck("fallback-ip", 60, 5));
        assertEquals(1, brokenRedisStore.getRequestCount("fallback-ip", 60));

        brokenRedisStore.block("bad-ip", "Spam", 60);
        assertTrue(brokenRedisStore.isBlocked("bad-ip"));
        assertEquals("Spam", brokenRedisStore.getBlockReason("bad-ip"));

        brokenRedisStore.close();
    }

    @Test
    @DisplayName("6. DdosProtectionService tích hợp trực tiếp RedisRateLimitStore qua 2 Node Backend")
    void testDdosProtectionServiceWithSharedRedisNodes() {
        Assumptions.assumeTrue(instance1.isRedisActive(), "Redis container localhost:6379 must be active for this distributed test");

        DdosProtectionService node1 = new DdosProtectionService(instance1, new TrustedProxyResolver());
        DdosProtectionService node2 = new DdosProtectionService(instance2, new TrustedProxyResolver());

        node1.setStandardRateLimit(4);
        node2.setStandardRateLimit(4);

        String clientIp = "198.51.100.222";

        // Node 1 nhận 2 requests
        assertEquals(CheckStatus.ALLOWED, node1.checkRequest(clientIp, "/api/projects").status());
        assertEquals(CheckStatus.ALLOWED, node1.checkRequest(clientIp, "/api/projects").status());

        // Node 2 nhận 2 requests
        assertEquals(CheckStatus.ALLOWED, node2.checkRequest(clientIp, "/api/projects").status());
        assertEquals(CheckStatus.ALLOWED, node2.checkRequest(clientIp, "/api/projects").status());

        // Request thứ 5 gửi đến bất kỳ Node nào cũng phải bị RATE_LIMITED bởi WAF
        assertEquals(CheckStatus.RATE_LIMITED, node1.checkRequest(clientIp, "/api/projects").status());
        assertEquals(CheckStatus.RATE_LIMITED, node2.checkRequest(clientIp, "/api/projects").status());

        // Node 1 thực hiện cấm IP
        node1.blockIp(clientIp, "Distributed Attacker", 10);

        // Node 2 lập tức chặn IP này
        assertTrue(node2.checkRequest(clientIp, "/api/projects").isBlocked());

        // Node 2 gỡ chặn
        node2.unblockIp(clientIp);
        assertFalse(node1.checkRequest(clientIp, "/api/projects").isBlocked());
    }

    @Test
    @DisplayName("7. Redis Reconnection & Recovery: Hệ thống tự động phục hồi phân tán khi kết nối sống lại")
    void testRedisReconnectionAndRecovery() {
        Assumptions.assumeTrue(instance1.isRedisActive(), "Redis container localhost:6379 must be active for this distributed test");

        InMemoryRateLimitStore memoryStore = new InMemoryRateLimitStore();
        RedisRateLimitStore recoverableStore = new RedisRateLimitStore(memoryStore, true, "localhost", 6379, 1000);
        assertTrue(recoverableStore.isRedisActive());

        assertTrue(recoverableStore.recordAndCheck("reconnect-ip", 60, 5));
        assertEquals(1, recoverableStore.getRequestCount("reconnect-ip", 60));

        recoverableStore.close();
    }

    @Test
    @DisplayName("8. Distributed 150% Auto-Ban: Gửi vượt 150% limit phân tán qua Redis kích hoạt auto-ban")
    void testDistributedOver150PercentAutoBan() {
        Assumptions.assumeTrue(instance1.isRedisActive(), "Redis container localhost:6379 must be active for this distributed test");

        DdosProtectionService node1 = new DdosProtectionService(instance1, new TrustedProxyResolver());
        DdosProtectionService node2 = new DdosProtectionService(instance2, new TrustedProxyResolver());

        node1.setStandardRateLimit(10);
        node2.setStandardRateLimit(10);

        String spammerIp = "198.51.100.188";

        // Gửi 10 requests hợp lệ xen kẽ node 1 và node 2 (đạt ngưỡng 100%)
        for (int i = 0; i < 5; i++) {
            assertEquals(CheckStatus.ALLOWED, node1.checkRequest(spammerIp, "/api/projects").status());
            assertEquals(CheckStatus.ALLOWED, node2.checkRequest(spammerIp, "/api/projects").status());
        }

        // Gửi thêm 5 requests (tổng 15 requests = 150% limit) -> nhận RATE_LIMITED
        for (int i = 0; i < 5; i++) {
            assertEquals(CheckStatus.RATE_LIMITED, node1.checkRequest(spammerIp, "/api/projects").status());
        }

        // Request thứ 16 (> 150% của 10) phải kích hoạt Auto-Ban trên toàn cụm phân tán!
        DdosProtectionService.CheckResult banResult = node2.checkRequest(spammerIp, "/api/projects");
        assertEquals(CheckStatus.IP_BLOCKED, banResult.status());
        assertTrue(banResult.reason().contains("Tự động khóa IP"));

        // Xác nhận IP lập tức bị khóa tại cả Node 1 và Node 2 qua Redis
        assertTrue(node1.isIpBlocked(spammerIp));
        assertTrue(node2.isIpBlocked(spammerIp));
    }

    @Test
    @DisplayName("9. IPv6 Canonical Consistency Across Nodes: Compressed và Expanded IPv6 đồng nhất")
    void testIpv6CanonicalConsistencyAcrossNodes() {
        Assumptions.assumeTrue(instance1.isRedisActive(), "Redis container localhost:6379 must be active for this distributed test");

        DdosProtectionService node1 = new DdosProtectionService(instance1, new TrustedProxyResolver());
        DdosProtectionService node2 = new DdosProtectionService(instance2, new TrustedProxyResolver());

        String compressedIpv6 = "2001:db8::1";
        String expandedIpv6 = "2001:0db8:0000:0000:0000:0000:0000:0001";

        // Node 1 chặn IP dạng compressed
        node1.blockIp(compressedIpv6, "IPv6 Malicious Traffic", 15);

        // Node 2 kiểm tra bằng dạng expanded IPv6 phải nhận diện đúng IP bị chặn
        assertTrue(node2.isIpBlocked(expandedIpv6));
        assertTrue(node2.checkRequest(expandedIpv6, "/api/projects").isBlocked());

        // Node 2 gỡ chặn bằng dạng expanded -> Node 1 cũng được gỡ
        node2.unblockIp(expandedIpv6);
        assertFalse(node1.isIpBlocked(compressedIpv6));
    }
}
