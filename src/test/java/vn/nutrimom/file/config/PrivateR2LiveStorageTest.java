package vn.nutrimom.file.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import vn.nutrimom.file.domain.StorageProvider;
import vn.nutrimom.file.service.PrivateR2StorageService;
import vn.nutrimom.file.service.StorageService;

/** Opt-in live test; writes a synthetic image and deletes only its own uniquely named object. */
@EnabledIfEnvironmentVariable(named = "FILES_R2_SMOKE_TEST", matches = "true")
class PrivateR2LiveStorageTest {
    @Test
    void actualPrivateR2BucketStoresAndReturnsIdenticalImageBytes() {
        PrivateR2Properties properties = new PrivateR2Properties(true,
                setting("FILES_R2_ACCOUNT_ID", "R2_ACCOUNT_ID"),
                setting("FILES_R2_ACCESS_KEY_ID", "R2_ACCESS_KEY_ID"),
                setting("FILES_R2_SECRET_ACCESS_KEY", "R2_SECRET_ACCESS_KEY"),
                System.getenv("FILES_R2_BUCKET_NAME"));
        FileStorageConfig.validate(properties, System.getenv("R2_BUCKET_NAME"));
        assertThat(properties.bucketName()).isEqualTo("nutrimom-medical-records");
        String key = "storage/_smoke-test/" + UUID.randomUUID() + ".png";
        StorageService.Location location = new StorageService.Location(StorageProvider.R2, properties.bucketName(), key);
        byte[] bytes = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5V8AAAAASUVORK5CYII=");
        try (var client = FileStorageConfig.createClient(properties)) {
            PrivateR2StorageService adapter = new PrivateR2StorageService(client, properties.bucketName());
            try {
                adapter.put(location, bytes);
                assertThat(adapter.head(location).orElseThrow().sizeBytes()).isEqualTo(bytes.length);
                assertThat(adapter.read(location)).isEqualTo(bytes);
            } finally {
                client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucketName()).key(key).build());
            }
        }
    }

    private String setting(String primary, String fallback) {
        String value = System.getenv(primary);
        return value == null || value.isBlank() ? System.getenv(fallback) : value;
    }
}
