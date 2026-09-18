package vn.nutrimom.common.ratelimit;

import java.time.Duration;

/**
 * Một chính sách giới hạn tần suất: cho phép tối đa {@code capacity} lượt trong khoảng {@code window}.
 *
 * <p>Tên chính sách chính là key trong {@code app.security.rate-limit.policies}; record này chỉ mang
 * hai tham số cấu hình để {@link RateLimiterStore} tính token bucket.</p>
 */
public record RateLimitPolicy(int capacity, Duration window) {
    public RateLimitPolicy {
        if (capacity <= 0) {
            throw new IllegalArgumentException("rate-limit capacity phải > 0");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("rate-limit window phải > 0");
        }
    }
}
