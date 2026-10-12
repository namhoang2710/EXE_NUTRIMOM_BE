package vn.nutrimom.file.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.nutrimom.file.service.PrivateR2StorageService;

class FileStorageConfigTest {
    private PrivateR2Properties properties(String bucket) {
        return new PrivateR2Properties(true, "0123456789abcdef0123456789abcdef",
                "test-access", "test-secret", bucket);
    }

    @Test
    void acceptsSeparatePrivateBucketWithoutPublicUrl() {
        FileStorageConfig.validate(properties("nutrimom-private-test"), "nutrimom-public-test");
    }

    @Test
    void disabledAdapterCreatesNoPrivateClientAndEnabledAdapterNeedsValidConfiguration() {
        ApplicationContextRunner context = new ApplicationContextRunner()
                .withUserConfiguration(FileStorageConfig.class)
                .withBean(PrivateR2Properties.class, () -> properties("private-bucket"));
        context.withPropertyValues("app.storage.r2.enabled=false").run(application ->
                assertThat(application).doesNotHaveBean(PrivateR2StorageService.class));
        context.withPropertyValues("app.storage.r2.enabled=true").run(application ->
                assertThat(application).hasSingleBean(PrivateR2StorageService.class)
                        .doesNotHaveBean(software.amazon.awssdk.services.s3.S3Client.class));
        context.withPropertyValues("app.storage.r2.enabled=true", "app.knowledge.r2.bucket-name=private-bucket")
                .run(application -> assertThat(application.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class));
    }

    @Test
    void rejectsMissingBucketCredentialsInvalidAccountAndPublicBucketReuse() {
        assertThatThrownBy(() -> FileStorageConfig.validate(properties(""), "public-bucket"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FILES_R2_BUCKET_NAME");
        assertThatThrownBy(() -> FileStorageConfig.validate(properties("public-bucket"), "public-bucket"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must differ");
        for (String bucket : new String[] {"UPPERCASE", "../private", "ab", "private/bucket"}) {
            assertThatThrownBy(() -> FileStorageConfig.validate(properties(bucket), "public-bucket"))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> FileStorageConfig.validate(
                new PrivateR2Properties(true, "not-an-account", "access", "secret", "private-bucket"), ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FileStorageConfig.validate(
                new PrivateR2Properties(true, "0123456789abcdef0123456789abcdef", null, "secret", "private-bucket"), ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
