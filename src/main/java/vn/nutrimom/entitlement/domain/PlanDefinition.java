package vn.nutrimom.entitlement.domain;

import vn.nutrimom.payment.domain.PlanTier;

/**
 * Một dòng trong catalog: giá do server sở hữu + quyền lợi đi kèm.
 *
 * <p>{@code durationDays} là số ngày server <strong>thực sự</strong> cấp khi thanh toán xong, không
 * phải lời quảng cáo trên trang giá. Xem ghi chú ở {@link vn.nutrimom.entitlement.PlanCatalog}.</p>
 */
public record PlanDefinition(
        PlanTier tier,
        String name,
        String description,
        int price,
        String currency,
        int durationDays,
        boolean featured,
        PlanEntitlements entitlements) {

    /** Mã gói đi trên dây; trùng đúng chuỗi {@code PlanTier} mà frontend đang dùng. */
    public String code() {
        return tier.name();
    }
}
