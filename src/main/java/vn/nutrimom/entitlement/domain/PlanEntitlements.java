package vn.nutrimom.entitlement.domain;

import java.util.Set;

/**
 * Những gì một gói thực sự mở khoá.
 *
 * <p>Giới hạn dạng số khác (số thành viên gia đình tối đa, số nhắc nhở tối đa…) chưa có vì bảng giá
 * hiện không hứa cái nào; {@link ScanQuota} là khuôn mẫu sẵn khi cần thêm.</p>
 */
public record PlanEntitlements(Set<PlanFeature> features, ScanQuota scanQuota) {

    public PlanEntitlements {
        features = features == null ? Set.of() : Set.copyOf(features);
    }

    public boolean has(PlanFeature feature) {
        return features.contains(feature);
    }
}
