package vn.nutrimom.payment.dto;

import java.time.OffsetDateTime;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.payment.domain.SubscriptionStatus;

public record SubscriptionResponse(
        String id,
        PlanTier planTier,
        String planName,
        SubscriptionStatus status,
        boolean active,
        OffsetDateTime startDate,
        OffsetDateTime endDate,
        long daysRemaining
) {}
