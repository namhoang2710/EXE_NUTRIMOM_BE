package vn.nutrimom.payment.dto;

import java.time.OffsetDateTime;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PaymentSource;
import vn.nutrimom.payment.domain.PlanTier;

/**
 * Một dòng trong lịch sử giao dịch của người dùng, và cũng là body của endpoint chi tiết.
 *
 * <p>{@code orderCode} giữ kiểu số: sequence tự ghi nhận đã bị V47 hạ trần xuống
 * {@code 9007199254740991} nên vẫn an toàn với {@code Number} của JS, và
 * {@code docs/admin-operations-api.md} chốt API thanh toán phía user giữ kiểu số để tương thích.</p>
 *
 * <p>{@code source} cần cho FE vì không phải dòng nào cũng là giao dịch người dùng tự thực hiện:
 * trigger V46 sinh ra đơn {@code PAID} với {@code source = LEGACY_UNKNOWN} và {@code checkoutUrl}
 * null mỗi khi một gói được cấp thẳng trong Postgres. FE cần phân biệt để gắn nhãn đúng và không vẽ
 * nút thanh toán tiếp cho những dòng không có link.</p>
 *
 * <p>{@code checkoutUrl} và {@code qrCode} chỉ có nghĩa khi đơn còn {@code PENDING}; với đơn đã
 * thanh toán hoặc huỷ thì chúng là link chết. Cả hai đều nullable và
 * {@code default-property-inclusion: non_null} sẽ bỏ hẳn key khỏi JSON khi vắng.</p>
 *
 * <p>{@code expiresAt} là hạn hiệu lực của link checkout, để FE đếm ngược thay vì đoán. Null với
 * đơn tạo trước V67 (và chỉ với chúng), nên FE phải chịu được việc thiếu key: vắng hạn nghĩa là
 * không biết, không phải là vô hạn.</p>
 */
public record PaymentOrderResponse(
        String id,
        Long orderCode,
        PlanTier planTier,
        String planName,
        int amount,
        String currency,
        PaymentOrderStatus status,
        PaymentSource source,
        String checkoutUrl,
        String qrCode,
        String description,
        OffsetDateTime paidAt,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
