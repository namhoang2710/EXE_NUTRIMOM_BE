package vn.nutrimom.nutrition.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.PriceEstimateEntity;

/**
 * Giá ước tính theo nguyên liệu. Khoá chính chính là ingredientId.
 *
 * <p>Nguyên liệu KHÔNG có dòng ở đây là chuyện bình thường: màn hình hiển thị "Chưa có giá" và
 * tổng chi phí mang nhãn chưa đầy đủ, chứ không coi là 0 đồng.</p>
 */
public interface PriceEstimateRepository extends JpaRepository<PriceEstimateEntity, String> {

    List<PriceEstimateEntity> findByIngredientIdIn(List<String> ingredientIds);
}
