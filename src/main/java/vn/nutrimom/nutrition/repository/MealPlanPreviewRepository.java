package vn.nutrimom.nutrition.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.MealPlanPreviewEntity;

public interface MealPlanPreviewRepository
        extends JpaRepository<MealPlanPreviewEntity, String> {

    /** Đường idempotency: cùng {@code (user, request_id)} phải trả lại đúng bản cũ (_CONTRACT mục 6). */
    Optional<MealPlanPreviewEntity> findByUserIdAndRequestId(String userId, String requestId);

    /** Chủ sở hữu nằm trong truy vấn, không phải trong một câu {@code if} viết thêm (TC20). */
    Optional<MealPlanPreviewEntity> findByIdAndUserId(String id, String userId);
}
