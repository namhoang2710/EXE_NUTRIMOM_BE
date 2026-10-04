package vn.nutrimom.payment.dto;

import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PlanTier;

public record PaymentResponse(
        Long orderCode,
        PlanTier planTier,
        String planName,
        int amount,
        String currency,
        PaymentOrderStatus status,
        String checkoutUrl,
        String qrCode,
        String description
) {}
