package com.greytest.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.greytest.dto.admin.AdminDtos.BlockedIpDto;
import com.greytest.dto.admin.AdminDtos.WafStatusDto;
import com.greytest.security.waf.InMemoryRateLimitStore;
import com.greytest.security.waf.RateLimitStore;
import com.greytest.security.waf.TrustedProxyResolver;

/**
 * Dịch vụ bảo vệ chống tấn công từ chối dịch vụ (DDoS) và ngăn chặn Spam tầng ứng dụng (Enterprise WAF Layer 7).
 * Cung cấp:
 * 1. Giới hạn tần suất request theo phân loại endpoint (Multi-Dimensional Tiered Rate Limiting: Auth, Upload, Gen, Export, Admin, General).
 * 2. Độc lập phát hiện tấn công dồn dập (Burst Attack / Spike Detection) trước khi xét hạn mức theo phút.
 * 3. Tự động đưa vào danh sách đen lũy tiến (Progressive Auto-Ban: 10m -> 30m -> 60m) khi vượt 150% ngưỡng hoặc burst spike.
 * 4. Chống khóa nhầm tài khoản/IP quản trị (Anti-self-lockout) và xóa bỏ đặc quyền miễn trừ login tùy tiện.
 * 5. Giới hạn dung lượng bộ nhớ (Bounded Memory 20,000 IPs) chống cạn kiệt tài nguyên máy chủ.
 * 6. Tích hợp trừu tượng hóa RateLimitStore hỗ trợ cả Single-Node và cụm phân tán.
 */
@Service
public class DdosProtectionService {

    public enum CheckStatus {
        ALLOWED,
        RATE_LIMITED,
        IP_BLOCKED
    }

    public record CheckResult(CheckStatus status, String reason, int retryAfterSeconds) {
        public boolean isBlocked() {
            return status != CheckStatus.ALLOWED;
        }
    }

    public static class BlockedRecord {
        public final String ip;
        public final String reason;
        public final long blockedAt;
        public final long expiresAt;
        public final AtomicInteger violationCount;

        public BlockedRecord(String ip, String reason, long blockedAt, long expiresAt, int initialViolations) {
            this.ip = ip;
            this.reason = reason;
            this.blockedAt = blockedAt;
            this.expiresAt = expiresAt;
            this.violationCount = new AtomicInteger(initialViolations);
        }
    }

    // Giới hạn bộ nhớ tối đa cho bảng theo dõi IP
    public static final int MAX_TRACKED_IPS = 20_000;

    // Cấu hình WAF & DDoS
    private final AtomicBoolean wafActive = new AtomicBoolean(true);
    private final AtomicBoolean underAttackMode = new AtomicBoolean(false);
    private final AtomicBoolean emergencySafeMode = new AtomicBoolean(false);
    private final AtomicLong emergencySafeModeExpiresAt = new AtomicLong(0);
    private final AtomicBoolean exemptDockerSubnets = new AtomicBoolean(false);

    // Ngưỡng tần suất (requests/phút)
    private final AtomicInteger standardRateLimit = new AtomicInteger(100);
    private final AtomicInteger underAttackRateLimit = new AtomicInteger(30);
    private final AtomicInteger burstPerSecondLimit = new AtomicInteger(200);

    // Ngưỡng phân loại Endpoint Tiers (requests/phút)
    private final AtomicInteger authTierLimit = new AtomicInteger(20);
    private final AtomicInteger uploadTierLimit = new AtomicInteger(10);
    private final AtomicInteger generationTierLimit = new AtomicInteger(15);
    private final AtomicInteger exportTierLimit = new AtomicInteger(30);
    private final AtomicInteger adminTierLimit = new AtomicInteger(60);

    // Số liệu giám sát & thống kê an ninh (Security Analytics)
    private final AtomicLong totalBlockedRequests = new AtomicLong(0);
    private final AtomicLong totalBurstAttacks = new AtomicLong(0);
    private final AtomicLong totalRateLimitExceeded = new AtomicLong(0);
    private final AtomicLong totalAutoBans = new AtomicLong(0);

