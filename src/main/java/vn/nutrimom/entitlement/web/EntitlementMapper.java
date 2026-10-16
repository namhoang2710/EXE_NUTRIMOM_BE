package vn.nutrimom.entitlement.web;

import java.util.Arrays;
import java.util.List;
import vn.nutrimom.entitlement.domain.PlanDefinition;
import vn.nutrimom.entitlement.domain.PlanEntitlements;
import vn.nutrimom.entitlement.domain.PlanFeature;
import vn.nutrimom.entitlement.domain.ScanQuota;
import vn.nutrimom.entitlement.dto.EntitlementDtos.PlanFeatureResponse;
import vn.nutrimom.entitlement.dto.EntitlementDtos.PlanResponse;
import vn.nutrimom.entitlement.dto.EntitlementDtos.ScanQuotaResponse;

/** Chuyển catalog sang hợp đồng JSON. */
final class EntitlementMapper {

    private EntitlementMapper() {
    }

    /**
     * Trả <strong>đủ</strong> mọi {@link PlanFeature} theo đúng thứ tự khai báo, kể cả dòng
     * {@code included = false}.
     *
     * <p>Bảng so sánh của FE dựng theo vị trí: nó lấy danh sách nhãn từ gói đầu tiên rồi index mọi
     * gói còn lại bằng cùng chỉ số. Lọc bớt dòng không có quyền sẽ làm lệch nhãn ở các cột sau.</p>
     */
    static List<PlanFeatureResponse> features(PlanEntitlements entitlements) {
        return Arrays.stream(PlanFeature.values())
                .map(feature -> new PlanFeatureResponse(
                        feature.name(), feature.label(), entitlements.has(feature)))
                .toList();
    }

    static ScanQuotaResponse scanQuota(ScanQuota quota) {
        return new ScanQuotaResponse(quota.limit(), quota.period().name(), quota.isUnlimited());
    }

    static PlanResponse plan(PlanDefinition plan) {
        return new PlanResponse(
                plan.code(), plan.name(), plan.description(), plan.price(), plan.currency(),
                plan.durationDays(), plan.featured(),
                features(plan.entitlements()), scanQuota(plan.entitlements().scanQuota()));
    }
}
