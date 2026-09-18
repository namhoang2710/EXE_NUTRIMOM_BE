package vn.nutrimom.common.ratelimit;

/**
 * Trừu tượng nơi lưu bộ đếm rate-limit. Mặc định dùng {@link InMemoryRateLimiterStore} (đếm trong RAM);
 * để đó sẵn interface này để sau muốn đổi sang Redis (đếm phân tán) chỉ cần thêm một implementation mới,
 * không phải sửa filter hay service.
 */
public interface RateLimiterStore {

    /**
     * Thử tiêu thụ 1 lượt cho {@code key} theo {@code policy}.
     *
     * @param key   khóa nhận diện chủ thể bị giới hạn (vd: "login:u:&lt;userId&gt;" hoặc "otp:ip:1.2.3.4").
     * @param policy chính sách áp dụng.
     * @return kết quả cho biết được phép hay không, kèm số giây nên chờ nếu bị chặn.
     */
    ConsumeResult tryConsume(String key, RateLimitPolicy policy);

    record ConsumeResult(boolean allowed, long retryAfterSeconds) {
        static ConsumeResult granted() {
            return new ConsumeResult(true, 0);
        }

        static ConsumeResult rejected(long retryAfterSeconds) {
            return new ConsumeResult(false, Math.max(1, retryAfterSeconds));
        }
    }
}
