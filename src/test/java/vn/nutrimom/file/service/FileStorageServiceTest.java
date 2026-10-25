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
        return router(r2, false);
    }

    private FileStorageService router(PrivateR2StorageService r2, boolean requirePrivateR2) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (r2 != null) beans.addBean("privateR2", r2);
        return new FileStorageService(new LocalStorageService(directory.toString()),
                beans.getBeanProvider(PrivateR2StorageService.class), requirePrivateR2);
    }

    /**
     * Trên Render ổ đĩa bị xoá mỗi lần khởi động lại: thiếu R2 riêng thì phải từ chối ngay ở bước tạo
     * phiên upload (503 rõ ràng), không được lặng lẽ ghi tệp y tế vào ổ đĩa rồi làm mất (lỗi 25/10/2026).
     */
    @Test
    void requiredPrivateR2RefusesMedicalUploadsInsteadOfWritingToTheEphemeralDisk() {
        FileStorageService storage = router(null, true);
        assertThatThrownBy(() -> storage.newLocation("MEDICAL_RECORD", "user/file/random"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("FILE_STORAGE_UNAVAILABLE");
                    assertThat(error.getStatus().value()).isEqualTo(503);
                });
        // Mục đích khác không phải tệp y tế thì vẫn local như trước.
        assertThat(storage.newLocation("AVATAR", "user/file/avatar").provider()).isEqualTo(StorageProvider.LOCAL);

        PrivateR2StorageService r2 = mock(PrivateR2StorageService.class);
        when(r2.bucket()).thenReturn("private-bucket");
        assertThat(router(r2, true).newLocation("MEDICAL_RECORD", "user/file/random").provider())
                .isEqualTo(StorageProvider.R2);
    }

    @Test
    void selectsLocalOnlyWhenPrivateR2IsDisabled() {
        FileStorageService storage = router(null);
        StorageService.Location location = storage.newLocation("MEDICAL_RECORD", "user/file/random");
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
        StorageService.Location newFile = storage.newLocation("MEDICAL_RECORD", "new/file");
        assertThat(newFile.provider()).isEqualTo(StorageProvider.R2);
        assertThat(newFile.bucket()).isEqualTo("private-bucket");
        assertThat(newFile.key()).isEqualTo("storage/new/file");
        byte[] bytes = {8};
        storage.put(newFile, bytes);
        verify(r2).put(newFile, bytes);
    }

    @Test
    void otherPurposesStayLocalWithoutCallingPrivateR2() {
        PrivateR2StorageService r2 = mock(PrivateR2StorageService.class);
        FileStorageService storage = router(r2);
        for (String purpose : new String[] {"AVATAR", "ARTICLE_MEDIA", "OTHER"}) {
            StorageService.Location location = storage.newLocation(purpose, "user/file/" + purpose);
            assertThat(location.provider()).isEqualTo(StorageProvider.LOCAL);
            assertThat(location.bucket()).isNull();
            assertThat(location.key()).doesNotStartWith("storage/");
            storage.put(location, new byte[] {9});
            assertThat(storage.read(location)).containsExactly(9);
        }
        verifyNoInteractions(r2);
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