    // Lưu trữ dấu thời gian các request gần nhất theo từng client IP và tier
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> ipRequestTimestamps = new ConcurrentHashMap<>();

    // Dấu thời gian đo lường burst 1 giây per client IP
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> ipBurstTimestamps = new ConcurrentHashMap<>();

    // Lịch sử vi phạm để áp dụng thời gian khóa lũy tiến (10m -> 30m -> 60m)
    private final ConcurrentHashMap<String, AtomicInteger> ipViolationHistory = new ConcurrentHashMap<>();

    private final AtomicLong lastCleanupMs = new AtomicLong(0);

    // Danh sách IP bị chặn / cách ly
    private final ConcurrentHashMap<String, BlockedRecord> blockedIps = new ConcurrentHashMap<>();

    // Store trừu tượng
    private final RateLimitStore rateLimitStore;
    private final TrustedProxyResolver trustedProxyResolver;

    public DdosProtectionService() {
        this(new InMemoryRateLimitStore(), new TrustedProxyResolver());
    }

    @Autowired
    public DdosProtectionService(
            @Autowired(required = false) RateLimitStore rateLimitStore,
            @Autowired(required = false) TrustedProxyResolver trustedProxyResolver) {
        this.rateLimitStore = rateLimitStore != null ? rateLimitStore : new InMemoryRateLimitStore();
        this.trustedProxyResolver = trustedProxyResolver != null ? trustedProxyResolver : new TrustedProxyResolver();
    }

    /**
     * Chuẩn hóa và kiểm tra tính hợp lệ của địa chỉ IP Client.
     * Sử dụng TrustedProxyResolver để chuẩn hóa IPv4, IPv6, IPv4-mapped IPv6 và loại bỏ port.
     */
    public static String normalizeClientIp(String rawIp) {
        return TrustedProxyResolver.normalizeIp(rawIp);
    }

    /**
     * Chuyển đổi địa chỉ IP sang dạng chuẩn canonical duy nhất (RFC 5952 cho IPv6) để dùng làm key nội bộ.
     */
    public static String toCanonicalIp(String ip) {
        if (ip == null || ip.isBlank()) return "127.0.0.1";
        String candidate = TrustedProxyResolver.extractIpCandidate(ip);
        String canonical = TrustedProxyResolver.canonicalizeIp(candidate);
        return canonical != null ? canonical : candidate;
    }

