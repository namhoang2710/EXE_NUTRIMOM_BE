package vn.nutrimom.payment.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.config.PaymentProperties;
import vn.nutrimom.payment.config.PayOSProperties;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PaymentSource;
import vn.nutrimom.payment.repository.PaymentOrderRepository;
import vn.payos.PayOS;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;

/**
 * Đối soát một đơn thanh toán với PayOS và ghi lại kết luận. Đây là đường ghi dùng chung của job
 * nền, của endpoint chi tiết và của webhook khi webhook gặp đơn đã kết sổ.
 *
 * <p>Ba pha tách rời một cách cố ý: <em>giành việc</em> bằng một câu update nguyên tử, <em>hỏi
 * cổng</em> khi không có transaction nào đang mở, rồi <em>ghi</em> trong một transaction ngắn có
 * khoá dòng. Giữ transaction suốt lời gọi HTTP là cách chắc chắn nhất để một sự cố bên PayOS biến
 * thành một connection pool cạn sạch ở phía ta.</p>
 *
 * <p>Tính exactly-once <strong>không</strong> đến từ câu claim. Claim chỉ giảm số lần gọi trùng;
 * thứ bảo đảm đúng một lần kích hoạt là lần kiểm trạng thái lại dưới khoá dòng ngay trước khi ghi.
 * Webhook và job có thể cùng về đích, chỉ một bên thấy đơn còn PENDING.</p>
 *
 * <p>Là bean riêng chứ không phải method của {@code PaymentService}: một method
 * {@code @Transactional} gọi từ trong cùng bean không đi qua proxy, và lời gọi HTTP sẽ âm thầm nằm
 * lại trong transaction của bên gọi.</p>
 */
