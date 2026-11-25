package com.greytest.security.waf;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Bộ giải mã và xác thực Reverse Proxy tin cậy (Workstream A - Anti-Spoofing & Trusted Proxy Hardening).
 * 
 * Nguyên tắc bảo mật:
 * 1. Chỉ tin cậy các địa chỉ hoặc dải CIDR được chỉ định tường minh (mặc định loopback 127.0.0.1/32, ::1/128).
 * 2. Loại bỏ toàn bộ dải private CIDR blanket và link-local để ngăn chặn IP spoofing từ các container nội bộ khác.
 * 3. Phân tích chuỗi X-Forwarded-For theo chiều từ PHẢI sang TRÁI (Right-to-Left / RTL), chỉ đi qua các node proxy tin cậy.
 * 4. Header X-Test-Client-IP chỉ được chấp nhận khi cờ allow-test-header bật và kết nối từ trusted proxy/loopback.
 */
@Component
public class TrustedProxyResolver {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxyResolver.class);

    // Mặc định nghiêm ngặt: chỉ tin cậy loopback cục bộ của host
    public static final String DEFAULT_TRUSTED_CIDRS = "127.0.0.1/32,::1/128";

    public record CidrRange(byte[] networkBytes, int prefixLength, boolean isIpv4) {
        public boolean matches(InetAddress address) {
            byte[] addrBytes = address.getAddress();
            if ((addrBytes.length == 4) != isIpv4) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            int remBits = prefixLength % 8;
            if (remBits > 0) {
                int mask = 0xFF << (8 - remBits);
                return (addrBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
            }
            return true;
        }
    }

    private final List<CidrRange> trustedCidrs = new CopyOnWriteArrayList<>();
    private final Set<String> trustedExactIps = new CopyOnWriteArraySet<>();
    private final boolean allowTestHeader;

    public TrustedProxyResolver() {
        this(DEFAULT_TRUSTED_CIDRS, false);
    }

    public TrustedProxyResolver(String cidrConfig) {
        this(cidrConfig, false);
    }

    @Autowired
    public TrustedProxyResolver(
            @Value("${greytest.waf.trusted-proxies:" + DEFAULT_TRUSTED_CIDRS + "}") String cidrConfig,
            @Value("${greytest.waf.allow-test-header:false}") boolean allowTestHeader) {
        this.allowTestHeader = allowTestHeader;
        loadCidrs(cidrConfig);
    }

    public boolean isAllowTestHeader() {
        return allowTestHeader;
    }

    public synchronized void loadCidrs(String cidrConfig) {
        trustedCidrs.clear();
        trustedExactIps.clear();
        if (cidrConfig == null || cidrConfig.isBlank()) {
            return;
        }

        String[] parts = cidrConfig.split(",");
        for (String raw : parts) {
            String token = raw.trim();
            if (token.isEmpty()) continue;

            try {
                if (token.contains("/")) {
                    String[] cidrParts = token.split("/");
                    String ipPart = cidrParts[0].trim();
                    int prefix = Integer.parseInt(cidrParts[1].trim());

                    if (isValidIpLiteral(ipPart)) {
                        InetAddress inet = InetAddress.getByName(ipPart);
                        boolean isIpv4 = inet.getAddress().length == 4;
                        int maxPrefix = isIpv4 ? 32 : 128;
                        if (prefix >= 0 && prefix <= maxPrefix) {
                            trustedCidrs.add(new CidrRange(inet.getAddress(), prefix, isIpv4));
                        }
                    }
                } else {
                    String canonical = canonicalizeIp(extractIpCandidate(token));
                    if (canonical != null) {
                        trustedExactIps.add(canonical);
                    }
                }
            } catch (Exception ex) {
                log.warn("Bỏ qua định dạng CIDR không hợp lệ trong cấu hình WAF: {}", token);
            }
        }
    }

    /**
     * Kiểm tra một địa chỉ IP có phải là Trusted Proxy hay không.
     */
    public boolean isTrustedProxy(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return false;
        }
        String clean = extractIpCandidate(rawIp);
        String canonical = canonicalizeIp(clean);
        if (canonical == null) {
            return false; // Chuỗi không phải IP literal hợp lệ thì không bao giờ là trusted proxy
        }
        if (trustedExactIps.contains(canonical)) {
            return true;
        }

        try {
            InetAddress address = InetAddress.getByName(canonical);
            for (CidrRange cidr : trustedCidrs) {
                if (cidr.matches(address)) {
                    return true;
                }
            }
        } catch (UnknownHostException ignored) {
        }
        return false;
    }

    /**
     * Xác định Client IP thật của HTTP Request với cơ chế chống giả mạo IP và Right-to-Left parsing.
     */
    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }

        String remoteRaw = extractIpCandidate(request.getRemoteAddr());
        String remoteAddr = canonicalizeIp(remoteRaw);
        if (remoteAddr == null) {
            remoteAddr = "127.0.0.1";
        }

        // Nếu kết nối trực tiếp không thuộc Trusted Proxy -> ĐÂY LÀ CLIENT THẬT, không tin bất kỳ header forwarded nào
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        // Với Trusted Proxy (hoặc localhost kiểm thử), cho phép đọc header kiểm thử chuyên dụng nếu cờ allowTestHeader bật
        if (allowTestHeader) {
            String testIp = request.getHeader("X-Test-Client-IP");
            if (testIp != null && !testIp.isBlank()) {
                String candidate = extractIpCandidate(testIp);
                String canonical = canonicalizeIp(candidate);
                if (canonical != null) {
                    return canonical;
                }
            }
        }

        // Đọc chuỗi X-Forwarded-For
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String resolved = parseRightToLeft(xff);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }

        // Đọc X-Real-IP nếu X-Forwarded-For không có
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            String candidate = extractIpCandidate(xRealIp);
            String canonical = canonicalizeIp(candidate);
            if (canonical != null) {
                return canonical;
            }
        }

        return remoteAddr;
    }

    /**
     * Phân tích chuỗi proxy theo chiều từ PHẢI sang TRÁI (Right-to-Left):
     * Duyệt từ proxy gần nhất quay ngược lại, lấy địa chỉ đầu tiên KHÔNG nằm trong danh sách Trusted Proxy.
     */
    public String parseRightToLeft(String xffChain) {
        if (xffChain == null || xffChain.isBlank()) {
            return null;
        }
        String[] hops = xffChain.split(",");
        // Duyệt từ hop cuối cùng (gần proxy biên nhất) về đầu chuỗi
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = extractIpCandidate(hops[i]);
            String canonical = canonicalizeIp(candidate);
            if (canonical == null) {
                // Hop không hợp lệ -> dừng duyệt RTL để tránh bị bypass về hop bên trái
                return null;
            }
            if (!isTrustedProxy(canonical)) {
                return canonical;
            }
        }
        // Nếu tất cả các node trong chuỗi đều là trusted proxy, lấy hop ngoài cùng bên trái (nguồn gốc ban đầu)
        String firstHop = extractIpCandidate(hops[0]);
        String canonicalFirst = canonicalizeIp(firstHop);
        return canonicalFirst != null ? canonicalFirst : null;
    }

    /**
     * Tách IP candidate từ chuỗi thô (loại bỏ cổng, dấu ngoặc vuông IPv6, v.v.).
     */
    public static String extractIpCandidate(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "";
        }
        String candidate = rawIp.trim();
        int commaIdx = candidate.indexOf(',');
        if (commaIdx >= 0) {
            candidate = candidate.substring(0, commaIdx).trim();
        }

        // Chuẩn hóa IPv4-mapped IPv6: ::ffff:192.0.2.128 -> 192.0.2.128
        if (candidate.startsWith("::ffff:") || candidate.startsWith("::FFFF:")) {
            candidate = candidate.substring(7).trim();
        }

        // Xử lý IPv6 có ngoặc vuông [2001:db8::1]:443
        if (candidate.startsWith("[")) {
            int closeBracket = candidate.indexOf(']');
            if (closeBracket > 1) {
                candidate = candidate.substring(1, closeBracket);
            }
        } else if (candidate.indexOf(':') > 0 && candidate.indexOf(':') == candidate.lastIndexOf(':')) {
            // IPv4 kèm port: 1.2.3.4:8080
            candidate = candidate.substring(0, candidate.indexOf(':')).trim();
        }

        return candidate.trim();
    }

    /**
     * Chuẩn hóa và chuyển đổi IP literal sang định dạng chuẩn (RFC 5952 cho IPv6).
     * Trả về null nếu chuỗi không phải IP literal hợp lệ (không bao giờ thực hiện DNS lookup).
     */
    public static String canonicalizeIp(String ipStr) {
        if (ipStr == null || ipStr.isBlank()) {
            return null;
        }
        String s = ipStr.trim();
        if (isValidIpv4Literal(s)) {
            return s;
        }
        if (isValidIpv6Literal(s)) {
            try {
                InetAddress addr = InetAddress.getByName(s);
                return addr.getHostAddress();
            } catch (UnknownHostException ignored) {
            }
        }
        return null;
    }

    public static boolean isValidIpv4Literal(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            if (p.length() > 1 && p.startsWith("0")) return false;
            try {
                int val = Integer.parseInt(p);
                if (val < 0 || val > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidIpv6Literal(String s) {
        if (s == null || s.length() < 2 || s.length() > 45) return false;
        if (!s.contains(":")) return false;
        int doubleColon = s.indexOf("::");
        if (doubleColon >= 0 && s.indexOf("::", doubleColon + 1) >= 0) {
            return false;
        }
        String[] segments = s.split(":", -1);
        if (doubleColon < 0 && segments.length != 8) return false;
        if (doubleColon >= 0 && segments.length > 8) return false;
        for (String seg : segments) {
            if (seg.isEmpty()) continue;
            if (seg.length() > 4) return false;
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isValidIpLiteral(String s) {
        return isValidIpv4Literal(s) || isValidIpv6Literal(s);
    }

    public static boolean isValidIpFormat(String candidate) {
        return isValidIpLiteral(candidate);
    }

    /**
     * Chuẩn hóa địa chỉ IP (dành cho legacy compatibility).
     */
    public static String normalizeIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "127.0.0.1";
        }
        String candidate = extractIpCandidate(rawIp);
        if (isValidIpLiteral(candidate)) {
            return candidate;
        }
        String canonical = canonicalizeIp(candidate);
        return canonical != null ? canonical : "127.0.0.1";
    }
}
