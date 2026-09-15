package vn.nutrimom.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.knowledge.r2")
public record R2Properties(boolean enabled, String accountId, String accessKeyId, String secretAccessKey,
                           String bucketName, String publicBaseUrl) {}
