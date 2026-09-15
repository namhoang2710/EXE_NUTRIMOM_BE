package vn.nutrimom.knowledge.service;

import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.knowledge.config.R2Properties;
import vn.nutrimom.knowledge.service.ImageOptimizationService.OptimizedImage;

@Service
public class R2StorageService {
    private static final Logger log = LoggerFactory.getLogger(R2StorageService.class);
    private final ObjectProvider<S3Client> clients;
    private final R2Properties config;
    public R2StorageService(ObjectProvider<S3Client> clients, R2Properties config) {
        this.clients = clients; this.config = config;
    }
    public String upload(String key, OptimizedImage image) {
        S3Client client = requireClient();
        try {
            client.putObject(PutObjectRequest.builder().bucket(config.bucketName()).key(key)
                    .contentType(image.contentType()).contentLength((long) image.bytes().length)
                    .cacheControl("public, max-age=31536000, immutable").build(), RequestBody.fromBytes(image.bytes()));
            log.info("R2 upload succeeded key={}", key);
            return config.publicBaseUrl().replaceAll("/+$", "") + "/" + key;
        } catch (RuntimeException ex) {
            log.error("R2 upload failed key={}", key, ex);
            // A timeout can occur after R2 accepted the object. Best-effort deletion uses the same key.
            try { delete(key); } catch (RuntimeException cleanup) { ex.addSuppressed(cleanup); }
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "R2_UPLOAD_FAILED", "Image could not be uploaded. Please retry.", true);
        }
    }
    public void delete(String key) {
        S3Client client = requireClient();
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucketName()).key(key).build());
            log.info("R2 orphan cleanup succeeded key={}", key);
        } catch (RuntimeException ex) {
            log.error("R2_ORPHAN_CLEANUP_FAILED key={} bucket={} manual cleanup required", key, config.bucketName(), ex);
            throw ex;
        }
    }
    private S3Client requireClient() {
        S3Client client = clients.getIfAvailable();
        if (!config.enabled() || client == null) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "R2_UPLOAD_FAILED", "Media storage is not configured.", true);
        return client;
    }
}
