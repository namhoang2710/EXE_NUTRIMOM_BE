package vn.nutrimom.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;

/**
 * Ánh xạ trạng thái link của PayOS sang kết luận nội bộ. Đây là nơi duy nhất được phép quyết định
 * một đơn có chuyển trạng thái hay không.
 *
 * <table>
 *   <caption>Bảng ánh xạ</caption>
 *   <tr><th>PayOS</th><th>Nội bộ</th></tr>
 *   <tr><td>PAID</td><td>PAID, chỉ khi khớp số tiền và không còn dư nợ</td></tr>
 *   <tr><td>CANCELLED</td><td>CANCELLED</td></tr>
 *   <tr><td>EXPIRED</td><td>EXPIRED</td></tr>
 *   <tr><td>PENDING, PROCESSING</td><td>không đổi — tiền có thể đang trên đường</td></tr>
 *   <tr><td>UNDERPAID</td><td>không đổi — tiền đã vào, chỉ thiếu</td></tr>
 *   <tr><td>FAILED</td><td>không đổi — một lần chuyển hỏng, link vẫn trả được</td></tr>
 * </table>
 *
 * <p>Dùng enum {@link PaymentLinkStatus} có kiểu chứ không so chuỗi: {@code "PAID".equalsIgnoreCase}
 * trên một {@code String.valueOf(...)} âm thầm cho false ở mọi hằng viết khác đi, và không ai biết
 * cho tới khi có người trả tiền mà đơn vẫn PENDING.</p>
 *
 * <p>UNDERPAID tuyệt đối không được hủy. Người dùng đã chuyển một phần tiền; hủy link là xóa mất
 * đường đối chiếu duy nhất giữa khoản tiền đó và đơn hàng. Ca này cần người xử lý, không phải code
 * (xem {@code docs/PAYMENT_API.md}, mục vận hành).</p>
 */
@Component
public class PayOSStatusMapper {

    private static final Logger log = LoggerFactory.getLogger(PayOSStatusMapper.class);

    public PaymentSyncOutcome map(PaymentLink link, PaymentOrderEntity order) {
        if (link == null || link.getStatus() == null) {
            log.warn("PayOS returned no status orderCode={}", order.getOrderCode());
            return PaymentSyncOutcome.UNCHANGED;
        }
        return switch (link.getStatus()) {
            case PAID -> paidOutcome(link, order);
            case CANCELLED -> PaymentSyncOutcome.CANCELLED;
            case EXPIRED -> PaymentSyncOutcome.EXPIRED;
            case PENDING, PROCESSING, UNDERPAID, FAILED -> PaymentSyncOutcome.UNCHANGED;
            // Nhánh này giữ lại dù switch đã phủ hết hằng hiện có: SDK thêm hằng mới thì ca đó rơi
            // vào đây và được log, thay vì ném MatchException ngay giữa đường đối soát.
            default -> {
                log.warn("Unknown PayOS status {} orderCode={}", link.getStatus(), order.getOrderCode());
                yield PaymentSyncOutcome.UNCHANGED;
            }
        };
    }

    /**
     * PAID của cổng chỉ thành PAID của ta khi số tiền khớp và không còn dư nợ.
     *
     * <p>Lệch tiền ở đây không phải lỗi mạng mà là dấu hiệu đơn bị gắn nhầm hoặc bị can thiệp, nên
     * nó dừng lại ở chỗ người đọc log nhìn thấy chứ không âm thầm kích hoạt gói.</p>
     */
    private PaymentSyncOutcome paidOutcome(PaymentLink link, PaymentOrderEntity order) {
        Long amount = link.getAmount();
        Long remaining = link.getAmountRemaining();
        if (amount == null || amount.longValue() != order.getAmount()) {
            log.warn("PayOS PAID with mismatched amount orderCode={}", order.getOrderCode());
            return PaymentSyncOutcome.UNCHANGED;
        }
        if (remaining == null || remaining.longValue() != 0L) {
            log.warn("PayOS PAID with outstanding balance orderCode={}", order.getOrderCode());
            return PaymentSyncOutcome.UNCHANGED;
        }
        return PaymentSyncOutcome.PAID;
    }
}
