package vn.nutrimom.payment.dto;

import jakarta.validation.constraints.NotNull;
import vn.nutrimom.payment.domain.PlanTier;

public record CreatePaymentRequest(
        @NotNull(message = "Vui lòng chọn gói dịch vụ cần thanh toán")
        PlanTier planTier
) {}
