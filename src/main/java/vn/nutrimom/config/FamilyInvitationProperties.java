package vn.nutrimom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình lời mời gia đình, dưới prefix {@code app.family.invitation}. */
@ConfigurationProperties(prefix = "app.family.invitation")
public class FamilyInvitationProperties {

    private int defaultExpiryHours = 48;

    /**
     * Tắt thì backend không gửi email và không tạo thông báo, chỉ trả {@code invite_url} cho chủ
     * nhóm tự gửi. Bộ test bật cờ này về false để khỏi in cả khối HTML vào log mỗi lần tạo lời mời.
     */
    private boolean sendEnabled = true;

    public int getDefaultExpiryHours() {
        return defaultExpiryHours;
    }

    public void setDefaultExpiryHours(int defaultExpiryHours) {
        this.defaultExpiryHours = defaultExpiryHours;
    }

    public boolean isSendEnabled() {
        return sendEnabled;
    }

    public void setSendEnabled(boolean sendEnabled) {
        this.sendEnabled = sendEnabled;
    }
}
