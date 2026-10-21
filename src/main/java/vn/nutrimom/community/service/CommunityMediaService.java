package vn.nutrimom.community.service;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.config.CommunityProperties;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.domain.CommunityPostImage;
import vn.nutrimom.community.domain.ImageStatus;
import vn.nutrimom.community.dto.CommunityDtos.MediaUpload;
import vn.nutrimom.community.repository.CommunityPostImageRepository;
import vn.nutrimom.knowledge.service.ImageOptimizationService;

/**
 * Upload một ảnh cộng đồng: kiểm tra → encode lại JPEG → R2 → ghi DB ở trạng thái PENDING.
 *
 * <p>Không mở transaction quanh lúc xử lý ảnh/gọi R2 để không giữ kết nối DB trong vài giây. Thứ tự
 * giống {@code ArticleMediaService}: R2 trước, DB sau; DB lỗi thì xoá object vừa ghi.</p>
 *
 * <p>Encode lại thành JPEG làm rơi toàn bộ metadata (EXIF, GPS) và mọi dữ liệu lạ chèn trong file.
 * Key là UUID, không dùng tên file người dùng gửi.</p>
 */
@Service
public class CommunityMediaService {
    private static final Logger log = LoggerFactory.getLogger(CommunityMediaService.class);
    private final ImageOptimizationService optimizer;
    private final ObjectProvider<CommunityR2StorageService> storage;
    private final CommunityPostImageRepository images;
    private final CommunityMembers members;
    private final CommunityProperties properties;
    private final TransactionTemplate tx;

    public CommunityMediaService(ImageOptimizationService optimizer, ObjectProvider<CommunityR2StorageService> storage,
            CommunityPostImageRepository images, CommunityMembers members, CommunityProperties properties,
            PlatformTransactionManager transactions) {
        this.optimizer = optimizer;
        this.storage = storage;
        this.images = images;
        this.members = members;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public MediaUpload upload(MultipartFile file, String userId) {
        CommunityR2StorageService store = storage.getIfAvailable();
        if (store == null) {
            throw new BusinessException(ErrorCode.R2_UPLOAD_FAILED, "Kho ảnh cộng đồng chưa được cấu hình.");
        }
        members.requireActive(userId);
        OffsetDateTime now = CommunityClock.now();
        if (images.countByOwnerIdAndCreatedAtGreaterThanEqual(userId, now.minusHours(24)) >= properties.dailyImageLimit()) {
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED, "Bạn đã tải lên số ảnh tối đa trong 24 giờ.");
        }

        var image = optimizer.optimize(file);
        String id = UUID.randomUUID().toString();
        String key = "community/%04d/%02d/%s.%s".formatted(now.getYear(), now.getMonthValue(), id, image.extension());
        String url = store.upload(key, image);
        try {
            tx.executeWithoutResult(status -> {
                CommunityPostImage row = new CommunityPostImage();
                row.setId(id);
                row.setOwnerId(userId);
                row.setImageKey(key);
                row.setImageUrl(url);
                row.setWidth(image.width());
                row.setHeight(image.height());
                row.setSizeBytes(image.bytes().length);
                row.setStatus(ImageStatus.PENDING);
                row.setDeleteAfter(now.plus(properties.pendingImageTtl()));
                images.saveAndFlush(row);
            });
        } catch (RuntimeException ex) {
            log.error("Community media DB persistence failed key={}", key, ex);
            try {
                store.delete(key);
            } catch (RuntimeException cleanup) {
                log.error("COMMUNITY_R2_ORPHAN key={} manual cleanup required", key);
            }
            throw new BusinessException(ErrorCode.DB_SAVE_FAILED, "Không lưu được ảnh. Vui lòng thử lại.");
        }
        return new MediaUpload(id, url, image.width(), image.height());
    }
}
