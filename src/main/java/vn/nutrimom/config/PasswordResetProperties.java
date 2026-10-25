package vn.nutrimom.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình luồng quên mật khẩu, prefix {@code app.auth.password-reset}. */
@ConfigurationProperties(prefix = "app.auth.password-reset")
public class PasswordResetProperties {

    /** Thời gian sống của link đặt lại mật khẩu. */
    private Duration ttl = Duration.ofMinutes(30);
    /** Khoảng cách tối thiểu giữa hai thư gửi cho cùng một tài khoản. */
    private Duration cooldown = Duration.ofSeconds(60);
    /** Số thư tối đa trong một giờ cho cùng một tài khoản. */
    private int maxPerHour = 5;

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public Duration getCooldown() { return cooldown; }
    public void setCooldown(Duration cooldown) { this.cooldown = cooldown; }
    public int getMaxPerHour() { return maxPerHour; }
    public void setMaxPerHour(int maxPerHour) { this.maxPerHour = maxPerHour; }
}