    /**
     * Kiểm tra địa chỉ IP Client có thuộc localhost, loopback hoặc mạng nội bộ Docker (172.16.0.0/12) không.
     */
    public static boolean isLoopbackOrDockerIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        String candidate = normalizeClientIp(ip);
        if ("127.0.0.1".equals(candidate)
                || "0:0:0:0:0:0:0:1".equals(candidate)
                || "::1".equals(candidate)
                || "localhost".equalsIgnoreCase(candidate)
                || candidate.startsWith("127.")) {
            return true;
        }
        if (candidate.startsWith("172.")) {
            String[] parts = candidate.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    public static boolean isLoopback(String ip) {
        if (ip == null || ip.isBlank()) return false;
        String candidate = normalizeClientIp(ip);
        return "127.0.0.1".equals(candidate)
                || "0:0:0:0:0:0:0:1".equals(candidate)
                || "::1".equals(candidate)
                || "localhost".equalsIgnoreCase(candidate)
                || candidate.startsWith("127.");
    }

    /**
     * Kiểm tra nhanh một request HTTP từ Client IP và URI.
     * Thứ tự kiểm tra:
     * 1. WAF Active & IP Blacklist.
     * 2. Loopback/Docker exemption (nếu được bật).
     * 3. Phân loại Endpoint Tier (Auth, Upload, Generation, Export, Admin, General).
     * 4. Độc lập kiểm tra Burst trong 1 giây (nếu vượt -> auto-ban 15 phút ngay lập tức).
     * 5. Kiểm tra Rate limit cửa sổ trượt 60 giây (nếu > 150% -> progressive auto-ban 10-60 phút).
     */
    public CheckResult checkRequest(String clientIp, String uri) {
        if (!wafActive.get() || clientIp == null || clientIp.isBlank()) {
            return new CheckResult(CheckStatus.ALLOWED, "WAF Disabled", 0);
        }
        clientIp = toCanonicalIp(clientIp);

        // Miễn trừ tuyệt đối cho loopback (127.0.0.1, ::1)
        if (isLoopback(clientIp)) {
            blockedIps.remove(clientIp);
            return new CheckResult(CheckStatus.ALLOWED, "Loopback IP exempt", 0);
        }

        // Miễn trừ riêng cho Docker subnets chỉ khi cờ exemptDockerSubnets được bật
        if (exemptDockerSubnets.get() && isLoopbackOrDockerIp(clientIp)) {
            blockedIps.remove(clientIp);
            return new CheckResult(CheckStatus.ALLOWED, "Local/Docker IP exempt", 0);
        }

        long now = System.currentTimeMillis();

        // 1. Kiểm tra IP có trong danh sách đen (Blacklist) hay không
        if (rateLimitStore != null) {
            if (rateLimitStore.isBlocked(clientIp)) {
                totalBlockedRequests.incrementAndGet();
                int remaining = rateLimitStore.getRemainingBlockSeconds(clientIp);
                String reason = rateLimitStore.getBlockReason(clientIp);
                return new CheckResult(CheckStatus.IP_BLOCKED, "IP bị khóa do nghi vấn DDoS/Spam: " + (reason != null ? reason : "Store Blacklisted"), remaining > 0 ? remaining : 60);
            } else {
                blockedIps.remove(clientIp);
            }
        } else {
            BlockedRecord blocked = blockedIps.get(clientIp);
            if (blocked != null) {
                if (now < blocked.expiresAt) {
                    totalBlockedRequests.incrementAndGet();
                    blocked.violationCount.incrementAndGet();
                    int remainingSeconds = (int) Math.max(1, (blocked.expiresAt - now) / 1000);
                    return new CheckResult(CheckStatus.IP_BLOCKED, "IP bị khóa do nghi vấn DDoS/Spam: " + blocked.reason, remainingSeconds);
                } else {
                    // Hết hạn phạt, tự động gỡ chặn
                    blockedIps.remove(clientIp);
                }
            }
        }

        // IP còn lại là chính proxy tin cậy nghĩa là proxy không gửi X-Forwarded-For: không biết client nào,
        // chặn IP này sẽ khóa toàn bộ người dùng phía sau proxy.
        if (trustedProxyResolver.isTrustedProxy(clientIp)) {
            return new CheckResult(CheckStatus.ALLOWED, "Trusted proxy exempt", 0);
        }

        // Lưu ý Workstream C: Tuyệt đối KHÔNG miễn trừ toàn bộ cho /api/auth/admin/login để tránh tấn công vét cạn.
        // Endpoint đăng nhập admin được bảo vệ bằng Auth Tier (20 req/phút) và Admin Brute-Force Protection.

        // 2. Burst Attack (> burstPerSecondLimit req/s) xét theo clientIp tổng thể (Workstream B)
        ConcurrentLinkedDeque<Long> burstQueue = ipBurstTimestamps.computeIfAbsent(clientIp, k -> new ConcurrentLinkedDeque<>());
        burstQueue.addLast(now);
        long oneSecondAgo = now - 1000L;
        while (!burstQueue.isEmpty() && burstQueue.peekFirst() < oneSecondAgo) {
            burstQueue.pollFirst();
        }

        if (burstQueue.size() > burstPerSecondLimit.get()) {
            totalBurstAttacks.incrementAndGet();
            totalBlockedRequests.incrementAndGet();
            totalAutoBans.incrementAndGet();
            // Tự động ban IP 15 phút vì có dấu hiệu công cụ DDoS/Spam script dồn dập
            autoBlockIp(clientIp, "Tần suất đột biến vượt ngưỡng (" + burstQueue.size() + " req/giây)", 15);
            return new CheckResult(CheckStatus.IP_BLOCKED, "Phát hiện tần suất đột biến DDoS (" + burstQueue.size() + " req/s)", 900);
        }

        // 3. Phân loại Endpoint (Multi-Dimensional Tier) và kiểm tra Rate Limit
        int effectiveLimit = resolveTierLimit(uri);
        String tier = resolveTierName(uri);
        String rateKey = clientIp + ":" + tier;

        // Bounded Memory: dọn dẹp nếu kích thước request hoặc burst vượt ngưỡng MAX_TRACKED_IPS
        if (ipRequestTimestamps.size() > MAX_TRACKED_IPS || ipBurstTimestamps.size() > MAX_TRACKED_IPS) {
            cleanupIdleIpTracking();
        }

        if (rateLimitStore != null) {
            boolean allowed = rateLimitStore.recordAndCheck(rateKey, 60, effectiveLimit);
            if (!allowed) {
                totalBlockedRequests.incrementAndGet();
                totalRateLimitExceeded.incrementAndGet();
                int currentMinuteCount = rateLimitStore.getRequestCount(rateKey, 60);

                // Nếu vượt quá 150% ngưỡng cho phép -> tự động khóa IP lũy tiến (10m, 30m, 60m)
                if (currentMinuteCount > (effectiveLimit * 1.5)) {
                    totalAutoBans.incrementAndGet();
                    int banDuration = calculateProgressiveBanMinutes(clientIp);
                    autoBlockIp(clientIp, "Gửi dồn dập " + currentMinuteCount + " req/phút (ngưỡng: " + effectiveLimit + ")", banDuration);
                    return new CheckResult(CheckStatus.IP_BLOCKED, "Tự động khóa IP do vượt 150% giới hạn tần suất (" + banDuration + " phút)", banDuration * 60);
                }

                return new CheckResult(CheckStatus.RATE_LIMITED, "Vượt quá giới hạn tần suất WAF (" + effectiveLimit + " req/phút)", 60);
            }
        } else {
            ConcurrentLinkedDeque<Long> timestamps = ipRequestTimestamps.computeIfAbsent(rateKey, k -> new ConcurrentLinkedDeque<>());
            timestamps.addLast(now);

            long windowStart = now - 60_000L;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }

            int currentMinuteCount = timestamps.size();
            if (currentMinuteCount > effectiveLimit) {
                totalBlockedRequests.incrementAndGet();
                totalRateLimitExceeded.incrementAndGet();

                if (currentMinuteCount > (effectiveLimit * 1.5)) {
                    totalAutoBans.incrementAndGet();
                    int banDuration = calculateProgressiveBanMinutes(clientIp);
                    autoBlockIp(clientIp, "Gửi dồn dập " + currentMinuteCount + " req/phút (ngưỡng: " + effectiveLimit + ")", banDuration);
                    return new CheckResult(CheckStatus.IP_BLOCKED, "Tự động khóa IP do vượt 150% giới hạn tần suất (" + banDuration + " phút)", banDuration * 60);
                }

                return new CheckResult(CheckStatus.RATE_LIMITED, "Vượt quá giới hạn tần suất WAF (" + effectiveLimit + " req/phút)", 60);
            }
        }

        return new CheckResult(CheckStatus.ALLOWED, "OK", 0);
    }

