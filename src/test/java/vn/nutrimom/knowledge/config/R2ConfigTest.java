package vn.nutrimom.knowledge.config;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

class R2ConfigTest {
    private R2Properties properties(String account, String baseUrl) {
        return new R2Properties(true, account, "test-access-key", "test-secret-key", "test-bucket", baseUrl);
    }
    @Test void productionClientBuildsWithR2EndpointAndRegionWithoutNetworkAccess() {
        String account = "0123456789abcdef0123456789abcdef";
        try (S3Client client = new R2Config().r2Client(properties(account, "https://media.example.com"))) {
            var config = client.serviceClientConfiguration();
            assertThat(config.region().id()).isEqualTo("auto");
            assertThat(config.endpointOverride()).hasValue(java.net.URI.create("https://"+account+".r2.cloudflarestorage.com"));
        }
    }
    @Test void rejectsMissingCredentialsMalformedAccountAndUnsafePublicUrl() {
        R2Config config = new R2Config();
        assertThatThrownBy(() -> config.r2Client(properties("", "https://media.example.com"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.r2Client(properties("not-an-account-id", "https://media.example.com"))).isInstanceOf(IllegalStateException.class);
        for (String url : new String[]{"http://media.example.com", "https://user:secret@media.example.com", "https://media.example.com?token=x"})
            assertThatThrownBy(() -> config.r2Client(properties("0123456789abcdef0123456789abcdef", url))).isInstanceOf(IllegalStateException.class);
    }
}
