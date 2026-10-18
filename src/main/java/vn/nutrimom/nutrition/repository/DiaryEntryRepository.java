package vn.nutrimom.nutrition.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.DiaryEntryEntity;

/**
 * Nhật ký đã ăn.
 *
 * <p>Mọi câu truy vấn ở đây mang {@code userId} <strong>trong chính điều kiện lọc</strong>, không
 * phải trong một câu {@code if} viết thêm sau khi đã đọc xong: tài khoản khác hỏi đúng mã bản ghi
 * thì không có dòng nào trả về, nên không có đường nào để quên bước kiểm (BR19, TC20).</p>
 */
public interface DiaryEntryRepository extends JpaRepository<DiaryEntryEntity, String> {

    /** Đường idempotency: cùng {@code (user, request_id)} phải trả lại đúng bản cũ (TC16). */
    Optional<DiaryEntryEntity> findByUserIdAndRequestId(String userId, String requestId);

    Optional<DiaryEntryEntity> findByIdAndUserId(String id, String userId);

    /** Nhật ký của một ngày, theo thứ tự bữa rồi thứ tự ghi — đúng thứ tự hiển thị. */
    List<DiaryEntryEntity> findByUserIdAndLocalDateOrderByMealTypeAscCreatedAtAsc(
            String userId, LocalDate localDate);
}