    /**
     * Phân loại endpoint và xác định tên tier tương ứng (Workstream D).
     */
    public String resolveTierName(String uri) {
        if (uri == null || uri.isBlank()) return "standard";
        String path = uri.toLowerCase();
        if (path.startsWith("/api/auth/")) return "auth";
        if (path.equals("/api/projects/upload") || path.startsWith("/api/projects/upload/")) return "upload";
        if (path.contains("/generate") || path.startsWith("/api/generation/")) return "generation";
        if (path.contains("/export") || path.contains("/download")) return "export";
        if (path.startsWith("/api/admin/")) return "admin";
        return "standard";
    }

    /**
     * Phân loại endpoint và xác định hạn mức requests/phút tương ứng (Workstream D).
     */
    public int resolveTierLimit(String uri) {
        // Chế độ Under Attack siêt chặt toàn bộ endpoint xuống ngưỡng khẩn cáº¥p (30 req/phút)
        if (underAttackMode.get()) {
            return underAttackRateLimit.get();
        }

        if (uri != null && !uri.isBlank()) {
            String path = uri.toLowerCase();
            // Các Tier nhạy cảm (Auth, Upload, Generation, Export, Admin) luôn được bảo vệ theo ngưỡng riêng, kể cả khi Safe Mode bật
            if (path.startsWith("/api/auth/")) {
                return authTierLimit.get();
            }
            if (path.equals("/api/projects/upload") || path.startsWith("/api/projects/upload/")) {
                return uploadTierLimit.get();
            }
            if (path.contains("/generate") || path.startsWith("/api/generation/")) {
                return generationTierLimit.get();
            }
            if (path.contains("/export") || path.contains("/download")) {
                return exportTierLimit.get();
            }
            if (path.startsWith("/api/admin/")) {
                return adminTierLimit.get();
            }
        }

        // Safe Mode chỉ nới trần cho traffic xem/duyệt chung (Standard Tier) lên 500 req/phút
        if (isEmergencySafeMode()) {
            return 500;
        }
        return standardRateLimit.get();
    }

