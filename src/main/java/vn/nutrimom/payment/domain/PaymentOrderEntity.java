package vn.nutrimom.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "payment_orders", schema = "app")
public class PaymentOrderEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "order_code", nullable = false, unique = true)
    private Long orderCode;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_tier", nullable = false, length = 20)
    private PlanTier planTier;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentOrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private PaymentSource source = PaymentSource.LEGACY_UNKNOWN;

    @Column(name = "payos_payment_link_id", length = 100)
    private String payosPaymentLinkId;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    @Column(name = "qr_code", columnDefinition = "TEXT")
    private String qrCode;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    /**
     * Hạn hiệu lực của link checkout, chính là giá trị đã gửi sang PayOS ở {@code expiredAt}.
     *
     * <p>Null với đơn tạo trước V67: lúc đó link gửi đi không kèm hạn nên PayOS không bao giờ tự
     * đóng nó. Job đối soát coi null là {@code createdAt + checkout-ttl} và phải gọi cancel lên
     * cổng trước khi đánh EXPIRED.</p>
     */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /**
     * Lần gần nhất có ai đó hỏi PayOS về đơn này.
     *
     * <p>Vừa là cờ giành việc giữa nhiều instance job, vừa là cooldown chặn FE poll endpoint chi
     * tiết dội thẳng sang cổng. Chỉ được ghi bằng câu update bulk của repository: ghi qua entity sẽ
     * bump {@code version} và biến một lần PATCH hợp lệ của client thành VERSION_CONFLICT.</p>
     */
    @Column(name = "reconcile_attempt_at")
    private OffsetDateTime reconcileAttemptAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public PaymentOrderEntity() {
    }

    public PaymentOrderEntity(Long orderCode, String userId, PlanTier planTier, int amount, String description) {
        this.id = UUID.randomUUID().toString();
        this.orderCode = orderCode;
        this.userId = userId;
        this.planTier = planTier;
        this.amount = amount;
        this.currency = "VND";
        this.status = PaymentOrderStatus.PENDING;
        this.description = description;
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (currency == null) {
            currency = "VND";
        }
        if (status == null) {
            status = PaymentOrderStatus.PENDING;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() {
        return id;
    }

    public PaymentSource getSource() { return source; }
    public void setSource(PaymentSource source) { this.source = source; }

    public void setId(String id) {
        this.id = id;
    }

    public Long getOrderCode() {
        return orderCode;
    }

    public void setOrderCode(Long orderCode) {
        this.orderCode = orderCode;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public PlanTier getPlanTier() {
        return planTier;
    }

    public void setPlanTier(PlanTier planTier) {
        this.planTier = planTier;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public PaymentOrderStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentOrderStatus status) {
        this.status = status;
    }

    public String getPayosPaymentLinkId() {
        return payosPaymentLinkId;
    }

    public void setPayosPaymentLinkId(String payosPaymentLinkId) {
        this.payosPaymentLinkId = payosPaymentLinkId;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public void setCheckoutUrl(String checkoutUrl) {
        this.checkoutUrl = checkoutUrl;
    }

    public String getQrCode() {
        return qrCode;
    }

    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(OffsetDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** Chỉ có getter: cột này do câu update bulk của repository ghi, không bao giờ qua entity. */
    public OffsetDateTime getReconcileAttemptAt() {
        return reconcileAttemptAt;
    }

    public long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
