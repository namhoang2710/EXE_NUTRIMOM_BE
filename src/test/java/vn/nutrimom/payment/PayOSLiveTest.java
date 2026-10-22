package vn.nutrimom.payment;

import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import vn.payos.PayOS;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLinkItem;

@Disabled("Chạy thủ công khi cần kiểm tra kết nối PayOS live")
class PayOSLiveTest {

    @Test
    void testCreateLivePaymentLink() {
        String clientId = "1b6d89f1-5fc9-4f04-a74f-cf1dbe76d174";
        String apiKey = "455a2421-afda-4edd-8022-4a6ce22617ed";
        String checksumKey = "16aba0fcff5efb4ffe36a1e93286b1de335ae6f1ca40c25aaf52c881c1967401";

        PayOS payOS = new PayOS(clientId, apiKey, checksumKey);

        long orderCode = (System.currentTimeMillis() / 1000L) % 1_000_000L + 100000L;
        PaymentLinkItem item = PaymentLinkItem.builder()
                .name("Gói Dịch Vụ Test")
                .quantity(1)
                .price(2000L)
                .build();

        CreatePaymentLinkRequest request = CreatePaymentLinkRequest.builder()
                .orderCode(orderCode)
                .amount(2000L)
                .description("NutriMom Test")
                .returnUrl("http://localhost:5173/payment/success")
                .cancelUrl("http://localhost:5173/payment/cancel")
                .items(List.of(item))
                .build();

        try {
            CreatePaymentLinkResponse response = payOS.paymentRequests().create(request);
            System.out.println("PAYOS_LIVE_SUCCESS: Checkout URL = " + response.getCheckoutUrl());
            System.out.println("PAYOS_LIVE_SUCCESS: QR Code = " + response.getQrCode());
        } catch (Exception e) {
            System.err.println("PAYOS_LIVE_ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Hai thứ chỉ môi trường thật trả lời được, và cả hai đều hỏng im lặng nếu đoán sai.
     *
     * <ol>
     *   <li>Đơn vị của {@code expiredAt}. Tài liệu PayOS khai Unix timestamp kiểu {@code Int32} và
     *       ví dụ của SDK dùng giây, nên bài này gửi giây rồi đối chiếu
     *       {@code response.getExpiredAt()} với đúng con số đã gửi.</li>
     *   <li>Mức hạn tối thiểu cổng chấp nhận. Tài liệu không nói. Nếu PayOS đặt sàn cao hơn 5 phút
     *       thì lời gọi này ném lỗi, và cách sửa là nâng {@code app.payment.checkout-ttl} chứ không
     *       phải sửa code.</li>
     * </ol>
     */
    @Test
    void testFiveMinuteExpiryIsAcceptedAndEchoedInSeconds() {
        PayOS payOS = new PayOS("1b6d89f1-5fc9-4f04-a74f-cf1dbe76d174",
                "455a2421-afda-4edd-8022-4a6ce22617ed",
                "16aba0fcff5efb4ffe36a1e93286b1de335ae6f1ca40c25aaf52c881c1967401");

        long orderCode = (System.currentTimeMillis() / 1000L) % 1_000_000L + 200000L;
        long expiredAt = System.currentTimeMillis() / 1000L + 300L;

        CreatePaymentLinkRequest request = CreatePaymentLinkRequest.builder()
                .orderCode(orderCode)
                .amount(2000L)
                .description("NutriMom TTL")
                .returnUrl("http://localhost:5173/payment/success")
                .cancelUrl("http://localhost:5173/payment/cancel")
                .items(List.of(PaymentLinkItem.builder().name("TTL probe").quantity(1).price(2000L).build()))
                .expiredAt(expiredAt)
                .build();

        try {
            CreatePaymentLinkResponse response = payOS.paymentRequests().create(request);
            System.out.println("PAYOS_TTL_SENT: " + expiredAt);
            System.out.println("PAYOS_TTL_ECHOED: " + response.getExpiredAt());
            System.out.println("PAYOS_TTL_MATCHES_SECONDS: " + Long.valueOf(expiredAt).equals(response.getExpiredAt()));
            payOS.paymentRequests().cancel(orderCode, "TTL probe");
        } catch (Exception e) {
            System.err.println("PAYOS_TTL_REJECTED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
