package vn.nutrimom.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.payment.service.PayOSStatusMapper;
import vn.nutrimom.payment.service.PaymentSyncOutcome;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;

/**
 * Bảng ánh xạ trạng thái PayOS là chốt chặn của cả nhánh: mỗi ô sai ở đây là một đơn bị giết nhầm
 * hoặc một khoản tiền không được ghi nhận. Bài này khoá từng hằng của SDK, kể cả những hằng mà câu
 * trả lời đúng là "không làm gì".
 */
class PayOSStatusMapperTest {

    private final PayOSStatusMapper mapper = new PayOSStatusMapper();

    private static PaymentOrderEntity order() {
        return new PaymentOrderEntity(123456L, "user-1", PlanTier.PLAN_99K, 99000, "NutriMom PLAN_99K");
    }

    private static PaymentLink link(PaymentLinkStatus status, long amount, long remaining) {
        return PaymentLink.builder()
                .id("link-1")
                .orderCode(123456L)
                .amount(amount)
                .amountPaid(amount - remaining)
                .amountRemaining(remaining)
                .status(status)
                .createdAt("2026-10-22T00:00:00+07:00")
                .transactions(List.of())
                .build();
    }

    @Test
    void paidWithMatchingAmountActivates() {
        assertEquals(PaymentSyncOutcome.PAID,
                mapper.map(link(PaymentLinkStatus.PAID, 99000L, 0L), order()));
    }

    @Test
    void paidWithWrongAmountChangesNothing() {
        assertEquals(PaymentSyncOutcome.UNCHANGED,
                mapper.map(link(PaymentLinkStatus.PAID, 9000L, 0L), order()));
    }

    @Test
    void paidWithOutstandingBalanceChangesNothing() {
        assertEquals(PaymentSyncOutcome.UNCHANGED,
                mapper.map(link(PaymentLinkStatus.PAID, 99000L, 1000L), order()));
    }

    @Test
    void cancelledAndExpiredMapStraightThrough() {
        assertEquals(PaymentSyncOutcome.CANCELLED,
                mapper.map(link(PaymentLinkStatus.CANCELLED, 99000L, 99000L), order()));
        assertEquals(PaymentSyncOutcome.EXPIRED,
                mapper.map(link(PaymentLinkStatus.EXPIRED, 99000L, 99000L), order()));
    }

    /**
     * Bốn hằng này là lý do bảng ánh xạ tồn tại. UNDERPAID đã có tiền thật trong tài khoản và
     * PROCESSING thì tiền đang trên đường: huỷ đơn ở hai ca đó là tự tay cắt mất đường đối chiếu.
     * FAILED chỉ nói một lần chuyển khoản hỏng, link vẫn trả được.
     */
    @ParameterizedTest
    @EnumSource(value = PaymentLinkStatus.class,
            names = {"PENDING", "PROCESSING", "UNDERPAID", "FAILED"})
    void inFlightStatusesNeverTouchTheOrder(PaymentLinkStatus status) {
        assertEquals(PaymentSyncOutcome.UNCHANGED, mapper.map(link(status, 99000L, 99000L), order()));
    }

    @Test
    void missingLinkOrStatusChangesNothing() {
        assertEquals(PaymentSyncOutcome.UNCHANGED, mapper.map(null, order()));
    }
}
