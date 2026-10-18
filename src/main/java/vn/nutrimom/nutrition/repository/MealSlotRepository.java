package vn.nutrimom.nutrition.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.nutrition.domain.MealPlanItemEntity;
import vn.nutrimom.nutrition.domain.MealSlotEntity;

/**
 * Bữa của kế hoạch, tra <strong>theo ngày</strong> thay vì theo kế hoạch.
 *
 * <p>Màn hình nhật ký hỏi "ngày 14/10 tôi dự kiến ăn gì", không hỏi "kế hoạch pl_x có gì". Hỏi theo
 * ngày cũng là cách duy nhất đúng khi nhánh 9 lập thực đơn ba và bảy ngày: một ngày lúc đó nằm ở
 * giữa một kế hoạch chứ không trùng với nó.</p>
 */
public interface MealSlotRepository extends JpaRepository<MealSlotEntity, String> {

    /**
     * Các bữa <strong>dự kiến</strong> của một người trong một ngày.
     *
     * <p>Chủ sở hữu suy qua kế hoạch ({@code slot.plan.userId}) và nằm trong chính câu truy vấn, nên
     * không có đường nào đọc được kế hoạch của người khác (BR19).</p>
     */
    @Query("select s from MealSlotEntity s join fetch s.plan p "
            + "where p.userId = :userId and s.localDate = :localDate")
    List<MealSlotEntity> findPlannedSlots(@Param("userId") String userId,
                                          @Param("localDate") LocalDate localDate);

    /**
     * Một mục kế hoạch của đúng người gọi — nguồn tùy chọn của một dòng nhật ký.
     *
     * <p>Nằm ở repository của bữa chứ không của mục vì {@code MealPlanItemEntity} chỉ tới được chủ
     * sở hữu qua {@code slot.plan}, và một repository thứ hai cho cùng một đường đi chỉ thêm chỗ để
     * quên điều kiện {@code userId}.</p>
     */
    @Query("select i from MealPlanItemEntity i where i.id = :itemId and i.slot.plan.userId = :userId")
    Optional<MealPlanItemEntity> findPlanItem(@Param("itemId") String itemId,
                                              @Param("userId") String userId);
}
