package com.greytest.security.waf;

/**
 * Interface trừu tượng hóa kho lưu trữ dữ liệu Rate Limiting và Blacklist (Workstream F).
 * Cho phép linh hoạt chuyển đổi giữa bộ nhớ cục bộ (InMemoryRateLimitStore) trên Single-Node
 * và bộ nhớ phân tán (RedisRateLimitStore) khi mở rộng cụm nhiều node mà không làm gián đoạn hệ thống.
 */
public interface RateLimitStore {

    /**
     * Ghi nhận và kiểm tra tần suất request theo cửa sổ trượt (sliding window).
     *
     * @param key           Khóa định danh (ví dụ: client IP, user ID, hoặc endpoint)
     * @param windowSeconds Kích thước cửa sổ tính toán (giây)
     * @param maxRequests   Số lượng request tối đa được phép trong cửa sổ
     * @return true nếu request được chấp nhận, false nếu vi phạm giới hạn tần suất (Rate Limited)
     */
    boolean recordAndCheck(String key, int windowSeconds, int maxRequests);

    /**
     * Đếm số lượng request đã ghi nhận của key trong cửa sổ thời gian gần nhất.
     */
    int getRequestCount(String key, int windowSeconds);

    /**
     * Kiểm tra một key (IP/User) có đang bị khóa (Blacklisted) hay không.
     */
    boolean isBlocked(String key);

    /**
     * Đưa một key vào danh sách chặn tạm thời.
     *
     * @param key             Khóa định danh
     * @param reason          Lý do chặn
     * @param durationSeconds Thời gian chặn tính bằng giây
     */
    void block(String key, String reason, long durationSeconds);

    /**
     * Gỡ chặn một key khỏi danh sách đen.
     */
    boolean unblock(String key);

    /**
     * Xóa toàn bộ dữ liệu rate limiting và blacklist (dùng cho khôi phục khẩn cấp).
     */
    void clear();

    /**
     * Lấy lý do chặn hiện tại của key (nếu đang bị chặn).
     */
    String getBlockReason(String key);

    /**
     * Lấy thời gian còn lại (giây) trước khi hết hạn chặn của key.
     */
    int getRemainingBlockSeconds(String key);
}
