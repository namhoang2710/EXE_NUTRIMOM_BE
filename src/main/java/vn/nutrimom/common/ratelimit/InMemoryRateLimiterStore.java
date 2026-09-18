package vn.nutrimom.common.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Token-bucket đếm trong bộ nhớ tiến trình. Đủ dùng cho triển khai một instance (đồ án); khi chạy nhiều
 * instance thì mỗi instance đếm riêng — lúc đó thay bằng một {@link RateLimiterStore} nền Redis.
 *
 * <p>Mỗi khóa có một bucket refill tuyến tính theo thời gian (rate = capacity/window). Bucket nhàn rỗi
 * được dọn lười khi bản đồ phình to để không rò rỉ bộ nhớ.</p>
 */
@Component
public class InMemoryRateLimiterStore implements RateLimiterStore {

    /** Ngưỡng số khóa trước khi kích hoạt một lượt dọn bucket đã đầy lại và quá hạn. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public ConsumeResult tryConsume(String key, RateLimitPolicy policy) {
        sweepIfNeeded(policy);
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(policy.capacity()));
        return bucket.tryConsume(policy);
    }

    private void sweepIfNeeded(RateLimitPolicy policy) {
        if (buckets.size() < SWEEP_THRESHOLD) {
            return;
        }
        long staleBefore = System.nanoTime() - policy.window().toNanos() * 2;
        buckets.forEach((key, bucket) -> {
            if (bucket.isIdleFullBefore(staleBefore, policy.capacity())) {
                buckets.remove(key, bucket);
            }
        });
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized ConsumeResult tryConsume(RateLimitPolicy policy) {
            refill(policy);
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return ConsumeResult.granted();
            }
            double refillPerSecond = refillPerSecond(policy);
            double deficit = 1.0d - tokens;
            long retryAfter = (long) Math.ceil(deficit / refillPerSecond);
            return ConsumeResult.rejected(retryAfter);
        }

        synchronized boolean isIdleFullBefore(long staleBeforeNanos, int capacity) {
            return lastRefillNanos < staleBeforeNanos && tokens >= capacity;
        }

        private void refill(RateLimitPolicy policy) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0d;
            if (elapsedSeconds > 0) {
                tokens = Math.min(policy.capacity(), tokens + elapsedSeconds * refillPerSecond(policy));
                lastRefillNanos = now;
            }
        }

        private static double refillPerSecond(RateLimitPolicy policy) {
            return policy.capacity() / (policy.window().toMillis() / 1000.0d);
        }
    }
}
