package vn.nutrimom.file.service;

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.file.domain.StorageProvider;

@Service
public class FileStorageService implements StorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final LocalStorageService local;
    private final ObjectProvider<PrivateR2StorageService> privateR2;
    /**
     * Bật trên môi trường có ổ đĩa tạm (Render): thiếu R2 riêng thì từ chối upload tệp y tế thay vì ghi
     * vào ổ đĩa sẽ bị xoá ở lần khởi động lại. Xem {@code app.storage.require-private-r2}.
     */
    private final boolean requirePrivateR2;

    public FileStorageService(LocalStorageService local, ObjectProvider<PrivateR2StorageService> privateR2,
                              @Value("${app.storage.require-private-r2:false}") boolean requirePrivateR2) {
        this.local = local;
        this.privateR2 = privateR2;
        this.requirePrivateR2 = requirePrivateR2;
    }

    @PostConstruct
    void warnWhenPrivateStorageIsMissing() {
        if (requirePrivateR2 && privateR2.getIfAvailable() == null) {
            log.error("FILES_R2_* chưa được cấu hình trong khi app.storage.require-private-r2=true: "
                    + "upload và tải tệp hồ sơ y tế sẽ trả FILE_STORAGE_UNAVAILABLE cho tới khi đặt biến.");
        }
    }

    @Override
    public Location newLocation(String purpose, String storageKey) {
        if (!"MEDICAL_RECORD".equals(purpose)) {
            return new Location(StorageProvider.LOCAL, null, storageKey);
        }
        PrivateR2StorageService r2 = privateR2.getIfAvailable();
        if (r2 == null) {
            if (requirePrivateR2) throw unavailable();
            return new Location(StorageProvider.LOCAL, null, storageKey);
        }
        return new Location(StorageProvider.R2, r2.bucket(), "storage/" + storageKey);
    }

    @Override
    public void put(Location location, byte[] content) {
        if (location.provider() == StorageProvider.LOCAL) local.put(location.key(), content);
        else requireR2(location).put(location, content);
    }

    @Override
    public Optional<StoredObject> head(Location location) {
        return location.provider() == StorageProvider.LOCAL ? local.head(location.key())
                : requireR2(location).head(location);
    }

    @Override
    public byte[] read(Location location) {
        return location.provider() == StorageProvider.LOCAL ? local.read(location.key())
                : requireR2(location).read(location);
    }

    private PrivateR2StorageService requireR2(Location location) {
        PrivateR2StorageService r2 = privateR2.getIfAvailable();
        if (location.provider() != StorageProvider.R2 || location.bucket() == null || r2 == null) {
            throw unavailable();
        }
        return r2;
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.FILE_STORAGE_UNAVAILABLE);
    }
}
