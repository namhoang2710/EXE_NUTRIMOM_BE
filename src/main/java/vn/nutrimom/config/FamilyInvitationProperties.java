package vn.nutrimom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình lời mời gia đình, dưới prefix {@code app.family.invitation}. */
@ConfigurationProperties(prefix = "app.family.invitation")
public class FamilyInvitationProperties {

    private int defaultExpiryHours = 48;

    /**
     * Tắt thì backend không gửi email mời, chỉ trả {@code invite_url} cho chủ nhóm tự gửi.
     *
     * <p>Cờ này <strong>chỉ gate kênh email</strong>. Thông báo in-app vẫn được tạo như thường:
     * nó là kênh đến nơi đáng tin nhất (người được mời bắt buộc phải có tài khoản sẵn mới accept
     * được), nên để một cấu hình SMTP tắt làm người ta mất luôn lời mời trong app là sai. Tên cũ
     * {@code send-enabled} gộp cả hai kênh và đã gây đúng sự cố đó.</p>
     */
    private boolean emailEnabled = true;

    public int getDefaultExpiryHours() {
        return defaultExpiryHours;
    }

    public void setDefaultExpiryHours(int defaultExpiryHours) {
        this.defaultExpiryHours = defaultExpiryHours;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }
}
