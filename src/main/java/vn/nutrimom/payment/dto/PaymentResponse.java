package vn.nutrimom.payment.dto;

import java.time.OffsetDateTime;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PlanTier;

/**
 * Body của {@code POST /payments/subscription-checkout}.
 *
 * <p>{@code expiresAt} có mặt ở đây chứ không chỉ ở {@link PaymentOrderResponse}: FE nhận đúng
 * response này ngay lúc mở màn hình QR, và đó là nơi cần đồng hồ đếm ngược. Bắt FE gọi thêm một
 * lượt {@code GET /orders/{orderCode}} chỉ để biết hạn là thêm một vòng mạng vào đúng giây người
 * dùng đang chờ mã.</p>
 */
public record PaymentResponse(
        Long orderCode,
        PlanTier planTier,
        String planName,
        int amount,
        String currency,
        PaymentOrderStatus status,
        String checkoutUrl,
        String qrCode,
        String description,
        OffsetDateTime expiresAt
) {}
