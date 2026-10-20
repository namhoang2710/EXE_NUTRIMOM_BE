package vn.nutrimom.community.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommunityStorageConfigTest {
    private static final String ACCOUNT = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsValidSeparateBucket() {
        assertThatCode(() -> CommunityStorageConfig.validate(props("community-bucket", "https://pub-x.r2.dev"),
                "article-bucket", "medical-bucket")).doesNotThrowAnyException();
    }

    @Test
    void rejectsSharedBucketsAndUnsafeUrls() {
        assertThatThrownBy(() -> CommunityStorageConfig.validate(props("article-bucket", "https://pub-x.r2.dev"),
                "article-bucket", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CommunityStorageConfig.validate(props("medical-bucket", "https://pub-x.r2.dev"),
                "", "medical-bucket")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CommunityStorageConfig.validate(props("community-bucket", "http://pub-x.r2.dev"),
                "", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CommunityStorageConfig.validate(props("community-bucket", "https://u:p@pub-x.r2.dev"),
                "", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CommunityStorageConfig.validate(
                new CommunityR2Properties(true, "bad", "k", "s", "community-bucket", "https://x.dev"), "", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toStringNeverContainsSecrets() {
        CommunityR2Properties properties = props("community-bucket", "https://pub-x.r2.dev");
        assertThat(properties.toString()).doesNotContain("SECRET-VALUE").doesNotContain("KEY-ID");
    }

    private static CommunityR2Properties props(String bucket, String url) {
        return new CommunityR2Properties(true, ACCOUNT, "KEY-ID", "SECRET-VALUE", bucket, url);
    }
}
