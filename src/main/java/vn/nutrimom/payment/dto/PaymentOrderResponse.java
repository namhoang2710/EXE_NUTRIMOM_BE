package vn.nutrimom.payment.dto;

import java.time.OffsetDateTime;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PlanTier;

public record PaymentOrderResponse(
        String id,
        Long orderCode,
        PlanTier planTier,
        String planName,
        int amount,
        String currency,
        PaymentOrderStatus status,
        String checkoutUrl,
        String description,
        OffsetDateTime paidAt,
        OffsetDateTime createdAt
) {}
