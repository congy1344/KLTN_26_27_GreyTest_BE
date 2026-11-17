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

import org.springframework.stereotype.Service;

import com.greytest.dto.admin.AdminDtos.BlockedIpDto;
import com.greytest.dto.admin.AdminDtos.WafStatusDto;

/**
 * Dịch vụ bảo vệ chống tấn công từ chối dịch vụ (DDoS) và ngăn chặn Spam tầng ứng dụng (WAF Layer 7).
 * Cung cấp:
 * 1. Giới hạn tần suất request theo IP (Rate Limiting).
 * 2. Tự động phát hiện và đưa vào danh sách đen (Auto IP Blacklist) khi phát hiện dấu hiệu tấn công.
 * 3. Chế độ phòng thủ nghiêm ngặt khẩn cấp (Under Attack Mode).
 * 4. Quản lý danh sách IP bị chặn theo thời gian thực cho Admin.
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

    private static class BlockedRecord {
        final String ip;
        final String reason;
        final long blockedAt;
        final long expiresAt;
        final AtomicInteger violationCount;

        BlockedRecord(String ip, String reason, long blockedAt, long expiresAt, int initialViolations) {
            this.ip = ip;
            this.reason = reason;
            this.blockedAt = blockedAt;
            this.expiresAt = expiresAt;
            this.violationCount = new AtomicInteger(initialViolations);
        }
    }

    // Cấu hình WAF & DDoS
    private final AtomicBoolean wafActive = new AtomicBoolean(true);
    private final AtomicBoolean underAttackMode = new AtomicBoolean(false);
    private final AtomicInteger standardRateLimit = new AtomicInteger(100); // 100 req/phút/IP
    private final AtomicInteger underAttackRateLimit = new AtomicInteger(30); // 30 req/phút khi kích hoạt Under Attack
    private final AtomicInteger burstPerSecondLimit = new AtomicInteger(200); // >200 req/giây coi là tấn công dồn dập (thay vì 15)

    private final AtomicLong totalBlockedRequests = new AtomicLong(0);

    // Lưu trữ dấu thời gian các request gần nhất theo từng client IP
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> ipRequestTimestamps = new ConcurrentHashMap<>();

    // Danh sách IP bị chặn / cách ly
    private final ConcurrentHashMap<String, BlockedRecord> blockedIps = new ConcurrentHashMap<>();

    /**
     * Chuẩn hóa và kiểm tra tính hợp lệ của địa chỉ IP Client.
     * Xử lý trường hợp danh sách nhiều IP (X-Forwarded-For), IPv6 có ngoặc vuông, IPv4 kèm port,
     * và từ chối chuỗi header không hợp lệ để đảm bảo nhất quán giữa Filter và WAF.
     */
    public static String normalizeClientIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "127.0.0.1";
        }
        String candidate = rawIp.trim();
        int commaIdx = candidate.indexOf(',');
        if (commaIdx >= 0) {
            candidate = candidate.substring(0, commaIdx).trim();
        }
        if (candidate.startsWith("[")) {
            int closeBracket = candidate.indexOf(']');
            if (closeBracket > 1) {
                candidate = candidate.substring(1, closeBracket);
            } else {
                return "127.0.0.1";
            }
        } else if (candidate.indexOf(':') > 0 && candidate.indexOf(':') == candidate.lastIndexOf(':')) {
            candidate = candidate.substring(0, candidate.indexOf(':')).trim();
        }
        if (candidate.isEmpty() || candidate.length() > 45) {
            return "127.0.0.1";
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean valid = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F')
                    || c == '.'
                    || c == ':';
            if (!valid) {
                return "127.0.0.1";
            }
        }
        return candidate;
    }

    /**
     * Kiểm tra địa chỉ IP Client có thuộc localhost, loopback hoặc mạng nội bộ Docker (172.16.0.0/12) không.
     * Môi trường phát triển và người dùng cục bộ không bao giờ bị khóa do DDoS.
     */
    public static boolean isLoopbackOrDockerIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        String candidate = ip.trim();
        if (candidate.startsWith("::ffff:")) {
            candidate = candidate.substring(7);
        }
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

    /**
     * Kiểm tra nhanh một request HTTP từ Client IP.
     * Trả về kết quả cho phép đi tiếp hay chặn bằng HTTP 429.
     */
    public CheckResult checkRequest(String clientIp, String uri) {
        if (!wafActive.get() || clientIp == null || clientIp.isBlank()) {
            return new CheckResult(CheckStatus.ALLOWED, "WAF Disabled", 0);
        }
        clientIp = normalizeClientIp(clientIp);

        // Miễn trừ tuyệt đối cho localhost và mạng Docker phát triển (tự động xóa khỏi danh sách chặn nếu đã lưu trước đó)
        if (isLoopbackOrDockerIp(clientIp)) {
            blockedIps.remove(clientIp);
            return new CheckResult(CheckStatus.ALLOWED, "Local/Docker IP exempt", 0);
        }

        long now = System.currentTimeMillis();

        // 1. Kiểm tra IP có trong danh sách đen (Blacklist) hay không (Admin thủ công chặn)
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

        // Miễn trừ riêng cho endpoint đăng nhập quản trị viên để tránh khóa cổng phục hồi của Admin
        if (uri != null && uri.equals("/api/auth/admin/login")) {
            return new CheckResult(CheckStatus.ALLOWED, "Admin login exempt", 0);
        }

        // 2. Cửa sổ trượt 60 giây đo lường tần suất request của IP này
        ConcurrentLinkedDeque<Long> timestamps = ipRequestTimestamps.computeIfAbsent(clientIp, k -> new ConcurrentLinkedDeque<>());
        timestamps.addLast(now);

        long windowStart = now - 60_000;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }

        int currentMinuteCount = timestamps.size();

        // Đo lường burst trong 1 giây gần nhất
        long oneSecondAgo = now - 1000;
        int burstCount = 0;
        Iterator<Long> descIt = timestamps.descendingIterator();
        while (descIt.hasNext()) {
            if (descIt.next() >= oneSecondAgo) {
                burstCount++;
            } else {
                break;
            }
        }

        int maxAllowed = underAttackMode.get() ? underAttackRateLimit.get() : standardRateLimit.get();

        // 3. Phát hiện tấn công dồn dập (Burst Attack / Spike)
        if (burstCount > burstPerSecondLimit.get()) {
            // Tự động ban IP 15 phút vì có dấu hiệu công cụ DDoS/Spam script
            autoBlockIp(clientIp, "Tần suất đột biến vượt ngưỡng (" + burstCount + " req/giây)", 15);
            totalBlockedRequests.incrementAndGet();
            return new CheckResult(CheckStatus.IP_BLOCKED, "Phát hiện tần suất đột biến DDoS (" + burstCount + " req/s)", 900);
        }

        // 4. Kiểm tra giới hạn Rate Limit theo phút
        if (currentMinuteCount > maxAllowed) {
            totalBlockedRequests.incrementAndGet();

            // Nếu vượt quá 150% ngưỡng cho phép -> tự động khóa IP 10 phút
            if (currentMinuteCount > (maxAllowed * 1.5)) {
                autoBlockIp(clientIp, "Gửi dồn dập " + currentMinuteCount + " req/phút (ngưỡng: " + maxAllowed + ")", 10);
                return new CheckResult(CheckStatus.IP_BLOCKED, "Tự động khóa IP do vượt 150% giới hạn tần suất", 600);
            }

            return new CheckResult(CheckStatus.RATE_LIMITED, "Vượt quá giới hạn tần suất WAF (" + maxAllowed + " req/phút)", 60);
        }

        return new CheckResult(CheckStatus.ALLOWED, "OK", 0);
    }

    /**
     * Dọn dẹp các IP đã ngừng gửi request quá 60 giây để giải phóng bộ nhớ.
     */
    public void cleanupIdleIpTracking() {
        long oneMinuteAgo = System.currentTimeMillis() - 60_000L;
        ipRequestTimestamps.entrySet().removeIf(entry -> {
            ConcurrentLinkedDeque<Long> deque = entry.getValue();
            while (!deque.isEmpty() && deque.peekFirst() < oneMinuteAgo) {
                deque.pollFirst();
            }
            return deque.isEmpty();
        });
    }

    /**
     * Tự động đưa IP vào danh sách chặn khi phát hiện vi phạm
     */
    private void autoBlockIp(String ip, String reason, int durationMinutes) {
        if (ip == null || ip.isBlank() || isLoopbackOrDockerIp(ip)) return;
        long now = System.currentTimeMillis();
        long expiry = now + (durationMinutes * 60_000L);
        blockedIps.compute(ip, (k, existing) -> {
            int violations = (existing != null) ? existing.violationCount.get() + 1 : 1;
            return new BlockedRecord(ip, reason, now, expiry, violations);
        });
    }

    /**
     * Mở khóa toàn bộ IP bị chặn hiện tại (dùng để khôi phục nhanh quyền truy cập cho hệ thống)
     */
    public void unblockAll() {
        blockedIps.clear();
        ipRequestTimestamps.clear();
    }

    /**
     * Admin chặn IP thủ công
     */
    public void blockIp(String ip, String reason, int durationMinutes) {
        if (ip == null || ip.isBlank()) return;
        String normalizedIp = normalizeClientIp(ip);
        long now = System.currentTimeMillis();
        long expiry = durationMinutes > 0 ? now + (durationMinutes * 60_000L) : now + (24 * 3600_000L);
        blockedIps.put(normalizedIp, new BlockedRecord(normalizedIp, reason, now, expiry, 1));
    }

    /**
     * Admin bỏ chặn IP
     */
    public boolean unblockIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        String normalizedIp = normalizeClientIp(ip);
        ipRequestTimestamps.remove(normalizedIp);
        return blockedIps.remove(normalizedIp) != null;
    }

    /**
     * Bật/tắt chế độ phòng thủ nghiêm ngặt (Under Attack Mode)
     */
    public void setUnderAttackMode(boolean enabled) {
        underAttackMode.set(enabled);
    }

    /**
     * Bật/tắt toàn bộ tường lửa WAF
     */
    public void setWafActive(boolean active) {
        wafActive.set(active);
    }

    /**
     * Điều chỉnh ngưỡng Rate Limit tiêu chuẩn
     */
    public void setStandardRateLimit(int limit) {
        if (limit > 0) {
            standardRateLimit.set(limit);
        }
    }

    /**
     * Điều chỉnh ngưỡng Rate Limit khi ở chế độ Under Attack
     */
    public void setUnderAttackRateLimit(int limit) {
        if (limit > 0) {
            underAttackRateLimit.set(limit);
        }
    }

    /**
     * Điều chỉnh ngưỡng Burst (số request tối đa trên 1 giây trước khi bị khóa IP)
     */
    public void setBurstPerSecondLimit(int limit) {
        if (limit > 0) {
            burstPerSecondLimit.set(limit);
        }
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

        int effectiveLimit = underAttackMode.get() ? underAttackRateLimit.get() : standardRateLimit.get();

        return new WafStatusDto(
                wafActive.get(),
                underAttackMode.get(),
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

