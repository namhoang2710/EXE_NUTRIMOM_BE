package vn.nutrimom.file.service;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/** Private objects are served through FileService ownership/signed-token checks, never public URLs. */
public class PrivateR2StorageService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PrivateR2StorageService.class);
    private final S3Client client;
    private final String bucket;

    public PrivateR2StorageService(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    public String bucket() { return bucket; }

    public void put(StorageService.Location location, byte[] content) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(location.bucket()).key(location.key())
                    .contentType("application/octet-stream").contentLength((long) content.length)
                    .cacheControl("private, no-store").build(), RequestBody.fromBytes(content));
        } catch (RuntimeException exception) {
            throw unavailable("upload", exception);
        }
    }

    public Optional<StorageService.StoredObject> head(StorageService.Location location) {
        try {
            // One GET supplies the exact bytes and size used for checksum/MIME validation.
            byte[] content = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(location.bucket()).key(location.key()).build()).asByteArray();
            return Optional.of(new StorageService.StoredObject(content.length, content));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) return Optional.empty();
            throw unavailable("read", exception);
        } catch (RuntimeException exception) {
            throw unavailable("read", exception);
        }
    }

    public byte[] read(StorageService.Location location) {
        return head(location).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND", "The stored file was not found.")).content();
    }

    private BusinessException unavailable(String operation, RuntimeException exception) {
        // Do not log keys, filenames, credentials or provider exception messages.
        log.warn("Private R2 {} failed ({})", operation, exception.getClass().getSimpleName());
        // Cùng code với ErrorCode.FILE_STORAGE_UNAVAILABLE nhưng 502: lỗi đến từ phía R2, không phải thiếu cấu hình.
        return new BusinessException(HttpStatus.BAD_GATEWAY, ErrorCode.FILE_STORAGE_UNAVAILABLE.code(),
                "Không kết nối được kho lưu tệp hồ sơ y tế. Vui lòng thử lại.", true);
    }

    @Override
    public void close() { client.close(); }
}
