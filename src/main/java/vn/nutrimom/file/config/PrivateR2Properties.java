package vn.nutrimom.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.storage.r2")
public record PrivateR2Properties(boolean enabled, String accountId, String accessKeyId,
                                 String secretAccessKey, String bucketName) { }
