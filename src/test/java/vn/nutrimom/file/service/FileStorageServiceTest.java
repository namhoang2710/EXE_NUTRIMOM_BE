package vn.nutrimom.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.file.domain.StorageProvider;

class FileStorageServiceTest {
    @TempDir Path directory;

    private FileStorageService router(PrivateR2StorageService r2) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (r2 != null) beans.addBean("privateR2", r2);
        return new FileStorageService(new LocalStorageService(directory.toString()),
                beans.getBeanProvider(PrivateR2StorageService.class));
    }

    @Test
    void selectsLocalOnlyWhenPrivateR2IsDisabled() {
        FileStorageService storage = router(null);
        StorageService.Location location = storage.newLocation("user/file/random");
        assertThat(location.provider()).isEqualTo(StorageProvider.LOCAL);
        assertThat(location.bucket()).isNull();
        storage.put(location, new byte[] {1, 2});
        assertThat(storage.read(location)).containsExactly(1, 2);
    }

    @Test
    void newUploadsUseR2AndLegacyLocalFilesRemainReadable() {
        PrivateR2StorageService r2 = mock(PrivateR2StorageService.class);
        when(r2.bucket()).thenReturn("private-bucket");
        FileStorageService storage = router(r2);
        StorageService.Location oldFile = new StorageService.Location(StorageProvider.LOCAL, null, "old/file");
        storage.put(oldFile, new byte[] {7});
        assertThat(storage.read(oldFile)).containsExactly(7);
        verifyNoInteractions(r2);
        StorageService.Location newFile = storage.newLocation("new/file");
        assertThat(newFile.provider()).isEqualTo(StorageProvider.R2);
        assertThat(newFile.bucket()).isEqualTo("private-bucket");
        byte[] bytes = {8};
        storage.put(newFile, bytes);
        verify(r2).put(newFile, bytes);
    }

    @Test
    void r2FailureAndDisabledR2NeverFallBackToLocal() {
        StorageService.Location location = new StorageService.Location(StorageProvider.R2, "private-bucket", "file");
        assertThatThrownBy(() -> router(null).read(location)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo("FILE_STORAGE_UNAVAILABLE"));
        PrivateR2StorageService r2 = mock(PrivateR2StorageService.class);
        when(r2.read(any())).thenThrow(new BusinessException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "FILE_STORAGE_UNAVAILABLE", "Unavailable"));
        assertThatThrownBy(() -> router(r2).read(location)).isInstanceOf(BusinessException.class);
    }
}