@Service
public class PaymentReconciliationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationDispatcher.class);

    private static final String EXPIRY_CANCEL_REASON = "Đơn hàng quá hạn thanh toán";

    private final PayOS payOS;
    private final PayOSProperties payOSProperties;
    private final PaymentProperties properties;
    private final PaymentOrderRepository orders;
    private final PayOSStatusMapper mapper;
    private final SubscriptionActivationService activation;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public PaymentReconciliationDispatcher(PayOS payOS,
                                           PayOSProperties payOSProperties,
                                           PaymentProperties properties,
                                           PaymentOrderRepository orders,
                                           PayOSStatusMapper mapper,
                                           SubscriptionActivationService activation,
                                           PlatformTransactionManager transactionManager,
                                           Clock clock) {
        this.payOS = payOS;
        this.payOSProperties = payOSProperties;
        this.properties = properties;
        this.orders = orders;
        this.mapper = mapper;
        this.activation = activation;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Đối soát một đơn còn đang chờ. Bỏ qua im lặng khi đơn vừa bị người khác hỏi trong khoảng
     * cooldown - đó là trường hợp bình thường, không phải lỗi.
     *
     * <p>Ngoại lệ từ PayOS được để lan ra ngoài: bên gọi (job) cần biết để lùi cả vòng quét lại,
     * chứ không phải thử tiếp 49 đơn còn lại trên một cổng đang từ chối.</p>
     */
    public void reconcilePending(Long orderCode) {
        if (!payOSProperties.isConfigured()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Boolean claimed = requiresNew.execute(status ->
                orders.claimForReconciliation(orderCode, now, now.minus(properties.getReconcileCooldown())) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            return;
        }

        PaymentOrderEntity snapshot = snapshot(orderCode);
        if (snapshot == null || snapshot.getStatus() != PaymentOrderStatus.PENDING
                || snapshot.getSource() != PaymentSource.REAL) {
            return;
        }

        PaymentLink link = payOS.paymentRequests().get(orderCode);
        PaymentSyncOutcome outcome = mapper.map(link, snapshot);
        if (outcome == PaymentSyncOutcome.UNCHANGED) {
            outcome = closeAtGatewayIfOverdue(link, snapshot, now);
        }
        apply(orderCode, outcome);
    }

    /**
     * Đọc lại một đơn đã kết sổ (CANCELLED/EXPIRED) từ PayOS và kích hoạt nếu hoá ra tiền đã về.
     *
     * <p>Ca này không hiếm như nó nghe: với hạn checkout 5 phút, một lần chuyển khoản bấm sát giờ
     * hoàn toàn có thể về sau khi ta đã đóng sổ. Quyết định ở đây là <em>trả hàng</em>, vì bên kia
     * đã trả tiền thật; giữ nguyên trạng thái cuối chỉ đẩy phần việc sang cho người hoàn tiền thủ
     * công, chậm hơn và dễ quên hơn.</p>
     *
     * <p>Không có claim và không có cooldown: lối vào duy nhất là một webhook đã qua kiểm chữ ký,
     * nên tần suất do PayOS quyết chứ không do client. Bản thân webhook vẫn không được phép ghi -
     * nó chỉ là tín hiệu, còn nguồn sự thật là {@code paymentRequests().get(orderCode)}.</p>
     */
    public void reconcileSettled(Long orderCode) {
        if (!payOSProperties.isConfigured()) {
            return;
        }
        PaymentOrderEntity snapshot = snapshot(orderCode);
        if (snapshot == null || snapshot.getSource() != PaymentSource.REAL
                || snapshot.getStatus() == PaymentOrderStatus.PAID
                || snapshot.getStatus() == PaymentOrderStatus.PENDING) {
            return;
        }
        PaymentSyncOutcome outcome = mapper.map(payOS.paymentRequests().get(orderCode), snapshot);
        if (outcome != PaymentSyncOutcome.PAID) {
            return;
        }
        log.warn("Late payment landed on a settled order orderCode={} previousStatus={}",
                orderCode, snapshot.getStatus());
        apply(orderCode, PaymentSyncOutcome.PAID);
    }

    /**
     * Hạn của ta là hạn có hiệu lực; {@code expiredAt} gửi sang PayOS chỉ là lớp chặn thứ hai.
     *
     * <p>Quá hạn mà cổng vẫn báo PENDING thì phải gọi cancel trước khi đánh EXPIRED. Đánh dấu suông
     * ở DB sẽ để lại một link còn thanh toán được trong khi phía ta đã coi đơn là chết - đúng cái
     * cảnh tiền về mà không biết gắn vào đâu. Với đơn tạo trước V67 (link không có hạn) đây là cách
     * duy nhất để chúng ngừng nhận tiền.</p>
     *
     * <p>PROCESSING và UNDERPAID không đi qua đây: chúng đã có tiền trên đường hoặc trong tài
     * khoản, hủy link là tự tay cắt mất đường đối chiếu.</p>
     */
    private PaymentSyncOutcome closeAtGatewayIfOverdue(PaymentLink link,
                                                       PaymentOrderEntity order,
                                                       OffsetDateTime now) {
        if (link == null || link.getStatus() != PaymentLinkStatus.PENDING || !isOverdue(order, now)) {
            return PaymentSyncOutcome.UNCHANGED;
        }
        try {
            payOS.paymentRequests().cancel(order.getOrderCode(), EXPIRY_CANCEL_REASON);
            return PaymentSyncOutcome.EXPIRED;
        } catch (RuntimeException failure) {
            log.warn("PayOS refused to cancel an overdue link orderCode={} failureType={}",
                    order.getOrderCode(), failure.getClass().getSimpleName());
            return mapper.map(payOS.paymentRequests().get(order.getOrderCode()), order);
        }
    }

    /** Quá hạn kèm thời gian ân hạn. Đơn trước V67 không có {@code expiresAt} nên suy từ lúc tạo. */
    private boolean isOverdue(PaymentOrderEntity order, OffsetDateTime now) {
        OffsetDateTime deadline = order.getExpiresAt() != null
                ? order.getExpiresAt()
                : order.getCreatedAt().plus(properties.getCheckoutTtl());
        return deadline.isBefore(now.minus(properties.getReconcileGrace()));
    }

    private PaymentOrderEntity snapshot(Long orderCode) {
        return requiresNew.execute(status -> orders.findByOrderCode(orderCode).orElse(null));
    }

    /**
     * Ghi kết luận dưới khoá dòng, sau khi kiểm lại trạng thái.
     *
     * <p>Luật chuyển trạng thái không đối xứng, và đó là chủ ý: PAID được phép đè lên CANCELLED hay
     * EXPIRED vì nó dựa trên một khoản tiền có thật mà cổng xác nhận; chiều ngược lại thì không,
     * một đơn đã trả tiền không bao giờ bị hết hạn.</p>
     */
    private void apply(Long orderCode, PaymentSyncOutcome outcome) {
        if (outcome == PaymentSyncOutcome.UNCHANGED) {
            return;
        }
        requiresNew.executeWithoutResult(status -> {
            PaymentOrderEntity order = orders.findByOrderCodeForUpdate(orderCode).orElse(null);
            if (order == null || order.getStatus() == PaymentOrderStatus.PAID) {
                return;
            }
            if (outcome == PaymentSyncOutcome.PAID) {
                activation.activate(order);
                log.info("Reconciliation activated orderCode={}", orderCode);
                return;
            }
            if (order.getStatus() != PaymentOrderStatus.PENDING) {
                return;
            }
            order.setStatus(outcome == PaymentSyncOutcome.CANCELLED
                    ? PaymentOrderStatus.CANCELLED
                    : PaymentOrderStatus.EXPIRED);
            orders.save(order);
            log.info("Reconciliation settled orderCode={} status={}", orderCode, order.getStatus());
        });
    }
}
