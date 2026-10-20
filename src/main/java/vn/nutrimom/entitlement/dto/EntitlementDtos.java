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
     * <p><strong>Ba field thời hạn, đọc field nào cho việc gì:</strong></p>
     * <ul>
     *   <li>{@code durationIso} — nguồn chính xác, {@code Period.toString()}:
     *       {@code "P0D"} / {@code "P30D"} / {@code "P10M"}. {@code Period.parse()} round-trip
     *       được. Dùng nó khi cần tính toán.</li>
     *   <li>{@code durationLabel} — chuỗi hiển thị dựng sẵn ({@code "10 tháng"}), FE render thẳng
     *       thay vì tự ghép từ con số.</li>
     *   <li>{@code durationDays} — số ngày <strong>danh nghĩa</strong> để hiển thị thô
     *       ({@code 0 / 30 / 300}). Với gói tính theo tháng lịch nó là xấp xỉ: PLAN_399K thực cấp
     *       303–305 ngày tuỳ ngày mua. Giữ lại vì nó là field FE cũ đang đọc, và giữ kiểu
     *       {@code int} non-null vì {@code default-property-inclusion: non_null} sẽ <em>xoá hẳn</em>
     *       field khỏi JSON nếu để null — đổi hợp đồng của một endpoint công khai.</li>
     * </ul>
     */
    public record PlanResponse(
            String id,
            String name,
            String description,
            int price,
            String currency,
            int durationDays,
            String durationIso,
            String durationLabel,
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