    /**
     * Tính toán thời gian khóa lũy tiến cho các IP vi phạm lặp lại (10m -> 30m -> 60m).
     */
    private int calculateProgressiveBanMinutes(String ip) {
        AtomicInteger existing = ipViolationHistory.get(ip);
        if (existing == null) {
            return 10;
        }
        int violations = existing.get();
        if (violations <= 0) return 10;
        if (violations == 1) return 30;
        return 60;
    }

    /**
     * Dọn dẹp các IP đã ngừng gửi request quá 60 giây để giải phóng bộ nhớ (Bounded Memory).
     */
    public void cleanupIdleIpTracking() {
        long now = System.currentTimeMillis();
        long last = lastCleanupMs.get();
        if (now - last < 5_000L) {
            return;
        }
        if (!lastCleanupMs.compareAndSet(last, now)) {
            return;
        }

        long oneMinuteAgo = now - 60_000L;
        ipRequestTimestamps.entrySet().removeIf(entry -> {
            ConcurrentLinkedDeque<Long> deque = entry.getValue();
            while (!deque.isEmpty() && deque.peekFirst() < oneMinuteAgo) {
                deque.pollFirst();
            }
            return deque.isEmpty();
        });

        long oneSecondAgo = now - 1000L;
        ipBurstTimestamps.entrySet().removeIf(entry -> {
            ConcurrentLinkedDeque<Long> deque = entry.getValue();
            while (!deque.isEmpty() && deque.peekFirst() < oneSecondAgo) {
                deque.pollFirst();
            }
            return deque.isEmpty();
        });

        if (ipRequestTimestamps.size() > MAX_TRACKED_IPS) {
            Iterator<String> it = ipRequestTimestamps.keySet().iterator();
            int toRemove = ipRequestTimestamps.size() - MAX_TRACKED_IPS;
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }

        if (ipBurstTimestamps.size() > MAX_TRACKED_IPS) {
            Iterator<String> it = ipBurstTimestamps.keySet().iterator();
            int toRemove = ipBurstTimestamps.size() - MAX_TRACKED_IPS;
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }
    }

