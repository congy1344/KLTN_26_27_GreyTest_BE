package com.greytest.security.waf;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.exceptions.JedisException;

/**
 * Triển khai RateLimitStore phân tán thực tế qua Redis (Workstream F - Distributed Rate Limiting).
 * Đặc điểm:
 * 1. Đảm bảo tính nguyên tử (Atomicity) bằng Redis Lua Script thực thi trên server Redis.
 *    Hai hay nhiều backend instance cùng dùng chung Redis sẽ đếm chung một bộ đếm atomic.
 * 2. Shared Blacklist: Khóa một IP tại instance A sẽ lập tức có hiệu lực tại instance B qua Redis TTL.
 * 3. Graceful Memory Fallback (Fail-Safe): Khi Redis chưa bật (mặc định single-node), hoặc gặp sự cố mạng (outage),
 *    hệ thống tự động chuyển sang InMemoryRateLimitStore mà không ném lỗi 500 hay làm gián đoạn request.
 * 4. Tự động phục hồi (Auto-Reconnect): Khi Redis hoạt động trở lại, hệ thống tự động quay lại chế độ distributed.
 */
@Component
@Primary
public class RedisRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);

    // Lua Script thực hiện kiểm tra và ghi nhận sliding window nguyên tử trên Redis
    private static final String SLIDING_WINDOW_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            local clear_before = now - window_ms

            -- 1. Xóa các request cũ ngoài cửa sổ
            redis.call('ZREMRANGEBYSCORE', key, '-inf', clear_before)

            -- 2. Đếm số request hiện tại trong cửa sổ
            local count = redis.call('ZCARD', key)

            -- Cho phép ghi nhận thêm quá hạn mức (lên tới 200% limit + 10) để WAF đo lường tấn công vượt ngưỡng 150% và auto-ban
            local max_recorded = math.ceil(limit * 2) + 10
            if count < max_recorded then
                redis.call('ZADD', key, now, member)
                redis.call('EXPIRE', key, math.ceil(window_ms / 1000) + 1)
            end

            if count < limit then
                return 1
            else
                return 0
            end
            """;

    private final InMemoryRateLimitStore memoryFallback;
    private final boolean redisEnabled;
    private final JedisPool jedisPool;
    private final java.util.concurrent.atomic.AtomicBoolean redisDegradedAlertLogged = new java.util.concurrent.atomic.AtomicBoolean(false);

    public RedisRateLimitStore() {
        this(new InMemoryRateLimitStore(), false, "localhost", 6379, 2000);
    }

    public RedisRateLimitStore(InMemoryRateLimitStore memoryFallback) {
        this(memoryFallback, false, "localhost", 6379, 2000, null);
    }

    public RedisRateLimitStore(InMemoryRateLimitStore memoryFallback, boolean redisEnabled, String host, int port, int timeout) {
        this(memoryFallback, redisEnabled, host, port, timeout, null);
    }

    @Autowired
    public RedisRateLimitStore(
            @Autowired(required = false) InMemoryRateLimitStore memoryFallback,
            @Value("${greytest.waf.redis.enabled:false}") boolean redisEnabled,
            @Value("${greytest.waf.redis.host:localhost}") String host,
            @Value("${greytest.waf.redis.port:6379}") int port,
            @Value("${greytest.waf.redis.timeout:2000}") int timeout,
            @Value("${greytest.waf.redis.password:}") String password) {
        this.memoryFallback = memoryFallback != null ? memoryFallback : new InMemoryRateLimitStore();
        this.redisEnabled = redisEnabled;

        if (redisEnabled) {
            JedisPool pool = null;
            try {
                JedisPoolConfig config = new JedisPoolConfig();
                config.setMaxTotal(32);
                config.setMaxIdle(16);
                config.setMinIdle(4);
                config.setTestOnBorrow(true);
                config.setMaxWait(Duration.ofMillis(500));
                if (password != null && !password.isBlank()) {
                    pool = new JedisPool(config, host, port, timeout, password);
                } else {
                    pool = new JedisPool(config, host, port, timeout);
                }
                try (Jedis jedis = pool.getResource()) {
                    jedis.ping();
                }
                log.info("RedisRateLimitStore: Kết nối thành công tới Redis {}:{} (Chế độ Distributed)", host, port);
            } catch (Exception ex) {
                log.warn("RedisRateLimitStore: Không thể kết nối tới Redis {}:{}. Chuyển sang fallback bộ nhớ: {}", host, port, ex.getMessage());
            }
            this.jedisPool = pool;
        } else {
            this.jedisPool = null;
            log.info("RedisRateLimitStore: Chế độ Single-Node (Redis disabled), sử dụng InMemoryRateLimitStore.");
        }
    }

    public boolean isRedisActive() {
        if (!redisEnabled || jedisPool == null) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isRedisAvailable() {
        return isRedisActive();
    }

    @Override
    public boolean recordAndCheck(String key, int windowSeconds, int maxRequests) {
        if (!redisEnabled || jedisPool == null) {
            return memoryFallback.recordAndCheck(key, windowSeconds, maxRequests);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String redisKey = "waf:rate:" + key;
            long now = System.currentTimeMillis();
            long windowMs = windowSeconds * 1000L;
            String member = now + ":" + java.util.UUID.randomUUID();

            Object result = jedis.eval(
                    SLIDING_WINDOW_LUA,
                    Collections.singletonList(redisKey),
                    List.of(String.valueOf(now), String.valueOf(windowMs), String.valueOf(maxRequests), member)
            );

            if (redisDegradedAlertLogged.compareAndSet(true, false)) {
                log.info("RedisRateLimitStore: Kết nối Redis đã phục hồi thành công, tự động quay trở lại chế độ Distributed Rate Limiting.");
            }
            return Long.valueOf(1L).equals(result);
        } catch (JedisException ex) {
            if (redisDegradedAlertLogged.compareAndSet(false, true)) {
                log.error("SECURITY ALERT: Redis RateLimitStore gặp sự cố kết nối ({}), kích hoạt Fail-Safe In-Memory Fallback trên instance.", ex.getMessage());
            } else {
                log.warn("Lỗi thao tác Redis. Tiếp tục duy trì fallback In-Memory: {}", ex.getMessage());
            }
            return memoryFallback.recordAndCheck(key, windowSeconds, maxRequests);
        }
    }

    @Override
    public int getRequestCount(String key, int windowSeconds) {
        if (!redisEnabled || jedisPool == null) {
            return memoryFallback.getRequestCount(key, windowSeconds);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String redisKey = "waf:rate:" + key;
            long now = System.currentTimeMillis();
            long windowStart = now - (windowSeconds * 1000L);
            jedis.zremrangeByScore(redisKey, "-inf", String.valueOf(windowStart));
            long count = jedis.zcount(redisKey, String.valueOf(windowStart), "+inf");
            return (int) count;
        } catch (JedisException ex) {
            return memoryFallback.getRequestCount(key, windowSeconds);
        }
    }

    @Override
    public boolean isBlocked(String key) {
        if (!redisEnabled || jedisPool == null) {
            return memoryFallback.isBlocked(key);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists("waf:block:" + key);
        } catch (JedisException ex) {
            return memoryFallback.isBlocked(key);
        }
    }

    @Override
    public void block(String key, String reason, long durationSeconds) {
        if (!redisEnabled || jedisPool == null) {
            memoryFallback.block(key, reason, durationSeconds);
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String val = reason != null ? reason : "Blocked";
            jedis.setex("waf:block:" + key, (int) Math.max(1, durationSeconds), val);
        } catch (JedisException ex) {
            memoryFallback.block(key, reason, durationSeconds);
        }
    }

    @Override
    public boolean unblock(String key) {
        if (!redisEnabled || jedisPool == null) {
            return memoryFallback.unblock(key);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("waf:rate:" + key);
            for (String tier : List.of("standard", "auth", "upload", "generation", "export", "admin")) {
                jedis.del("waf:rate:" + key + ":" + tier);
            }
            return jedis.del("waf:block:" + key) > 0;
        } catch (JedisException ex) {
            return memoryFallback.unblock(key);
        }
    }

    @Override
    public void clear() {
        memoryFallback.clear();
        if (!redisEnabled || jedisPool == null) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("waf:*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, params);
                List<String> keys = scanResult.getResult();
                if (keys != null && !keys.isEmpty()) {
                    jedis.del(keys.toArray(new String[0]));
                }
                cursor = scanResult.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        } catch (JedisException ex) {
            log.warn("Không thể xóa sạch dữ liệu Redis: {}", ex.getMessage());
        }
    }

    @Override
    public String getBlockReason(String key) {
        if (!redisEnabled || jedisPool == null) {
            return memoryFallback.getBlockReason(key);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get("waf:block:" + key);
        } catch (JedisException ex) {
            return memoryFallback.getBlockReason(key);
        }
    }

    @Override
    public int getRemainingBlockSeconds(String key) {
        if (!redisEnabled || jedisPool == null) {
            return memoryFallback.getRemainingBlockSeconds(key);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            long ttl = jedis.ttl("waf:block:" + key);
            return (int) Math.max(0, ttl);
        } catch (JedisException ex) {
            return memoryFallback.getRemainingBlockSeconds(key);
        }
    }

    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }
}
