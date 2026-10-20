package vn.nutrimom.payment.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PlanTier;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrderEntity, String> {
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

    /**
     * Lịch sử giao dịch của một người, các bộ lọc đều tuỳ chọn (null = không lọc).
     *
     * <p>Một truy vấn với tham số nullable thay vì bốn method derived cho tổ hợp bộ lọc. Sắp theo
     * {@code created_at DESC} vì đó là thứ index {@code ix_payment_orders_user_created} (V31) phục
     * vụ; đừng đổi sort nếu không muốn mất seek.</p>
     */
    @Query("""
            select p from PaymentOrderEntity p
            where p.userId = :userId
              and (:status is null or p.status = :status)
              and (:planTier is null or p.planTier = :planTier)
              and (:from is null or p.createdAt >= :from)
              and (:to is null or p.createdAt < :to)
            order by p.createdAt desc, p.id desc
            """)
    Page<PaymentOrderEntity> findHistory(@Param("userId") String userId,
                                         @Param("status") PaymentOrderStatus status,
                                         @Param("planTier") PlanTier planTier,
                                         @Param("from") OffsetDateTime from,
                                         @Param("to") OffsetDateTime to,
                                         Pageable pageable);
}
