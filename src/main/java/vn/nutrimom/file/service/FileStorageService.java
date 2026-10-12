package vn.nutrimom.file.service;

import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.file.domain.StorageProvider;

@Service
public class FileStorageService implements StorageService {
    private final LocalStorageService local;
    private final ObjectProvider<PrivateR2StorageService> privateR2;

    public FileStorageService(LocalStorageService local, ObjectProvider<PrivateR2StorageService> privateR2) {
        this.local = local;
        this.privateR2 = privateR2;
    }

    @Override
    public Location newLocation(String storageKey) {
        PrivateR2StorageService r2 = privateR2.getIfAvailable();
        return r2 == null ? new Location(StorageProvider.LOCAL, null, storageKey)
                : new Location(StorageProvider.R2, r2.bucket(), storageKey);
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
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_STORAGE_UNAVAILABLE",
                    "Private file storage is not configured.", true);
        }
        return r2;
    }
}
