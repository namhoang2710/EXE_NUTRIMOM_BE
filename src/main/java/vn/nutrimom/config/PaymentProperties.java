package vn.nutrimom.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chính sách vòng đời đơn thanh toán dưới prefix {@code app.payment}.
 *
 * <pre>
 * app:
 *   payment:
 *     checkout-ttl: PT5M             # NUTRIMOM_PAYMENT_CHECKOUT_TTL
 *     reconcile-job-enabled: true    # NUTRIMOM_PAYMENT_RECONCILE_ENABLED
 *     reconcile-job-interval: PT5M   # NUTRIMOM_PAYMENT_RECONCILE_INTERVAL (ISO-8601 duration)
 *     reconcile-batch-size: 50
 *     reconcile-grace: PT2M
 *     reconcile-cooldown: PT5M
 * </pre>
 *
 * <p>Cố ý tách khỏi {@code PayOSProperties}: bên đó chỉ giữ credential và URL của cổng, còn đây là
 * chính sách nghiệp vụ. Trộn hai thứ vào một prefix thì mỗi lần muốn đổi thời hạn giữ link lại phải
 * đụng vào khối cấu hình chứa khoá bí mật.</p>
 *
 * <p>{@code reconcileJobEnabled=false} tắt hẳn scheduler (xem {@link PaymentSchedulingConfig});
 * profile test đặt false để không có thread nền gọi PayOS song song với integration test.</p>
 */
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    /**
     * Link checkout sống được bao lâu. Gửi sang PayOS dưới dạng {@code expiredAt} và lưu lại ở
     * {@code payment_orders.expires_at} để FE đếm ngược và job biết đơn nào đã quá hạn.
     */
    private Duration checkoutTtl = Duration.ofMinutes(5);

    private boolean reconcileJobEnabled = true;

    /** Khoảng cách giữa hai lượt quét, dạng ISO-8601. Đọc lại ở {@code @Scheduled} qua placeholder. */
    private String reconcileJobInterval = "PT5M";

    /** Trần số đơn một lượt quét xử lý; mỗi đơn là một lời gọi PayOS nên đây cũng là trần lưu lượng. */
    private int reconcileBatchSize = 50;

    /**
     * Chờ thêm bao lâu sau hạn rồi mới đối soát.
     *
     * <p>Đồng hồ của ta và của PayOS không trùng nhau tuyệt đối, và một lần chuyển khoản bấm đúng
     * giây cuối vẫn cần thời gian để cổng ghi nhận. Đối soát ngay tại mốc hạn nghĩa là thỉnh thoảng
     * hỏi PayOS trước khi chính PayOS kịp kết luận.</p>
     */
    private Duration reconcileGrace = Duration.ofMinutes(2);

    /**
     * Khoảng nghỉ tối thiểu giữa hai lần hỏi PayOS về cùng một đơn.
     *
     * <p>Vừa là giới hạn retry của job, vừa là thứ chặn FE poll {@code GET /orders/{orderCode}}
     * dội thẳng sang cổng: cả hai đường đều giành việc qua {@code reconcile_attempt_at}.</p>
     */
    private Duration reconcileCooldown = Duration.ofMinutes(5);

    public Duration getCheckoutTtl() { return checkoutTtl; }
    public void setCheckoutTtl(Duration value) { checkoutTtl = value; }
    public boolean isReconcileJobEnabled() { return reconcileJobEnabled; }
    public void setReconcileJobEnabled(boolean value) { reconcileJobEnabled = value; }
    public String getReconcileJobInterval() { return reconcileJobInterval; }
    public void setReconcileJobInterval(String value) { reconcileJobInterval = value; }
    public int getReconcileBatchSize() { return reconcileBatchSize; }
    public void setReconcileBatchSize(int value) { reconcileBatchSize = value; }
    public Duration getReconcileGrace() { return reconcileGrace; }
    public void setReconcileGrace(Duration value) { reconcileGrace = value; }
    public Duration getReconcileCooldown() { return reconcileCooldown; }
    public void setReconcileCooldown(Duration value) { reconcileCooldown = value; }
}
