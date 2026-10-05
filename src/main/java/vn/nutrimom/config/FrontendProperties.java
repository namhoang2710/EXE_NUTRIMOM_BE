package vn.nutrimom.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Địa chỉ web mà backend cần dựng link trỏ về, dưới prefix {@code app.frontend}.
 *
 * <p>Cố ý tách khỏi {@code app.auth.magic-link-base-url}: biến đó là một <em>đường dẫn đầy đủ</em>
 * ({@code .../auth/verify}), còn {@code base-url} ở đây là <em>origin thuần</em>. Gộp hai thứ khác
 * ngữ nghĩa vào một khoá là cách chắc chắn để một hôm nào đó link mời trỏ vào trang xác thực.</p>
 *
 * <p>Link gửi ra ngoài luôn là {@code https://} thường, <strong>không bao giờ</strong> dùng scheme
 * riêng kiểu {@code nutrimom://}: mail client không mở được scheme lạ, và khi nào có app mobile thì
 * chính link https này sẽ tự mở app qua Universal Links / App Links mà không phải sửa gì ở đây.</p>
 */
@ConfigurationProperties(prefix = "app.frontend")
public class FrontendProperties {

    private String baseUrl = "http://localhost:5173";
    private String invitePath = "/family/invite";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    public String getInvitePath() {
        return invitePath;
    }

    public void setInvitePath(String invitePath) {
        this.invitePath = invitePath;
    }

    /** {@code {base}{invitePath}?token=...} — đích của nút "Chấp nhận lời mời" trong email. */
    public String inviteUrl(String rawToken) {
        return baseUrl + invitePath + "?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
