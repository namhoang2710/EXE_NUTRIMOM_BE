package vn.nutrimom.nutrition.repository;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.domain.RecipeRole;

public interface RecipeRepository extends JpaRepository<RecipeEntity, String> {

    /**
     * Tra cứu công thức cho {@code GET /api/v1/recipes}.
     *
     * <p>Phân trang bằng <strong>con trỏ</strong> chứ không phải offset: danh mục món được nạp lại
     * mỗi lần seed đổi phiên bản, nên offset có thể nhảy dòng giữa hai lượt gọi. Sắp xếp theo
     * {@code id} tăng dần — id là slug do seed đặt nên thứ tự ổn định giữa các lần chạy, khác với
     * sắp theo tên vốn phụ thuộc collation của database.</p>
     *
     * <p>Mọi tham số lọc đều cho phép null = không lọc, nên chỉ cần một câu truy vấn thay vì dựng
     * Specification động cho ba tổ hợp.</p>
     */
    @Query("""
            select r from RecipeEntity r
            where (:mealType is null or :mealType member of r.mealTypes)
              and (:role is null or r.role = :role)
              and (:maxMinutes is null or r.minutes <= :maxMinutes)
              and (:afterId is null or r.id > :afterId)
            order by r.id asc
            """)
    List<RecipeEntity> search(@Param("mealType") MealType mealType,
                              @Param("role") RecipeRole role,
                              @Param("maxMinutes") Integer maxMinutes,
                              @Param("afterId") String afterId,
                              Limit limit);
}
