package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import vn.nutrimom.nutrition.domain.ActivityCategory;
import vn.nutrimom.nutrition.domain.TrimesterGroup;
import vn.nutrimom.nutrition.service.EnergyEstimate;
import vn.nutrimom.nutrition.service.EnergyEstimate.UnmetKind;
import vn.nutrimom.nutrition.service.EnergyEstimator;
import vn.nutrimom.nutrition.service.EnergyInputs;
import vn.nutrimom.nutrition.service.NutritionRulesProvider;

/**
 * Ước tính năng lượng EER.
 *
 * <p>Giá trị mong đợi trong từng test được tính tay từ chính công thức trong
 * {@code branch-02-calculator.md}, không lấy từ kết quả chạy — nếu không thì test chỉ chụp lại
 * đúng con số mà code đang trả ra, kể cả khi nó sai.</p>
 */
class EnergyEstimatorTest {

    private final EnergyEstimator estimator = new EnergyEstimator(new NutritionRulesProvider());

    // ---------------------------------------------------------------- TC05

    @Test
    void doesNotEstimateWhenHeightAndWeightWereSkipped() {
        // TC05: người dùng bấm "Để sau" ở bước tùy chọn. Không có EER thì lớp trên không hiển thị
        // phần trăm năng lượng, và tuyệt đối không mặc định 2.000 kcal (BR20).
        var estimate = estimator.estimate(new EnergyInputs(30, null, null, null, 140,
                ActivityCategory.LOW_ACTIVE));

        assertThat(estimate.available()).isFalse();
        assertThat(estimate.kcalPerDay()).isNull();
        assertThat(estimate.unmet())
                .containsExactlyInAnyOrder(
                        new EnergyEstimate.Unmet("height_cm", UnmetKind.MISSING),
                        new EnergyEstimate.Unmet("current_weight_kg", UnmetKind.MISSING));
    }

    @Test
    void doesNotEstimateWithoutAnActivityCategory() {
        // Không suy nhóm vận động từ bất kỳ dữ liệu nào khác.
        var estimate = estimator.estimate(new EnergyInputs(30, cm("160"), kg("60"), kg("55"), 140,
                null));

        assertThat(estimate.available()).isFalse();
        assertThat(estimate.unmet())
                .containsExactly(new EnergyEstimate.Unmet("activity_category", UnmetKind.MISSING));
    }

    // ------------------------------------------------------- tam cá nguyệt 1

    @Test
    void usesTheFirstTrimesterFormula() {
        // 575,77 - 7,01x30 + 6,60x160 + 12,14x55 = 2089,17
        var estimate = estimator.estimate(new EnergyInputs(30, cm("160"), kg("55"), kg("55"), 70,
                ActivityCategory.LOW_ACTIVE));

        assertThat(estimate.trimesterGroup()).isEqualTo(TrimesterGroup.FIRST);
        assertThat(estimate.kcalPerDay()).isEqualByComparingTo("2089.17");
        // Tam cá nguyệt 1 không có thành phần D và không dùng tuổi thai.
        assertThat(estimate.bmiAdjustmentKcal()).isNull();
        assertThat(estimate.prePregnancyBmi()).isNull();
    }

    @Test
    void doesNotNeedThePrePregnancyWeightInTheFirstTrimester() {
        var estimate = estimator.estimate(new EnergyInputs(30, cm("160"), kg("55"), null, 70,
                ActivityCategory.LOW_ACTIVE));

        assertThat(estimate.available()).isTrue();
    }

    // --------------------------------------------------- tam cá nguyệt 2 và 3

    @Test
    void usesTheSecondTrimesterFormulaWithTheBmiComponent() {
        // 1131,20 - 2,04x30 + 0,34x160 + 12,15x60 + 9,16x20 + 200 = 2236,60
        // BMI trước thai kỳ = 55 / 1,6^2 = 21,48 -> D = +200
        var estimate = estimator.estimate(new EnergyInputs(30, cm("160"), kg("60"), kg("55"), 140,
                ActivityCategory.INACTIVE));

        assertThat(estimate.trimesterGroup()).isEqualTo(TrimesterGroup.SECOND_THIRD);
        assertThat(estimate.kcalPerDay()).isEqualByComparingTo("2236.60");
        assertThat(estimate.bmiAdjustmentKcal()).isEqualByComparingTo("200");
        assertThat(estimate.prePregnancyBmi()).isEqualByComparingTo("21.484375");
    }

