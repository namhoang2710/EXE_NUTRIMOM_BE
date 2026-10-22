package vn.nutrimom.payment.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.nutrimom.config.PaymentProperties;
import vn.nutrimom.payment.repository.PaymentOrderRepository;

/**
 * Quét định kỳ các đơn chờ đã quá hạn và giao cho {@link PaymentReconciliationDispatcher} đối soát.
 *
 * <p>Bean này chỉ tìm việc, không ghi gì và không gọi PayOS: mỗi đơn được giành việc và xử lý trong
 * transaction riêng của dispatcher.</p>
 *
 * <p>Backoff đặt ở cả vòng quét chứ không ở từng dòng. Lỗi hay gặp nhất của cổng là giới hạn lưu
 * lượng, mà giới hạn đó tính trên toàn tài khoản: thử tiếp 49 đơn còn lại sau khi đơn đầu bị từ
 * chối chỉ làm cổng nặng thêm và đẩy lần phục hồi ra xa hơn. Khuôn lấy từ
 * {@code AdminNotificationSchedulingConfig}.</p>
 *
 * <p>Tắt bằng {@code NUTRIMOM_PAYMENT_RECONCILE_ENABLED=false}; profile test tắt sẵn để không có
 * thread nền nào gọi PayOS song song với integration test.</p>
 */
@Component
@ConditionalOnProperty(name = "app.payment.reconcile-job-enabled", havingValue = "true")
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private static final int MAX_BACKOFF_STEPS = 4;

    private final PaymentOrderRepository orders;
    private final PaymentReconciliationDispatcher dispatcher;
    private final PaymentProperties properties;
    private final Clock clock;

    private int failures;
    private long retryAfter;

    public PaymentReconciliationJob(PaymentOrderRepository orders,
                                    PaymentReconciliationDispatcher dispatcher,
                                    PaymentProperties properties,
                                    Clock clock) {
        this.orders = orders;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * {@code fixedDelay} chứ không phải {@code fixedRate}: một lượt quét chậm (50 lời gọi HTTP sang
     * PayOS) không được xếp hàng chồng lên lượt sau.
     */
    @Scheduled(
            initialDelayString = "${app.payment.reconcile-job-interval:PT5M}",
            fixedDelayString = "${app.payment.reconcile-job-interval:PT5M}")
    public void scan() {
        if (retryAfter != 0 && System.nanoTime() - retryAfter < 0) {
            return;
        }
        try {
            int handled = runOnce();
            if (handled > 0) {
                log.debug("Payment reconciliation handled {} overdue order(s)", handled);
            }
            failures = 0;
            retryAfter = 0;
        } catch (RuntimeException failure) {
            failures = Math.min(failures + 1, MAX_BACKOFF_STEPS);
            retryAfter = System.nanoTime() + Math.min(60, 5L << failures) * 1_000_000_000L;
            log.warn("Payment reconciliation deferred failureType={}", failure.getClass().getSimpleName());
        }
    }

    /**
     * Một lượt quét, tách khỏi phần backoff để test gọi thẳng với {@code Clock} cố định.
     *
     * <p>Hai mốc cắt vì bảng có hai thế hệ dòng: đơn từ V67 trở đi so theo {@code expires_at}, đơn
     * cũ hơn không có hạn nào nên suy từ {@code created_at + checkout-ttl}. Cả hai đều trừ thêm
     * thời gian ân hạn.</p>
     */
    public int runOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> due = orders.findDueForReconciliation(
                now.minus(properties.getReconcileGrace()),
                now.minus(properties.getCheckoutTtl()).minus(properties.getReconcileGrace()),
                now.minus(properties.getReconcileCooldown()),
                PageRequest.of(0, properties.getReconcileBatchSize()));
        for (Long orderCode : due) {
            dispatcher.reconcilePending(orderCode);
        }
        return due.size();
    }
}
