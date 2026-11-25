package com.greytest.security.waf;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Triển khai RateLimitStore trên bộ nhớ trong (In-Memory) hiệu năng cao,
 * thread-safe.
 * Phù hợp hoàn hảo cho triển khai Single-Node của đồ án tốt nghiệp, không phụ
 * thuộc hạ tầng ngoài.
 * Có cơ chế giới hạn dung lượng bộ nhớ (Bounded Memory) và tự động dọn dẹp các
 * key nhàn rỗi.
 */
@Component
public class InMemoryRateLimitStore implements RateLimitStore {

    public static final int MAX_TRACKED_KEYS = 20_000;

    public static class BlockedEntry {
        public final String key;
        public final String reason;
        public final long blockedAtMs;
        public final long expiresAtMs;
        public final AtomicInteger violationCount;

        public BlockedEntry(String key, String reason, long blockedAtMs, long expiresAtMs, int initialViolations) {
            this.key = key;
            this.reason = reason;
            this.blockedAtMs = blockedAtMs;
            this.expiresAtMs = expiresAtMs;
            this.violationCount = new AtomicInteger(initialViolations);
        }
    }

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> timestampsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BlockedEntry> blockedMap = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong lastCleanupMs = new java.util.concurrent.atomic.AtomicLong(0);

    @Override
    public boolean recordAndCheck(String key, int windowSeconds, int maxRequests) {
        if (key == null || key.isBlank()) {
            return true;
        }
        if (isBlocked(key)) {
            return false;
        }

        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000L);

        // 1. Kiểm soát dung lượng bộ nhớ định kỳ qua cooldown 5s
        if (timestampsMap.size() > MAX_TRACKED_KEYS) {
            long last = lastCleanupMs.get();
            if (now - last >= 5000L && lastCleanupMs.compareAndSet(last, now)) {
                evictIdleKeys(windowStart);
            }
        }

        // 2. Chính sách xử lý IP mới khi chạm trần dung lượng MAX_TRACKED_KEYS (không âm thầm bypass):
        // Loại bỏ 1 key cũ nhất để nhường chỗ theo dõi IP mới, bảo đảm IP mới LUÔN ĐƯỢC RATE LIMIT
        if (!timestampsMap.containsKey(key) && timestampsMap.size() >= MAX_TRACKED_KEYS) {
            Iterator<String> it = timestampsMap.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }

        ConcurrentLinkedDeque<Long> deque = timestampsMap.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(now);

        // Xóa dấu thời gian cũ nằm ngoài cửa sổ trượt
        while (!deque.isEmpty() && deque.peekFirst() < windowStart) {
            deque.pollFirst();
        }

        return deque.size() <= maxRequests;
    }

    @Override
    public int getRequestCount(String key, int windowSeconds) {
        if (key == null || key.isBlank())
            return 0;
        ConcurrentLinkedDeque<Long> deque = timestampsMap.get(key);
        if (deque == null)
            return 0;

        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000L);

        int count = 0;
        Iterator<Long> it = deque.descendingIterator();
        while (it.hasNext()) {
            if (it.next() >= windowStart) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    @Override
    public boolean isBlocked(String key) {
        if (key == null || key.isBlank())
            return false;
        BlockedEntry entry = blockedMap.get(key);
        if (entry == null)
            return false;

        long now = System.currentTimeMillis();
        if (now < entry.expiresAtMs) {
            return true;
        } else {
            // Đã hết thời hạn chặn -> tự động giải phóng
            blockedMap.remove(key, entry);
            return false;
        }
    }

    @Override
    public void block(String key, String reason, long durationSeconds) {
        if (key == null || key.isBlank())
            return;
        long now = System.currentTimeMillis();
        long expiresAt = now + (durationSeconds * 1000L);

        blockedMap.compute(key, (k, existing) -> {
            int violations = (existing != null) ? existing.violationCount.incrementAndGet() : 1;
            return new BlockedEntry(key, reason, now, expiresAt, violations);
        });
    }

    @Override
    public boolean unblock(String key) {
        if (key == null || key.isBlank())
            return false;
        timestampsMap.remove(key);
        for (String tier : java.util.List.of("standard", "auth", "upload", "generation", "export", "admin")) {
            timestampsMap.remove(key + ":" + tier);
        }
        return blockedMap.remove(key) != null;
    }

    @Override
    public void clear() {
        timestampsMap.clear();
        blockedMap.clear();
    }

    @Override
    public String getBlockReason(String key) {
        BlockedEntry entry = blockedMap.get(key);
        return (entry != null && System.currentTimeMillis() < entry.expiresAtMs) ? entry.reason : null;
    }

    @Override
    public int getRemainingBlockSeconds(String key) {
        BlockedEntry entry = blockedMap.get(key);
        if (entry == null)
            return 0;
        long now = System.currentTimeMillis();
        if (now >= entry.expiresAtMs)
            return 0;
        return (int) Math.max(1, (entry.expiresAtMs - now + 999L) / 1000L);
    }

    /**
     * Dọn dẹp các IP/Key không có hoạt động mới ngoài cửa sổ trượt để bảo vệ RAM.
     */
    public void evictIdleKeys(long thresholdTime) {
        timestampsMap.entrySet().removeIf(e -> {
            ConcurrentLinkedDeque<Long> deque = e.getValue();
            while (!deque.isEmpty() && deque.peekFirst() < thresholdTime) {
                deque.pollFirst();
            }
            return deque.isEmpty();
        });
        if (timestampsMap.size() > MAX_TRACKED_KEYS) {
            Iterator<String> it = timestampsMap.keySet().iterator();
            int toRemove = timestampsMap.size() - MAX_TRACKED_KEYS;
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }
    }

    public ConcurrentHashMap<String, BlockedEntry> getBlockedMap() {
        return blockedMap;
    }

    public int getActiveTrackedKeyCount() {
        return timestampsMap.size();
    }
}
