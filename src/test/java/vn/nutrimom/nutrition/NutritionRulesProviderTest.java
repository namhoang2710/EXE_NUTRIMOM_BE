package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import vn.nutrimom.nutrition.domain.ActivityCategory;
import vn.nutrimom.nutrition.domain.TrimesterGroup;
import vn.nutrimom.nutrition.service.NutritionRules;
import vn.nutrimom.nutrition.service.NutritionRulesProvider;
import vn.nutrimom.nutrition.service.NutritionSeedData.MicronutrientTarget;

/**
 * Bảng vi chất và hệ số EER phải <strong>nạp từ file seed</strong>, không nằm cứng trong code:
 * đổi một con số tham chiếu là sửa JSON và tăng {@code rulesVersion}, chứ không phải build lại
 * logic tính toán.
 */
class NutritionRulesProviderTest {

    private final NutritionRules rules = new NutritionRulesProvider().rules();

    @Test
    void loadsTheMicronutrientTableFromTheSeedFile() {
        assertThat(rules.rulesVersion()).isNotBlank();
        assertThat(rules.micronutrientTargets())
                .extracting(MicronutrientTarget::code)
                .containsExactlyInAnyOrder("FOLATE", "IRON", "CALCIUM", "CHOLINE", "IODINE",
                        "VITAMIN_D");

        var folate = rules.targetFor("FOLATE").orElseThrow();
        assertThat(folate.amountPerDay()).isEqualByComparingTo("600");
        // DFE khác với lượng axit folic ghi trên nhãn, nên đơn vị phải nói rõ là mcg_dfe.
        assertThat(folate.unit()).isEqualTo("mcg_dfe");
        assertThat(folate.kind()).isEqualTo("RDA");
        // Choline là AI chứ không phải RDA — hai loại mức tham chiếu khác nhau.
        assertThat(rules.targetFor("CHOLINE").orElseThrow().kind()).isEqualTo("AI");
    }

    @Test
    void loadsAFormulaForEveryTrimesterGroupAndActivityCategory() {
        for (TrimesterGroup group : TrimesterGroup.values()) {
            for (ActivityCategory activity : ActivityCategory.values()) {
                assertThat(rules.formulaFor(group, activity))
                        .as("hệ số EER cho %s/%s", group, activity)
                        .isPresent();
            }
        }
        assertThat(rules.energyEstimate().trimester2StartWeek()).isEqualTo(14);
        assertThat(rules.energyEstimate().minAgeYears()).isEqualTo(19);
        assertThat(rules.energyEstimate().maxAgeYears()).isEqualTo(50);
    }

    @Test
    void coversEveryBmiWithTheAdjustmentTable() {
        assertThat(rules.bmiAdjustmentFor(new BigDecimal("15"))).contains(new BigDecimal("300"));
        assertThat(rules.bmiAdjustmentFor(new BigDecimal("40"))).contains(new BigDecimal("-50"));
    }

    @Test
    void shipsWithoutRetentionFactorsSoFar() {
        // Bảng rỗng là CỐ Ý: chưa có bộ hệ số giữ lại đối chiếu được nguồn cho nhóm món Việt.
        // Calculator vẫn chạy với r = 1 nhưng ghi rõ đó là giả định — xem NutritionCalculatorTest.
        assertThat(rules.retentionFactors()).isEmpty();
    }

    @Test
    void knowsTheUnitOfEveryNutrientInTheCatalog() {
        assertThat(rules.unitFor("ENERGY")).contains("kcal");
        assertThat(rules.unitFor("IODINE")).contains("mcg");
        assertThat(rules.unitFor("KHONG_CO_THAT")).isEmpty();
    }
}
