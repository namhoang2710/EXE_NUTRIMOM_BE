package vn.nutrimom.knowledge.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;

@Configuration
public class R2Config {
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(prefix="app.knowledge.r2", name="enabled", havingValue="true")
    S3Client r2Client(R2Properties p) {
        if (List.of(p.accountId(), p.accessKeyId(), p.secretAccessKey(), p.bucketName(), p.publicBaseUrl())
                .stream().anyMatch(String::isBlank)) throw new IllegalStateException("All R2 credentials, bucket and R2_PUBLIC_BASE_URL are required when R2_ENABLED=true");
        if (!p.accountId().matches("[a-fA-F0-9]{32}")) throw new IllegalStateException("Invalid R2_ACCOUNT_ID");
        URI publicUrl = URI.create(p.publicBaseUrl());
        if (!"https".equals(publicUrl.getScheme()) || publicUrl.getHost() == null || publicUrl.getUserInfo() != null
                || publicUrl.getQuery() != null || publicUrl.getFragment() != null)
            throw new IllegalStateException("R2_PUBLIC_BASE_URL must be an HTTPS URL without credentials, query or fragment");
        return S3Client.builder()
                .endpointOverride(URI.create("https://" + p.accountId() + ".r2.cloudflarestorage.com"))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKeyId(), p.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(35)))
                .build();
    }
}
