package vn.nutrimom.community.config;

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
import vn.nutrimom.community.service.CommunityR2StorageService;

/**
 * Kho ảnh cộng đồng.
 *
 * <p>Bean là {@link CommunityR2StorageService}, S3Client nằm bên trong (giống {@code FileStorageConfig}).
 * KHÔNG khai báo thêm {@code @Bean S3Client}: {@code R2StorageService} của bài viết lấy S3Client duy
 * nhất trong context, có hai bean cùng kiểu sẽ làm hỏng upload ảnh bài viết.</p>
 */
@Configuration
public class CommunityStorageConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.community.r2", name = "enabled", havingValue = "true")
    CommunityR2StorageService communityR2StorageService(CommunityR2Properties properties,
            @Value("${app.knowledge.r2.bucket-name:}") String knowledgeBucket,
            @Value("${app.storage.r2.bucket-name:}") String medicalFilesBucket) {
        validate(properties, knowledgeBucket, medicalFilesBucket);
        return new CommunityR2StorageService(createClient(properties), properties.bucketName(),
                properties.publicBaseUrl().replaceAll("/+$", ""));
    }

    static void validate(CommunityR2Properties p, String knowledgeBucket, String medicalFilesBucket) {
        if (Stream.of(p.accountId(), p.accessKeyId(), p.secretAccessKey(), p.bucketName(), p.publicBaseUrl())
                .anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("COMMUNITY_R2_* credentials, bucket and public base URL are required "
                    + "when COMMUNITY_R2_ENABLED=true");
        }
        if (!p.accountId().matches("[a-fA-F0-9]{32}")) {
            throw new IllegalStateException("Invalid COMMUNITY_R2_ACCOUNT_ID");
        }
        if (!p.bucketName().matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
            throw new IllegalStateException("Invalid COMMUNITY_R2_BUCKET_NAME");
        }
        if (p.bucketName().equals(knowledgeBucket) || p.bucketName().equals(medicalFilesBucket)) {
            throw new IllegalStateException(
                    "COMMUNITY_R2_BUCKET_NAME must differ from the article and medical-file buckets");
        }
        URI publicUrl = URI.create(p.publicBaseUrl());
        if (!"https".equals(publicUrl.getScheme()) || publicUrl.getHost() == null || publicUrl.getUserInfo() != null
                || publicUrl.getQuery() != null || publicUrl.getFragment() != null) {
            throw new IllegalStateException(
                    "COMMUNITY_R2_PUBLIC_BASE_URL must be an HTTPS URL without credentials, query or fragment");
        }
    }

    private static S3Client createClient(CommunityR2Properties p) {
        return S3Client.builder()
                .endpointOverride(URI.create("https://" + p.accountId() + ".r2.cloudflarestorage.com"))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(p.accessKeyId(), p.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(60))
                        .apiCallAttemptTimeout(Duration.ofSeconds(35)))
                .build();
    }
}
