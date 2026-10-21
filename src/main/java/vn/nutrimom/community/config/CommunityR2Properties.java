package vn.nutrimom.community.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * R2 riêng của cộng đồng (tài khoản Cloudflare khác bucket bài viết), đọc từ {@code COMMUNITY_R2_*}.
 * Cố ý không fallback sang {@code R2_*}: nhầm tài khoản là ghi ảnh người dùng vào bucket CMS.
 */
@ConfigurationProperties("app.community.r2")
public record CommunityR2Properties(boolean enabled, String accountId, String accessKeyId,
                                    String secretAccessKey, String bucketName, String publicBaseUrl) {
    @Override
    public String toString() {
        // Không để secret lọt vào log khi Spring in cấu hình lỗi.
        return "CommunityR2Properties[enabled=" + enabled + ", bucketName=" + bucketName + "]";
    }
}
