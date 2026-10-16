package vn.nutrimom.nutrition.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.MealPlanEntity;

public interface MealPlanRepository extends JpaRepository<MealPlanEntity, String> {

    /**
     * Đọc kế hoạch <strong>của đúng người gọi</strong>.
     *
     * <p>Chủ sở hữu nằm trong điều kiện truy vấn chứ không phải một câu {@code if} sau khi đã đọc
     * xong: tài khoản khác hỏi đúng {@code plan_id} thì không có dòng nào trả về, nên không có
     * đường nào để quên mất bước kiểm (TC20, BR19).</p>
     *
     * <p>Chỉ nạp sẵn {@code slots}; {@code slots.items} để lazy. Nạp cả hai trong một câu là hai
     * {@code List} cùng lúc và Hibernate từ chối ({@code MultipleBagFetchException}). Một ngày có
     * tối đa sáu bữa nên vài câu truy vấn con không đáng để đổi kiểu dữ liệu của quan hệ.</p>
     */
    @EntityGraph(attributePaths = "slots")
    Optional<MealPlanEntity> findByIdAndUserId(String id, String userId);
}
