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

    // --- Vòng đời đơn thanh toán (V67) ---
    // Các method dưới đây cố ý viết tên kiểu đầy đủ thay vì thêm import: khối import của file này
    // đang là chỗ đụng nhau giữa nhiều nhánh thanh toán đang mở.

    /** Bản không khoá của {@link #findByOrderCodeAndUserIdForUpdate}, cho đường chỉ đọc. */
    Optional<PaymentOrderEntity> findByOrderCodeAndUserId(Long orderCode, String userId);

    /**
     * Đơn còn đang chờ trả tiền của một người, mới nhất trước.
     *
     * <p>Lọc hạn ở tầng Java chứ không nhét vào đây: hạn của đơn cũ (trước V67) là
     * {@code createdAt + checkout-ttl}, mà TTL là cấu hình runtime nên không thuộc về câu truy vấn.
     * Một người chỉ có vài đơn chờ nên không có gì để tối ưu.</p>
     */
    @Query("""
            select p from PaymentOrderEntity p
            where p.userId = :userId
              and p.status = vn.nutrimom.payment.domain.PaymentOrderStatus.PENDING
              and p.source = :source
            order by p.createdAt desc
            """)
    List<PaymentOrderEntity> findPendingByUser(@Param("userId") String userId,
                                               @Param("source") vn.nutrimom.payment.domain.PaymentSource source);

    /**
     * Hàng đợi của job đối soát: đơn thật, còn PENDING, đã quá hạn kèm thời gian ân hạn, và chưa bị
     * hỏi trong khoảng cooldown.
     *
     * <p>Hai nhánh hạn vì bảng có hai thế hệ dòng: {@code expires_at} cho đơn tạo từ V67 trở đi, và
     * {@code created_at} cho đơn cũ hơn vốn không có hạn nào cả. Trả về <strong>orderCode</strong>
     * chứ không phải entity: mỗi đơn sau đó được giành việc và xử lý trong transaction riêng, nên
     * entity load ở đây sẽ mang {@code version} cũ ngay khi câu claim chạy.</p>
     *
     * <p>Chỉ {@code source = REAL}: link DEMO là {@code MOCK-…} nên gọi PayOS sẽ 404, còn
     * {@code LEGACY_UNKNOWN} có thể thuộc về một writer khác trên DB dùng chung.</p>
     */
    @Query("""
            select p.orderCode from PaymentOrderEntity p
            where p.status = vn.nutrimom.payment.domain.PaymentOrderStatus.PENDING
              and p.source = vn.nutrimom.payment.domain.PaymentSource.REAL
              and ((p.expiresAt is not null and p.expiresAt < :expiryCutoff)
                   or (p.expiresAt is null and p.createdAt < :legacyCutoff))
              and (p.reconcileAttemptAt is null or p.reconcileAttemptAt < :cooldownCutoff)
            order by p.createdAt asc
            """)
    List<Long> findDueForReconciliation(@Param("expiryCutoff") java.time.OffsetDateTime expiryCutoff,
                                        @Param("legacyCutoff") java.time.OffsetDateTime legacyCutoff,
                                        @Param("cooldownCutoff") java.time.OffsetDateTime cooldownCutoff,
                                        org.springframework.data.domain.Pageable pageable);

    /**
     * Giành quyền hỏi PayOS về một đơn. Trả 1 nếu giành được, 0 nếu người khác vừa hỏi xong trong
     * khoảng cooldown — đây là thứ chặn cả hai instance job lẫn FE poll dội lên cổng.
     *
     * <p>Cố ý <strong>không</strong> bump {@code version} và không chạy {@code @PreUpdate}: đây là
     * cờ vận hành, không phải thay đổi dữ liệu người dùng, nên nó không được biến một lần PATCH hợp
     * lệ của client thành {@code VERSION_CONFLICT}, và webhook chạy song song cũng không bị đá ra.
     * Cùng lý do đó, câu lệnh chỉ liệt kê đúng một cột: {@code trg_payment_source_legacy_writer}
     * (V45) nổ theo danh sách cột của UPDATE chứ không theo giá trị thay đổi, nên chạm vào
     * {@code source} hay {@code payos_payment_link_id} ở đây sẽ đánh thức nó.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("""
            update PaymentOrderEntity p
            set p.reconcileAttemptAt = :now
            where p.orderCode = :code
              and p.status = vn.nutrimom.payment.domain.PaymentOrderStatus.PENDING
              and (p.reconcileAttemptAt is null or p.reconcileAttemptAt < :cooldownCutoff)
            """)
    int claimForReconciliation(@Param("code") Long code,
                               @Param("now") java.time.OffsetDateTime now,
                               @Param("cooldownCutoff") java.time.OffsetDateTime cooldownCutoff);
}