    @Test
    void doesNotAddAnyPregnancySurchargeOnTopOfTheFormula() {
        // Công thức đã gồm yếu tố thai kỳ. Cộng thêm 340 hay 450 kcal là tính hai lần — test này
        // khoá đúng điểm đó: kết quả phải bằng CHÍNH công thức, không hơn một kcal nào.
        var estimate = estimator.estimate(new EnergyInputs(30, cm("160"), kg("60"), kg("55"), 140,
                ActivityCategory.INACTIVE));

        BigDecimal byHand = new BigDecimal("1131.20")
                .subtract(new BigDecimal("2.04").multiply(new BigDecimal("30")))
                .add(new BigDecimal("0.34").multiply(new BigDecimal("160")))
                .add(new BigDecimal("12.15").multiply(new BigDecimal("60")))
                .add(new BigDecimal("9.16").multiply(new BigDecimal("20")))
                .add(new BigDecimal("200"));

        assertThat(estimate.kcalPerDay()).isEqualByComparingTo(byHand);
    }

    @Test
    void doesNotEstimateInTheSecondTrimesterWithoutThePrePregnancyWeight() {
        // Thiếu cân nặng trước thai kỳ thì không có D. Bỏ D đi là dùng một công thức khác, không
        // phải ước tính thô hơn — nên dừng lại và hỏi.
        var estimate = estimator.estimate(new EnergyInputs(30, cm("160"), kg("60"), null, 140,
                ActivityCategory.INACTIVE));

        assertThat(estimate.available()).isFalse();
        assertThat(estimate.unmet()).containsExactly(
                new EnergyEstimate.Unmet("pre_pregnancy_weight_kg", UnmetKind.MISSING));
    }

    // ------------------------------------------------------- ranh giới D

    @Test
    void picksTheBmiComponentByExclusiveUpperBound() {
        // Cao 1,6 m: BMI = cân nặng / 2,56.
        assertThat(adjustmentFor("45")).isEqualByComparingTo("300");    // 17,58 -> dưới 18,5
        assertThat(adjustmentFor("47.36")).isEqualByComparingTo("200"); // đúng 18,5
        assertThat(adjustmentFor("64")).isEqualByComparingTo("150");    // đúng 25
        assertThat(adjustmentFor("76.8")).isEqualByComparingTo("-50");  // đúng 30
    }

    // -------------------------------------------------- ranh giới tam cá nguyệt

    @Test
    void switchesFormulaExactlyAtFourteenWeeks() {
        // 13 tuần 6 ngày = 97 ngày vẫn là bộ hệ số tam cá nguyệt 1; 14 tuần 0 ngày = 98 ngày đã
        // sang bộ kia. Lệch một ngày ở đây là lệch cả mục tiêu năng lượng của người dùng (TC04).
        assertThat(estimateAtDay(97).trimesterGroup()).isEqualTo(TrimesterGroup.FIRST);
        assertThat(estimateAtDay(98).trimesterGroup()).isEqualTo(TrimesterGroup.SECOND_THIRD);
    }

    // ------------------------------------------------------- dữ liệu không hợp lệ

    @Test
    void doesNotExtrapolateOutsideTheModelAgeRange() {
        var estimate = estimator.estimate(new EnergyInputs(16, cm("160"), kg("55"), kg("55"), 70,
                ActivityCategory.LOW_ACTIVE));

        assertThat(estimate.available()).isFalse();
        assertThat(estimate.unmet())
                .containsExactly(new EnergyEstimate.Unmet("age_years", UnmetKind.NOT_SUPPORTED));
    }

    @Test
    void doesNotEstimateFromImplausibleMeasurements() {
        // 1,6 nhập vào ô centimet, và 450 nhập vào ô kilogram: hai lỗi nhập liệu thường gặp nhất.
        var estimate = estimator.estimate(new EnergyInputs(30, cm("1.6"), kg("450"), kg("55"), 140,
                ActivityCategory.ACTIVE));

        assertThat(estimate.available()).isFalse();
        assertThat(estimate.unmet()).containsExactlyInAnyOrder(
                new EnergyEstimate.Unmet("height_cm", UnmetKind.OUT_OF_RANGE),
                new EnergyEstimate.Unmet("current_weight_kg", UnmetKind.OUT_OF_RANGE));
    }

    @Test
    void carriesTheRulesVersionEvenWhenItCannotEstimate() {
        var estimate = estimator.estimate(new EnergyInputs(null, null, null, null, null, null));

        assertThat(estimate.rulesVersion()).isNotBlank();
    }

    // ------------------------------------------------------------- fixture

    private BigDecimal adjustmentFor(String prePregnancyWeightKg) {
        return estimator.estimate(new EnergyInputs(30, cm("160"), kg("60"),
                        kg(prePregnancyWeightKg), 140, ActivityCategory.INACTIVE))
                .bmiAdjustmentKcal();
    }

    private EnergyEstimate estimateAtDay(int gestationalDays) {
        return estimator.estimate(new EnergyInputs(30, cm("160"), kg("60"), kg("55"),
                gestationalDays, ActivityCategory.INACTIVE));
    }

    private static BigDecimal cm(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal kg(String value) {
        return new BigDecimal(value);
    }
}
