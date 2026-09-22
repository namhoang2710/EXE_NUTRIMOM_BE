package vn.nutrimom.consultation.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.dto.ExpertDtos.AdminExpertResponse;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;
import vn.nutrimom.knowledge.service.ImageOptimizationService;
import vn.nutrimom.knowledge.service.ImageOptimizationService.OptimizedImage;
import vn.nutrimom.knowledge.service.R2StorageService;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.auth.domain.UserEntity;

/** Admin upload ảnh avatar chuyên gia lên R2 (tái dùng pipeline ảnh của knowledge media). */
@Service
public class ExpertAvatarService {
    private static final Logger log = LoggerFactory.getLogger(ExpertAvatarService.class);
    private final ImageOptimizationService optimizer;
    private final R2StorageService storage;
    private final ExpertProfileRepository experts;
    private final UserRepository users;
    private final AccessGuard guard;

    public ExpertAvatarService(ImageOptimizationService optimizer, R2StorageService storage,
                               ExpertProfileRepository experts, UserRepository users, AccessGuard guard) {
        this.optimizer = optimizer;
        this.storage = storage;
        this.experts = experts;
        this.users = users;
        this.guard = guard;
    }

    @Transactional
    public AdminExpertResponse uploadAvatar(String expertUserId, MultipartFile file) {
        ExpertProfileEntity profile = guard.requireOwned(
                experts.findById(expertUserId), "Không tìm thấy chuyên gia.");
        UserEntity user = users.findById(expertUserId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tài khoản chuyên gia."));

        OptimizedImage image = optimizer.optimize(file);
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        String key = "expert/%04d/%02d/%s.%s".formatted(
                now.getYear(), now.getMonthValue(), UUID.randomUUID(), image.extension());
        String previousKey = profile.getAvatarKey();
        String url = storage.upload(key, image);

        profile.setAvatarKey(key);
        profile.setAvatarUrl(url);
        try {
            experts.saveAndFlush(profile);
        } catch (RuntimeException ex) {
            try {
                storage.delete(key);
            } catch (RuntimeException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw new BusinessException(ErrorCode.DB_SAVE_FAILED,
                    "Không lưu được avatar. Vui lòng thử lại.");
        }
        if (previousKey != null && !previousKey.equals(key)) {
            try {
                storage.delete(previousKey);
            } catch (RuntimeException cleanup) {
                log.warn("Không xóa được avatar cũ key={}", previousKey, cleanup);
            }
        }
        return new AdminExpertResponse(profile.getUserId(), user.getPhone(), profile.getFullName(),
                profile.getSpecialty(), profile.getTitle(), profile.getWorkplace(),
                profile.getYearsOfExperience(), profile.getBio(), profile.getAvatarKey(),
                profile.getAvatarUrl(), profile.getStatus(), profile.getAverageRating(),
                profile.getRatingCount(), profile.getVersion(), profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
