package vn.nutrimom.nutrition.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.nutrition.domain.RecommendationLogEntity;

/**
 * Nhật ký gợi ý.
 *
 * <p>Chỉ ghi và đọc lại khi đi tìm lỗi — không có endpoint nào phục vụ bảng này cho người dùng
 * cuối. Điểm thuật toán giữ ở backend.</p>
 */
public interface RecommendationLogRepository extends JpaRepository<RecommendationLogEntity, String> {

    List<RecommendationLogEntity> findByUserIdOrderByCreatedAtDesc(String userId, Limit limit);

    /**
     * Một lượt gợi ý kèm toàn bộ món ứng viên, nạp trong cùng một câu.
     *
     * <p>Cần {@code join fetch} vì {@code open-in-view} đã tắt: đọc một lượt gợi ý mà không có nó
     * thì danh sách ứng viên — tức là <em>toàn bộ</em> giá trị của bảng này — không mở ra được
     * ngoài transaction.</p>
     */
    @Query("select l from RecommendationLogEntity l left join fetch l.entries where l.id = :id")
    Optional<RecommendationLogEntity> findWithEntries(@Param("id") String id);
}
