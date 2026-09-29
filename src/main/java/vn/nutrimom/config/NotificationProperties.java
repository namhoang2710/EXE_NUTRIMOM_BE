package vn.nutrimom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình module notification dưới prefix {@code app.notification}.
 *
 * <pre>
 * app:
 *   notification:
 *     push-token-key: ${NUTRIMOM_PUSH_TOKEN_KEY}   # base64 của 32 byte ngẫu nhiên (AES-256)
 *     push-enabled: true
 * </pre>
 *
 * <p>{@code pushTokenKey} bắt buộc phải có vì spec mục 18 yêu cầu push token "được mã hóa" khi lưu.
 * Sinh key mới: {@code openssl rand -base64 32}.</p>
 *
 * <p>{@code pushEnabled=false} tắt việc gửi push nhưng vẫn ghi thông báo vào hộp in-app — dùng khi
 * chạy local mà không muốn đụng tới gateway push.</p>
 */
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {

    private String pushTokenKey;
    private boolean pushEnabled = true;

    public String getPushTokenKey() { return pushTokenKey; }
    public void setPushTokenKey(String pushTokenKey) { this.pushTokenKey = pushTokenKey; }
    public boolean isPushEnabled() { return pushEnabled; }
    public void setPushEnabled(boolean pushEnabled) { this.pushEnabled = pushEnabled; }
}
