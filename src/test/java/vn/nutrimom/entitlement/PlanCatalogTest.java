package vn.nutrimom.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Period;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.nutrimom.entitlement.domain.PlanDefinition;
import vn.nutrimom.entitlement.domain.PlanFeature;
import vn.nutrimom.entitlement.domain.QuotaPeriod;
import vn.nutrimom.payment.domain.PlanTier;

/** Catalog server-side có khớp đúng thứ đang được bán trên trang giá hay không (spec mục 17). */
class PlanCatalogTest {

    private final PlanCatalog catalog = new PlanCatalog();

    @Test
    void catalogCoversEveryPlanTier() {
        assertThat(catalog.all()).extracting(PlanDefinition::tier)
                .containsExactlyInAnyOrder(PlanTier.values());
    }

    /**
     * Thứ tự thẻ trên trang giá: gói nổi bật nằm giữa.
     *
     * <p>Test frontend {@code tests/public-pricing.test.ts} ghim đúng thứ tự này, nên catalog phải
     * trả về theo nó để FE render thẳng, khỏi sắp lại.</p>
     */
    @Test
    void catalogIsOrderedTheWayThePricingPageShowsIt() {
        assertThat(catalog.all()).extracting(PlanDefinition::code)
                .containsExactly("FREE", "PLAN_399K", "PLAN_99K");
        assertThat(catalog.all()).filteredOn(PlanDefinition::featured)
                .extracting(PlanDefinition::code).containsExactly("PLAN_399K");
    }

    @Test
    void priceAndCurrencyComeFromTheServerNotTheClient() {
        assertThat(catalog.get(PlanTier.FREE).price()).isZero();
        assertThat(catalog.get(PlanTier.PLAN_99K).price()).isEqualTo(99_000);
        assertThat(catalog.get(PlanTier.PLAN_399K).price()).isEqualTo(399_000);
        assertThat(catalog.all()).allSatisfy(
                plan -> assertThat(plan.currency()).isEqualTo("VND"));
    }

    /**
     * Bảng so sánh của FE dựng theo VỊ TRÍ: nó lấy nhãn từ gói đầu tiên rồi index mọi gói còn lại
     * bằng cùng chỉ số. Một gói thiếu dòng là cả cột sau bị dán sai nhãn.
     */
    @Test
    void everyPlanDeclaresEveryFeatureSoTheComparisonTableStaysAligned() {
        List<PlanFeature> all = Arrays.asList(PlanFeature.values());
        assertThat(catalog.all()).allSatisfy(plan ->
                assertThat(all).allSatisfy(feature -> assertThat(
                        plan.entitlements().features().contains(feature)).isNotNull()));
        assertThat(PlanFeature.values()).allSatisfy(
                feature -> assertThat(feature.label()).isNotBlank());
    }

    /** Đúng những dòng "✗" mà trang giá đang hứa — đây là hợp đồng mà cổng chặn phải thi hành. */
    @Test
    void paywalledFeaturesMatchWhatThePricingPagePromises() {
        assertThat(catalog.entitlementsOf(PlanTier.FREE).has(PlanFeature.FAMILY_SHARING)).isFalse();
        assertThat(catalog.entitlementsOf(PlanTier.PLAN_99K).has(PlanFeature.FAMILY_SHARING)).isFalse();
        assertThat(catalog.entitlementsOf(PlanTier.PLAN_399K).has(PlanFeature.FAMILY_SHARING)).isTrue();

        assertThat(catalog.entitlementsOf(PlanTier.FREE).has(PlanFeature.CALENDAR_REMINDERS)).isFalse();
        assertThat(catalog.entitlementsOf(PlanTier.PLAN_99K).has(PlanFeature.CALENDAR_REMINDERS)).isTrue();
        assertThat(catalog.entitlementsOf(PlanTier.PLAN_399K).has(PlanFeature.CALENDAR_REMINDERS)).isTrue();
    }

    @Test
    void scanQuotaKeepsItsPeriodNotJustANumber() {
        assertThat(catalog.entitlementsOf(PlanTier.FREE).scanQuota())
                .returns(2, q -> q.limit()).returns(QuotaPeriod.LIFETIME, q -> q.period());
        assertThat(catalog.entitlementsOf(PlanTier.PLAN_99K).scanQuota())
                .returns(2, q -> q.limit()).returns(QuotaPeriod.DAILY, q -> q.period());
        assertThat(catalog.entitlementsOf(PlanTier.PLAN_399K).scanQuota().isUnlimited()).isTrue();
    }

    /** Catalog nói đúng thời hạn server CẤP, không phải lời quảng cáo trên thẻ giá. */
    @Test
    void durationReflectsWhatTheServerActuallyGrants() {
        assertThat(catalog.get(PlanTier.FREE).duration()).isEqualTo(Period.ZERO);
        assertThat(catalog.get(PlanTier.PLAN_99K).duration()).isEqualTo(Period.ofDays(30));
        assertThat(catalog.get(PlanTier.PLAN_399K).duration()).isEqualTo(Period.ofMonths(10));
    }

    /**
     * Thứ catalog công bố và thứ {@code PaymentService} cấp là <strong>cùng một</strong> giá trị.
     *
     * <p>Trước đây hai bên là hai con số rời: catalog ghi 30 còn
     * {@code activateOrderAndSubscription} hardcode {@code plusDays(30)}, nên PLAN_399K bán 399k mà
     * nhận đúng thời hạn của gói 99k. Nay cả hai đọc {@link PlanTier#getTerm()}; test này chặn việc
     * ai đó tách chúng ra trở lại.</p>
     */
    @Test
    void catalogAndPaymentReadTheSameTerm() {
        for (PlanTier tier : PlanTier.values()) {
            assertThat(catalog.get(tier).duration())
                    .as("thời hạn công bố của %s phải đúng bằng PlanTier.getTerm()", tier)
                    .isEqualTo(tier.getTerm());
        }
    }
}