    /**
     * Tự động đưa IP vào danh sách chặn khi phát hiện vi phạm
     */
    private void autoBlockIp(String ip, String reason, int durationMinutes) {
        if (ip == null || ip.isBlank()) return;
        String canonicalIp = toCanonicalIp(ip);
        if (exemptDockerSubnets.get() && isLoopbackOrDockerIp(canonicalIp)) return;

        ipViolationHistory.computeIfAbsent(canonicalIp, k -> new AtomicInteger(0)).incrementAndGet();
        long now = System.currentTimeMillis();
        long expiry = now + (durationMinutes * 60_000L);
        if (rateLimitStore != null) {
            rateLimitStore.block(canonicalIp, reason, durationMinutes * 60L);
        }
        blockedIps.compute(canonicalIp, (k, existing) -> {
            int violations = (existing != null) ? existing.violationCount.get() + 1 : 1;
            return new BlockedRecord(canonicalIp, reason, now, expiry, violations);
        });
    }

    /**
     * Mở khóa toàn bộ IP bị chặn hiện tại (dùng để khôi phục nhanh quyền truy cập cho hệ thống)
     */
    public void unblockAll() {
        blockedIps.clear();
        ipRequestTimestamps.clear();
        ipBurstTimestamps.clear();
        if (rateLimitStore != null) {
            rateLimitStore.clear();
        }
    }

    /**
     * Admin chặn IP thủ công kèm cơ chế chống tự khóa (Anti-self-lockout)
     */
    public void blockIp(String ip, String reason, int durationMinutes, String adminIp) {
        if (ip == null || ip.isBlank()) return;
        String canonicalIp = toCanonicalIp(ip);

        // Chống tự khóa Admin (Workstream G): Không cho phép admin chặn địa chỉ IP hiện tại của chính mình
        if (adminIp != null && !adminIp.isBlank()) {
            String canonicalAdminIp = toCanonicalIp(adminIp);
            if (canonicalIp.equals(canonicalAdminIp)) {
                throw new IllegalArgumentException("Không thể tự chặn địa chỉ IP của chính mình (Anti-self-lockout)");
            }
        }

        long now = System.currentTimeMillis();
        long expiry = durationMinutes > 0 ? now + (durationMinutes * 60_000L) : now + (24 * 3600_000L);
        if (rateLimitStore != null) {
            rateLimitStore.block(canonicalIp, reason, (durationMinutes > 0 ? durationMinutes : 24 * 60) * 60L);
        }
        blockedIps.put(canonicalIp, new BlockedRecord(canonicalIp, reason, now, expiry, 1));
    }

    public void blockIp(String ip, String reason, int durationMinutes) {
        blockIp(ip, reason, durationMinutes, null);
    }

