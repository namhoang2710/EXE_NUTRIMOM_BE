package vn.nutrimom.entitlement;

import static vn.nutrimom.entitlement.domain.PlanFeature.AI_SCAN;
import static vn.nutrimom.entitlement.domain.PlanFeature.CALENDAR_REMINDERS;
import static vn.nutrimom.entitlement.domain.PlanFeature.CHATBOT;
import static vn.nutrimom.entitlement.domain.PlanFeature.CONCEPTION_CALCULATOR;
import static vn.nutrimom.entitlement.domain.PlanFeature.CONSULTATION;
import static vn.nutrimom.entitlement.domain.PlanFeature.DOCTOR_BOOKING;
import static vn.nutrimom.entitlement.domain.PlanFeature.FAMILY_SHARING;
import static vn.nutrimom.entitlement.domain.PlanFeature.MEDICAL_RECORDS;
import static vn.nutrimom.entitlement.domain.PlanFeature.PERSONALISED_GUIDANCE;
import static vn.nutrimom.entitlement.domain.PlanFeature.SUPPORT;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import vn.nutrimom.entitlement.domain.PlanDefinition;
import vn.nutrimom.entitlement.domain.PlanEntitlements;
import vn.nutrimom.entitlement.domain.ScanQuota;
import vn.nutrimom.payment.domain.PlanTier;

/**
 * Bảng gói do <strong>server</strong> sở hữu (spec mục 17: "Không lấy giá từ Flutter").
 *
 * <p><strong>Vì sao là hằng Java chứ không phải một bảng DB:</strong></p>
 * <ol>
 *   <li>Test tắt Flyway, nên catalog nằm trong bảng sẽ vô hình với cả suite — endpoint catalog lẫn
 *       mọi test chặn gói đều phải seed tay.</li>
 *   <li>Endpoint {@code /subscription-plans} và {@link vn.nutrimom.entitlement.service.EntitlementGuard}
 *       đọc <em>cùng một</em> record ở đây. Tách ra DB là mở đường cho thứ công bố và thứ enforce
 *       lệch nhau mà không test nào bắt được.</li>
 *   <li>Supabase dùng chung: mỗi lần đổi giá mà phải đẻ thêm migration là cái giá vô ích khi chưa có
 *       admin UI nào sửa giá.</li>
 * </ol>
 * <p>Cố ý không cho {@code @ConfigurationProperties} ghi đè giá: nó cho phép config prod lệch với
 * entitlements đang được enforce.</p>
 *
 * <p><strong>Thứ tự</strong> của {@link #all()} là thứ tự thẻ trên trang giá
 * (FREE → PLAN_399K → PLAN_99K, gói nổi bật nằm giữa), khớp thứ tự mà test frontend đang ghim.</p>
 *
 * <p><strong>{@code duration} nói đúng thứ server đang cấp, không phải lời quảng cáo.</strong>
 * Cả catalog này lẫn {@code PaymentService.activateOrderAndSubscription} đều đọc
 * {@link PlanTier#getTerm()}, nên thứ công bố và thứ được cấp là <em>cùng một giá trị</em>, không
 * phải hai con số phải nhớ đồng bộ bằng tay. {@code PlanCatalogTest} ghim bất biến đó.</p>
 */
@Component
public class PlanCatalog {

    private final Map<PlanTier, PlanDefinition> plans;

    public PlanCatalog() {
        Map<PlanTier, PlanDefinition> table = new LinkedHashMap<>();
        table.put(PlanTier.FREE, new PlanDefinition(
                PlanTier.FREE,
                PlanTier.FREE.getDisplayName(),
                "Khởi đầu nhẹ nhàng với những công cụ thiết yếu cho hành trình của mẹ.",
                PlanTier.FREE.getPrice(), "VND", PlanTier.FREE.getTerm(), false,
                new PlanEntitlements(
                        EnumSet.of(MEDICAL_RECORDS, AI_SCAN, CHATBOT, CONSULTATION,
                                DOCTOR_BOOKING, SUPPORT),
                        ScanQuota.lifetime(2))));
        table.put(PlanTier.PLAN_399K, new PlanDefinition(
                PlanTier.PLAN_399K,
                PlanTier.PLAN_399K.getDisplayName(),
                "Đầy đủ công cụ chăm sóc, theo dõi và kết nối gia đình trong suốt thai kỳ.",
                PlanTier.PLAN_399K.getPrice(), "VND", PlanTier.PLAN_399K.getTerm(), true,
                new PlanEntitlements(
                        EnumSet.allOf(vn.nutrimom.entitlement.domain.PlanFeature.class),
                        ScanQuota.unlimited())));
        table.put(PlanTier.PLAN_99K, new PlanDefinition(
                PlanTier.PLAN_99K,
                PlanTier.PLAN_99K.getDisplayName(),
                "Thêm công cụ theo dõi chủ động và gợi ý phù hợp với từng giai đoạn.",
                PlanTier.PLAN_99K.getPrice(), "VND", PlanTier.PLAN_99K.getTerm(), false,
                new PlanEntitlements(
                        EnumSet.of(CONCEPTION_CALCULATOR, MEDICAL_RECORDS, AI_SCAN,
                                CALENDAR_REMINDERS, PERSONALISED_GUIDANCE, CHATBOT,
                                CONSULTATION, DOCTOR_BOOKING, SUPPORT),
                        ScanQuota.perDay(2))));
        this.plans = Collections.unmodifiableMap(table);
    }

    /** Catalog theo thứ tự hiển thị. */
    public List<PlanDefinition> all() {
        return List.copyOf(plans.values());
    }

    public PlanDefinition get(PlanTier tier) {
        PlanDefinition definition = plans.get(tier);
        if (definition == null) {
            throw new IllegalStateException("Catalog thiếu định nghĩa cho gói " + tier);
        }
        return definition;
    }

    public PlanEntitlements entitlementsOf(PlanTier tier) {
        return get(tier).entitlements();
    }
}
