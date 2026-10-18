package vn.nutrimom.nutrition.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Đọc kế hoạch để <strong>sửa</strong> (nhánh 6), khóa dòng cho tới hết transaction.
     *
     * <p>So sánh {@code expected_plan_version} rồi ghi số mới chỉ đúng khi không ai chen vào giữa
     * hai bước đó. Không có khóa này thì hai yêu cầu song song cùng đọc phiên bản 4, cả hai thấy
     * khớp, cả hai ghi 5 — và một trong hai thao tác biến mất mà người dùng nhận
     * <strong>thành công</strong>. Đó đúng là tình huống "hai thiết bị" mà
     * {@code VERSION_CONFLICT} sinh ra để chặn, nên nó phải chặn thật chứ không chỉ trong trường
     * hợp hai yêu cầu cách nhau đủ xa.</p>
     *
     * <p>Khóa ở mức dòng {@code meal_plans}: mọi thao tác sửa đều đi qua đúng một kế hoạch, nên hai
     * người dùng khác nhau không bao giờ chờ nhau.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MealPlanEntity p where p.id = :id and p.userId = :userId")
    Optional<MealPlanEntity> findForUpdate(@Param("id") String id,
                                           @Param("userId") String userId);
}
