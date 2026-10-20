package vn.nutrimom.entitlement.domain;

import java.time.Period;
import vn.nutrimom.payment.domain.PlanTier;

/**
 * Một dòng trong catalog: giá do server sở hữu + quyền lợi đi kèm.
 *
 * <p>{@code duration} là thời hạn server <strong>thực sự</strong> cấp khi thanh toán xong, không
 * phải lời quảng cáo trên trang giá. Nó lấy thẳng từ {@link PlanTier#getTerm()} — cùng giá trị mà
 * {@code PaymentService} dùng để cấp hạn — nên catalog không thể lệch với thứ được enforce. Xem ghi
 * chú ở {@link vn.nutrimom.entitlement.PlanCatalog}.</p>
 */
public record PlanDefinition(
        PlanTier tier,
        String name,
        String description,
        int price,
        String currency,
        Period duration,
        boolean featured,
        PlanEntitlements entitlements) {

    /** Mã gói đi trên dây; trùng đúng chuỗi {@code PlanTier} mà frontend đang dùng. */
    public String code() {
        return tier.name();
    }
}
