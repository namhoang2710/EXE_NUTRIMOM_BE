package vn.nutrimom.nutrition.repository;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.ShoppingListEntity;

/**
 * Danh sách mua sắm theo kế hoạch và phạm vi ngày.
 *
 * <p>Mọi truy vấn mang theo {@code userId}: tài khoản khác hỏi đúng mã danh sách thì không có dòng
 * nào trả về, nên không có đường nào để quên mất bước kiểm chủ sở hữu (BR19).</p>
 */
public interface ShoppingListRepository extends JpaRepository<ShoppingListEntity, String> {

    @EntityGraph(attributePaths = "items")
    Optional<ShoppingListEntity> findByPlanIdAndUserIdAndStartDateAndEndDate(
            String planId, String userId, LocalDate startDate, LocalDate endDate);
}
