package vn.nutrimom.entitlement.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Hợp đồng của {@code /subscription-plans} và {@code /subscriptions/me} (spec mục 17).
 *
 * <p>Serialize snake_case toàn cục ({@code spring.jackson.property-naming-strategy}), nên record
 * viết camelCase vẫn ra {@code duration_days}, {@code scan_quota}… đúng thứ FE đang đọc.</p>
 */
public final class EntitlementDtos {

    private EntitlementDtos() {
    }

    /**
     * Một gói trên trang giá.
     *
     * <p>{@code id} trùng đúng chuỗi {@code PlanTier} FE đang dùng
     * ({@code FREE|PLAN_99K|PLAN_399K}) — deep link {@code ?plan=PLAN_399K} và {@code isPaidPlanTier}
     * so sánh literal nên lệch hoa thường là hỏng.</p>
     *
     * <p>{@code durationDays} là số ngày server thực cấp, không phải lời quảng cáo trên thẻ giá.</p>
     */
    public record PlanResponse(
            String id,
            String name,
            String description,
            int price,
            String currency,
            int durationDays,
            boolean featured,
            List<PlanFeatureResponse> features,
            ScanQuotaResponse scanQuota) { }

    /** Một dòng quyền lợi. Mọi gói trả về đủ các dòng, khác nhau ở {@code included}. */
    public record PlanFeatureResponse(String code, String label, boolean included) { }

    /** {@code period} ∈ {@code LIFETIME|DAILY|UNLIMITED}; {@code limit} vô nghĩa khi UNLIMITED. */
    public record ScanQuotaResponse(int limit, String period, boolean unlimited) { }

    /**
     * Gói hiện tại của người gọi + quyền lợi đã phân giải.
     *
     * <p>{@code paid} false nghĩa là đang ở FREE (không có subscription hiệu lực), khi đó
     * {@code startDate}/{@code endDate} đều null.</p>
     */
    public record MySubscriptionResponse(
            String planId,
            String planName,
            boolean paid,
            OffsetDateTime startDate,
            OffsetDateTime endDate,
            long daysRemaining,
            List<PlanFeatureResponse> features,
            ScanQuotaResponse scanQuota) { }
}