    /**
     * Admin bỏ chặn IP
     */
    public boolean unblockIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        String canonicalIp = toCanonicalIp(ip);
        ipRequestTimestamps.remove(canonicalIp);
        ipRequestTimestamps.entrySet().removeIf(e -> e.getKey().startsWith(canonicalIp + ":"));
        ipBurstTimestamps.remove(canonicalIp);
        ipViolationHistory.remove(canonicalIp);
        boolean storeRemoved = false;
        if (rateLimitStore != null) {
            storeRemoved = rateLimitStore.unblock(canonicalIp);
        }
        boolean localRemoved = blockedIps.remove(canonicalIp) != null;
        return localRemoved || storeRemoved;
    }

    public boolean isIpBlocked(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return false;
        String canonicalIp = toCanonicalIp(clientIp);
        if (rateLimitStore != null) {
            return rateLimitStore.isBlocked(canonicalIp);
        }
        BlockedRecord b = blockedIps.get(canonicalIp);
        return b != null && System.currentTimeMillis() < b.expiresAt;
    }

    public void setUnderAttackMode(boolean enabled) {
        underAttackMode.set(enabled);
    }

    public void setEmergencySafeMode(boolean enabled) {
        setEmergencySafeMode(enabled, 60);
    }

    public void setEmergencySafeMode(boolean enabled, int durationMinutes) {
        emergencySafeMode.set(enabled);
        if (enabled) {
            if (durationMinutes > 0) {
                emergencySafeModeExpiresAt.set(System.currentTimeMillis() + (durationMinutes * 60_000L));
            } else {
                // Thời gian không hợp lệ hoặc <= 0 coi như hết hạn ngay
                emergencySafeModeExpiresAt.set(System.currentTimeMillis() - 1000L);
            }
        } else {
            emergencySafeModeExpiresAt.set(0);
        }
    }

    public boolean isEmergencySafeMode() {
        if (!emergencySafeMode.get()) return false;
        long expiry = emergencySafeModeExpiresAt.get();
        if (expiry > 0 && System.currentTimeMillis() >= expiry) {
            emergencySafeMode.set(false);
            return false;
        }
        return true;
    }

    public void setWafActive(boolean active) {
        wafActive.set(active);
    }

    public void setExemptDockerSubnets(boolean exempt) {
        exemptDockerSubnets.set(exempt);
    }

    public boolean isExemptDockerSubnets() {
        return exemptDockerSubnets.get();
    }

    public void setStandardRateLimit(int limit) {
        if (limit > 0) standardRateLimit.set(limit);
    }

    public void setUnderAttackRateLimit(int limit) {
        if (limit > 0) underAttackRateLimit.set(limit);
    }

    public void setBurstPerSecondLimit(int limit) {
        if (limit > 0) burstPerSecondLimit.set(limit);
    }

    public void setAuthTierLimit(int limit) {
        if (limit > 0) authTierLimit.set(limit);
    }

    public void setUploadTierLimit(int limit) {
        if (limit > 0) uploadTierLimit.set(limit);
    }

    public void setGenerationTierLimit(int limit) {
        if (limit > 0) generationTierLimit.set(limit);
    }

    public void setExportTierLimit(int limit) {
        if (limit > 0) exportTierLimit.set(limit);
    }

    public void setAdminTierLimit(int limit) {
        if (limit > 0) adminTierLimit.set(limit);
    }

    public int getAuthTierLimit() {
        return authTierLimit.get();
    }

    public int getUploadTierLimit() {
        return uploadTierLimit.get();
    }

    public int getGenerationTierLimit() {
        return generationTierLimit.get();
    }

    public int getExportTierLimit() {
        return exportTierLimit.get();
    }

    public int getAdminTierLimit() {
        return adminTierLimit.get();
    }

    public long getTotalBurstAttacks() {
        return totalBurstAttacks.get();
    }

    public long getTotalRateLimitExceeded() {
        return totalRateLimitExceeded.get();
    }

    public long getTotalAutoBans() {
        return totalAutoBans.get();
    }

    /**
     * Lấy trạng thái WAF và danh sách IP bị chặn
     */
    public WafStatusDto getWafStatus() {
        cleanupIdleIpTracking();
        long now = System.currentTimeMillis();
        List<BlockedIpDto> list = new ArrayList<>();

        blockedIps.forEach((ip, record) -> {
            if (now < record.expiresAt) {
                list.add(new BlockedIpDto(
                        record.ip,
                        record.reason,
                        Instant.ofEpochMilli(record.blockedAt),
                        Instant.ofEpochMilli(record.expiresAt),
                        record.violationCount.get()
                ));
            }
        });

        boolean safeModeActive = isEmergencySafeMode();
        int effectiveLimit = underAttackMode.get() ? underAttackRateLimit.get() : (safeModeActive ? 500 : standardRateLimit.get());

        return new WafStatusDto(
                wafActive.get(),
                underAttackMode.get(),
                safeModeActive,
                effectiveLimit,
                standardRateLimit.get(),
                underAttackRateLimit.get(),
                burstPerSecondLimit.get(),
                totalBlockedRequests.get(),
                list.size(),
                list
        );
    }
}
