package vn.nutrimom.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.config.CommunityProperties;
import vn.nutrimom.community.domain.CommunityPostImage;
import vn.nutrimom.community.repository.CommunityPostImageRepository;
import vn.nutrimom.community.service.CommunityMediaService;
import vn.nutrimom.community.service.CommunityMembers;
import vn.nutrimom.community.service.CommunityR2StorageService;
import vn.nutrimom.knowledge.service.ImageOptimizationService;
import vn.nutrimom.knowledge.service.ImageOptimizationService.OptimizedImage;

/** Upload ảnh với R2 giả: key an toàn, quota, và dọn object khi DB lỗi. */
class CommunityMediaServiceTest {
    private final CommunityR2StorageService store = mock(CommunityR2StorageService.class);
    private final CommunityPostImageRepository images = mock(CommunityPostImageRepository.class);
    private final CommunityMembers members = mock(CommunityMembers.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final CommunityProperties properties =
            new CommunityProperties(4, 3, 10, 100, 40, Duration.ofHours(24), Duration.ofDays(7), false, 50);

    @SuppressWarnings("unchecked")
    private CommunityMediaService service(boolean configured) {
        ObjectProvider<CommunityR2StorageService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configured ? store : null);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new CommunityMediaService(new ImageOptimizationService(), provider, images, members, properties,
                transactions);
    }

    @Test
    void usesRandomKeyAndNeverTheClientFilename() throws Exception {
        when(store.upload(anyString(), any())).thenAnswer(call -> "https://pub.test/" + call.getArgument(0));
        var result = service(true).upload(png("../../evil name.png"), "user-1");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(store).upload(key.capture(), any(OptimizedImage.class));
        assertThat(key.getValue()).matches("community/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg").doesNotContain("evil");
        assertThat(result.url()).endsWith(key.getValue());
        verify(images).saveAndFlush(any(CommunityPostImage.class));
    }

    @Test
    void deletesUploadedObjectWhenDatabaseFails() throws Exception {
        when(store.upload(anyString(), any())).thenReturn("https://pub.test/x.jpg");
        when(images.saveAndFlush(any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service(true).upload(png("a.png"), "user-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.DB_SAVE_FAILED.name());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(store).upload(key.capture(), any());
        verify(store).delete(eq(key.getValue()));
    }

    @Test
    void rejectsSpoofedFileBeforeTouchingStorage() {
        var fake = new MockMultipartFile("file", "a.png", "image/png", "<svg onload=alert(1)>".getBytes());
        assertThatThrownBy(() -> service(true).upload(fake, "user-1")).isInstanceOf(BusinessException.class);
        verify(store, never()).upload(anyString(), any());
    }

    @Test
    void enforcesDailyQuotaAndMissingStorage() throws Exception {
        when(images.countByOwnerIdAndCreatedAtGreaterThanEqual(eq("user-1"), any())).thenReturn(40L);
        assertThatThrownBy(() -> service(true).upload(png("a.png"), "user-1"))
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED.name());
        assertThatThrownBy(() -> service(false).upload(png("a.png"), "user-1"))
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(ErrorCode.R2_UPLOAD_FAILED.name());
        verify(store, never()).upload(anyString(), any());
    }

    private static MockMultipartFile png(String name) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }
}
