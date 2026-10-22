package vn.nutrimom.payment.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.payment.domain.PaymentOrderEntity;

/**
 * Lịch sử giao dịch dùng {@link JpaSpecificationExecutor} chứ không phải một {@code @Query} với
 * tham số nullable: xem {@code PaymentService.historyFilter}.
 */
@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrderEntity, String>,
        JpaSpecificationExecutor<PaymentOrderEntity> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentOrderEntity p where p.orderCode=:code")
    Optional<PaymentOrderEntity> findByOrderCodeForUpdate(@Param("code") Long code);

    /**
     * Row-level security: lọc kèm chủ sở hữu <strong>trong chính truy vấn</strong>, không sở hữu
     * thì 404 (xem {@code docs/security.md} mục 2).
     *
     * <p>Khác {@link #findByOrderCodeForUpdate} ở chỗ khoá chỉ được lấy sau khi đã khớp chủ sở hữu.
     * Khoá trước rồi mới so owner ở service nghĩa là chỉ cần đoán {@code orderCode} là giữ được
     * row-lock trên đơn của người khác cho tới hết transaction.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentOrderEntity p where p.orderCode=:code and p.userId=:userId")
    Optional<PaymentOrderEntity> findByOrderCodeAndUserIdForUpdate(@Param("code") Long code,
                                                                   @Param("userId") String userId);

    Optional<PaymentOrderEntity> findByOrderCode(Long orderCode);

    List<PaymentOrderEntity> findAllByUserIdOrderByCreatedAtDesc(String userId);
    List<PaymentOrderEntity> findTop3ByUserIdOrderByCreatedAtDesc(String userId);

    Optional<PaymentOrderEntity> findByIdAndUserId(String id, String userId);
}
