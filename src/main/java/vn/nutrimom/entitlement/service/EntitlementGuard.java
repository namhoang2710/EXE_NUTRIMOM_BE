package vn.nutrimom.entitlement.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.entitlement.PlanCatalog;
import vn.nutrimom.entitlement.domain.PlanDefinition;
import vn.nutrimom.entitlement.domain.PlanFeature;
import vn.nutrimom.payment.domain.PlanTier;

/**
 * Cổng tính năng theo gói, soi gương {@link vn.nutrimom.common.security.AccessGuard}.
 *
 * <p>Cùng kiểu dùng: một {@code @Component} được inject vào service rồi gọi thẳng, không annotation,
 * không AOP — dự án này không có aspect nào và task 17 không phải lý do để kéo AOP vào.</p>
 *
 * <p>Quy ước đi kèm, khác với {@code AccessGuard}:</p>
 * <ul>
 *   <li>Thiếu gói trả về <strong>403 {@link ErrorCode#PLAN_UPGRADE_REQUIRED}</strong> chứ không phải
 *       404. Quy ước "không sở hữu thì giấu bằng 404" của {@code AccessGuard} là để không lộ việc
 *       tài nguyên có tồn tại; ở đây thì ngược lại — tính năng <em>cố ý</em> phải lộ ra để người
 *       dùng biết mà nâng cấp.</li>
 *   <li>Chỉ dùng cho đường <strong>GHI</strong>. Dữ liệu user đã tạo từ trước vẫn phải đọc và xoá
 *       được, nếu không thì bật cổng đồng nghĩa với nuốt mất dữ liệu của họ.</li>
 * </ul>
 */
@Component
public class EntitlementGuard {

    private final EntitlementService entitlements;
    private final PlanCatalog catalog;

    public EntitlementGuard(EntitlementService entitlements, PlanCatalog catalog) {
        this.entitlements = entitlements;
        this.catalog = catalog;
    }

    /** Yêu cầu gói hiện tại của {@code userId} có {@code feature}; thiếu → 403. */
    public void requireFeature(String userId, PlanFeature feature) {
        PlanTier current = entitlements.currentPlan(userId);
        if (catalog.entitlementsOf(current).has(feature)) {
            return;
        }
        PlanDefinition required = cheapestPlanWith(feature);
        throw new BusinessException(ErrorCode.PLAN_UPGRADE_REQUIRED,
                feature.label() + " chỉ có ở " + required.name() + ".",
                upgradeHint(feature, current, required));
    }

    /**
     * Gói rẻ nhất có bán {@code feature} — chính là gói đáng mời nâng cấp.
     *
     * <p>Tính từ catalog thay vì ghim cứng ở mỗi chỗ gọi: đổi bảng quyền lợi thì lời mời tự đúng
     * theo.</p>
     */
    private PlanDefinition cheapestPlanWith(PlanFeature feature) {
        return catalog.all().stream()
                .filter(plan -> plan.entitlements().has(feature))
                .min(java.util.Comparator.comparingInt(PlanDefinition::price))
                .orElseThrow(() -> new IllegalStateException(
                        "Không gói nào trong catalog bán tính năng " + feature));
    }

    /**
     * Dữ liệu để FE dựng dialog nâng cấp.
     *
     * <p>Đi trong {@code fields} của error envelope. {@code docs/error-codes.md} ghi rõ đây là ngoại
     * lệ có chủ đích của quy ước "{@code fields} chỉ dùng cho lỗi validation".</p>
     */
    private Map<String, String> upgradeHint(
            PlanFeature feature, PlanTier current, PlanDefinition required) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("feature", feature.name());
        fields.put("required_plan", required.code());
        fields.put("current_plan", current.name());
        return fields;
    }
}
