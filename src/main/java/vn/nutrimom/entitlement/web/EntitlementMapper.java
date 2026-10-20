package vn.nutrimom.entitlement.web;

import java.time.Period;
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
                nominalDays(plan.duration()), plan.duration().toString(), durationLabel(plan.duration()),
                plan.featured(),
                features(plan.entitlements()), scanQuota(plan.entitlements().scanQuota()));
    }

    /**
     * Số ngày danh nghĩa (tháng = 30) cho field {@code duration_days} cũ.
     *
     * <p>Với gói tính theo tháng lịch đây là <strong>xấp xỉ</strong> — PLAN_399K thực cấp 303–305
     * ngày tuỳ ngày mua. Nguồn chính xác là {@code duration_iso} nằm ngay cạnh.</p>
     */
    private static int nominalDays(Period duration) {
        return (int) duration.toTotalMonths() * 30 + duration.getDays();
    }

    /** Chuỗi hiển thị dựng sẵn để FE không phải tự ghép từ con số. */
    private static String durationLabel(Period duration) {
        if (duration.isZero()) {
            return "Mãi mãi";
        }
        long months = duration.toTotalMonths();
        return months > 0 ? months + " tháng" : duration.getDays() + " ngày";
    }
}
