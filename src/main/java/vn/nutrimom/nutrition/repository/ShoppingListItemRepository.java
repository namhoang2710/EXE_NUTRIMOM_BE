package vn.nutrimom.nutrition.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.ShoppingListItemEntity;

/**
 * Một dòng đi chợ, luôn kèm chủ sở hữu.
 *
 * <p>{@code PATCH /api/v1/shopping-list-items/{id}} không có mã kế hoạch trong đường dẫn, nên chủ
 * sở hữu phải nằm trong chính câu truy vấn — một câu {@code if} sau khi đã đọc xong là chỗ duy
 * nhất có thể quên (BR19, TC20).</p>
 */
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItemEntity, String> {

    Optional<ShoppingListItemEntity> findByIdAndListUserId(String id, String userId);
}
