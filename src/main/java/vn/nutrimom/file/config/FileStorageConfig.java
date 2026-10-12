package vn.nutrimom.file.config;

import java.net.URI;
import java.time.Duration;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import vn.nutrimom.file.service.PrivateR2StorageService;

@Configuration
public class FileStorageConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.storage.r2", name = "enabled", havingValue = "true")
    PrivateR2StorageService privateR2StorageService(PrivateR2Properties properties,
            @Value("${app.knowledge.r2.bucket-name:}") String publicMediaBucket) {
        validate(properties, publicMediaBucket);
        // Keep this client inside the private adapter, separate from the public-media S3 bean.
        return new PrivateR2StorageService(createClient(properties), properties.bucketName());
    }

    static S3Client createClient(PrivateR2Properties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create("https://" + properties.accountId() + ".r2.cloudflarestorage.com"))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.accessKeyId(), properties.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(60))
                        .apiCallAttemptTimeout(Duration.ofSeconds(35)))
                .build();
    }

    static void validate(PrivateR2Properties properties, String publicMediaBucket) {
        if (Stream.of(properties.accountId(), properties.accessKeyId(), properties.secretAccessKey(),
                properties.bucketName()).anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("Private file R2 requires credentials and FILES_R2_BUCKET_NAME; "
                    + "configure a private bucket, not R2_PUBLIC_BASE_URL.");
        }
        if (!properties.accountId().matches("[a-fA-F0-9]{32}")) {
            throw new IllegalStateException("Invalid private file R2 account ID.");
        }
        String bucket = properties.bucketName();
        if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
            throw new IllegalStateException("Invalid FILES_R2_BUCKET_NAME.");
        }
        if (bucket.equals(publicMediaBucket)) {
            throw new IllegalStateException("FILES_R2_BUCKET_NAME must differ from the public-media bucket.");
        }
    }
}
