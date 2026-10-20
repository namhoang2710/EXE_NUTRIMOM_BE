package vn.nutrimom.community.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.nutrimom.community.config.CommunityProperties;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.domain.CommunityPostImage;
import vn.nutrimom.community.repository.CommunityPostImageRepository;

/**
 * Dọn ảnh không còn được tham chiếu: PENDING quá hạn (upload rồi không đăng) và ORPHANED đã qua thời
 * gian giữ lại (bài bị xoá/gỡ, ảnh bị bỏ khi sửa).
 *
 * <p>Xoá object R2 TRƯỚC, thành công mới xoá dòng DB; lỗi thì tăng {@code cleanup_attempts} để lượt sau
 * thử lại — không bao giờ mất dấu một object còn trên R2. {@code fixedDelay} nên hai lượt không chạy
 * chồng nhau; mỗi lượt tối đa {@code media-cleanup-batch-size} ảnh để không giữ RAM/kết nối lâu.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.community", name = "media-cleanup-enabled", havingValue = "true",
        matchIfMissing = true)
public class CommunityMediaCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(CommunityMediaCleanupJob.class);
    private final CommunityPostImageRepository images;
    private final ObjectProvider<CommunityR2StorageService> storage;
    private final CommunityProperties properties;

    public CommunityMediaCleanupJob(CommunityPostImageRepository images,
            ObjectProvider<CommunityR2StorageService> storage, CommunityProperties properties) {
        this.images = images;
        this.storage = storage;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "${app.community.media-cleanup-interval:PT1H}")
    public void run() {
        CommunityR2StorageService store = storage.getIfAvailable();
        if (store == null) return;
        cleanup(store);
    }

    /** Trả số ảnh đã xoá xong (dùng cho test). */
    public int cleanup(CommunityR2StorageService store) {
        List<CommunityPostImage> due = images.findDueForCleanup(CommunityClock.now(),
                PageRequest.of(0, properties.mediaCleanupBatchSize()));
        int deleted = 0;
        for (CommunityPostImage image : due) {
            try {
                store.delete(image.getImageKey());
                images.delete(image);
                deleted++;
            } catch (RuntimeException ex) {
                image.setCleanupAttempts(image.getCleanupAttempts() + 1);
                images.save(image);
                log.warn("Community media cleanup failed key={} attempts={} error={}", image.getImageKey(),
                        image.getCleanupAttempts(), ex.getClass().getSimpleName());
            }
        }
        if (deleted > 0) log.info("Community media cleanup deleted={} scanned={}", deleted, due.size());
        return deleted;
    }
}
