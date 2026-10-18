package vn.nutrimom.nutrition.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.nutrition.domain.MealFeedbackEntity;

/** Phản hồi sau bữa, ghi theo kiểu nhật ký — mỗi lần chạm một dòng. */
public interface MealFeedbackRepository extends JpaRepository<MealFeedbackEntity, String> {

    /**
     * Phản hồi <strong>còn hiệu lực</strong> của một người, mới nhất trước.
     *
     * <p>Hạn được lọc ngay trong câu truy vấn chứ không ở lớp trên: {@code HARD_NOW} hết hạn phải
     * biến mất khỏi điểm P <em>mà không cần</em> một job dọn dẹp nào chạy đúng giờ. Không xóa dòng
     * hết hạn — nó vẫn là lịch sử đã xảy ra, chỉ thôi ảnh hưởng tới xếp hạng.</p>
     *
     * <p>Thứ tự giảm dần theo {@code createdAt} để nơi gọi lấy đúng <strong>một</strong> phản hồi
     * gần nhất cho mỗi món (QĐ-03).</p>
     */
    @Query("select f from MealFeedbackEntity f where f.userId = :userId "
            + "and (f.expiresAt is null or f.expiresAt > :now) "
            + "order by f.createdAt desc")
    List<MealFeedbackEntity> findValid(@Param("userId") String userId,
                                       @Param("now") OffsetDateTime now);
}
