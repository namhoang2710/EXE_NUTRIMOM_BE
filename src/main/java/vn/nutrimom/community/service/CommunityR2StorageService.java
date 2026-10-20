package vn.nutrimom.community.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.knowledge.service.ImageOptimizationService.OptimizedImage;

/** Ghi/xoá ảnh cộng đồng trên bucket R2 riêng. Chỉ log key, không bao giờ log credentials. */
public class CommunityR2StorageService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CommunityR2StorageService.class);
    private final S3Client client;
    private final String bucket;
    private final String publicBaseUrl;

    public CommunityR2StorageService(S3Client client, String bucket, String publicBaseUrl) {
        this.client = client;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Ghi ảnh và trả URL công khai. Lỗi/timeout thì xoá thử theo key vì R2 có thể đã nhận object. */
    public String upload(String key, OptimizedImage image) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType(image.contentType()).contentLength((long) image.bytes().length)
                    .cacheControl("public, max-age=31536000, immutable").build(), RequestBody.fromBytes(image.bytes()));
            log.info("Community R2 upload succeeded key={}", key);
            return publicBaseUrl + "/" + key;
        } catch (RuntimeException ex) {
            log.warn("Community R2 upload failed key={} error={}", key, ex.getClass().getSimpleName());
            try {
                delete(key);
            } catch (RuntimeException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw new BusinessException(ErrorCode.R2_UPLOAD_FAILED, "Không tải được ảnh lên. Vui lòng thử lại.");
        }
    }

    /** Xoá object; S3/R2 trả thành công cả khi key không còn, nên gọi lặp lại là an toàn. */
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void close() {
        client.close();
    }
}
