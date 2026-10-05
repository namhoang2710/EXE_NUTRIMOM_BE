package vn.nutrimom.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Link trong email chỉ sai một lần là cả mẻ lời mời thành ngõ cụt, nên khoá bằng test. */
class FrontendPropertiesTest {

    @Test
    void trailingSlashesInTheConfiguredOriginDoNotProduceADoubleSlash() {
        FrontendProperties properties = new FrontendProperties();
        properties.setBaseUrl("https://nutrimom.vn///");

        assertThat(properties.inviteUrl("abc"))
                .isEqualTo("https://nutrimom.vn/family/invite?token=abc");
    }

    @Test
    void tokenIsUrlEncoded() {
        FrontendProperties properties = new FrontendProperties();
        // Token sinh ra là base64url nên có thể chứa '-' và '_'; ký tự lạ vẫn phải được mã hoá
        // thay vì cắt ngang query string.
        assertThat(properties.inviteUrl("a+b/c=")).endsWith("?token=a%2Bb%2Fc%3D");
    }

    @Test
    void theDefaultPointsAtTheLocalDevServer() {
        assertThat(new FrontendProperties().inviteUrl("abc"))
                .isEqualTo("http://localhost:5173/family/invite?token=abc");
    }

    /** Link gửi ra ngoài phải mở được từ Gmail; scheme riêng thì không. */
    @Test
    void theInviteUrlIsAlwaysAnHttpUrl() {
        FrontendProperties properties = new FrontendProperties();
        properties.setBaseUrl("https://nutrimom.vn");

        assertThat(properties.inviteUrl("abc")).startsWith("https://");
    }

    @Test
    void theInvitePathIsConfigurable() {
        FrontendProperties properties = new FrontendProperties();
        properties.setBaseUrl("https://nutrimom.vn");
        properties.setInvitePath("/moi");

        assertThat(properties.inviteUrl("abc")).isEqualTo("https://nutrimom.vn/moi?token=abc");
    }
}
