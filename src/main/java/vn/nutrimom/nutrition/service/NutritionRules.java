package vn.nutrimom.nutrition.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.nutrimom.nutrition.domain.ActivityCategory;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.TrimesterGroup;
import vn.nutrimom.nutrition.service.NutritionSeedData.MicronutrientTarget;

/**
 * Tập quy tắc dinh dưỡng đã nạp từ {@code nutritionRules.json} và danh mục chất từ
 * {@code nutrientSources.json}, dạng bất biến để {@link NutritionCalculator} và
 * {@link EnergyEstimator} dùng lại không phải đọc file hay database.
 *
 * <p>{@link #rulesVersion()} đi kèm <strong>mọi</strong> kết quả tính: nhánh 5 ghi nó vào snapshot
 * kế hoạch, nhờ đó sửa seed về sau không làm lệch con số đã trình bày cho người dùng, và một kết
 * quả cũ luôn tái lập được bằng đúng tập quy tắc đã sinh ra nó.</p>
 */
public record NutritionRules(String rulesVersion,
                             Map<String, String> nutrientUnits,
                             List<MicronutrientTarget> micronutrientTargets,
                             List<RetentionFactor> retentionFactors,
                             List<WeeklyFoodLimit> weeklyFoodLimits,
                             EnergyEstimateRules energyEstimate) {

    public NutritionRules {
        weeklyFoodLimits = weeklyFoodLimits == null ? List.of() : List.copyOf(weeklyFoodLimits);
    }

    /**
     * Một nhóm thực phẩm có <strong>giới hạn theo tuần</strong> (nhánh 9).
     *
     * <p>FDA/EPA xếp cá theo nhóm và cho tần suất <em>theo tuần</em>, nên điều kiện này chỉ có
     * nghĩa trên cả kế hoạch chứ không trên từng ngày. Ba điều lớp dùng nó phải giữ:</p>
     *
     * <ol>
     *   <li><strong>Theo nhóm, không theo tên món.</strong> {@code ingredientIds} là nhóm dữ liệu
     *       thật; bộ sinh cộng gram nguyên liệu của cả kế hoạch, nên một món đổi tên vẫn bị tính.
     *       </li>
     *   <li><strong>Mỗi nhóm một dòng.</strong> Cá ít thuỷ ngân và cá nhiều thuỷ ngân là hai mức
     *       khác nhau; gộp chúng lại là coi mọi loại cá như nhau, đúng thứ nhánh 9 cấm.</li>
     *   <li><strong>Chỉ nguyên liệu khai thẳng trong công thức.</strong> Danh mục không khai gram
     *       cho nguyên liệu con, nên không có cách nào biết một muỗng nước mắm mang bao nhiêu gram
     *       cá cơm. Đoán ra một con số ở đây sẽ làm giới hạn an toàn dựa trên số bịa; muốn tính thì
     *       khai chính nguyên liệu dẫn xuất đó thành một dòng.</li>
     * </ol>
     *
     * @param maxGramPerWeek mức của <strong>một tuần</strong>, áp cho cả kế hoạch kể cả khi kế
     *                       hoạch ngắn hơn bảy ngày
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WeeklyFoodLimit(String code, String label, List<String> ingredientIds,
                                  BigDecimal maxGramPerWeek, String sourceId) {

        public WeeklyFoodLimit {
            ingredientIds = ingredientIds == null ? List.of() : List.copyOf(ingredientIds);
        }

        public boolean covers(String ingredientId) {
            return ingredientIds.contains(ingredientId);
        }
    }

    /**
     * Hệ số giữ lại {@code r} khi bản ghi dinh dưỡng mô tả một trạng thái, còn công thức dùng
     * nguyên liệu ở trạng thái khác.
     *
     * <p>{@code nutrientCode} hoặc {@code foodGroup} để null nghĩa là dòng áp cho mọi chất hoặc
     * mọi nhóm thực phẩm. Dòng khai cụ thể hơn thắng.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetentionFactor(FoodState fromState, FoodState toState, String nutrientCode,
                                  FoodGroup foodGroup, BigDecimal factor, String sourceId) {

        boolean matches(FoodState from, FoodState to, String code, FoodGroup group) {
            return fromState == from && toState == to
                    && (nutrientCode == null || nutrientCode.equals(code))
                    && (foodGroup == null || foodGroup == group);
        }

        /** Dòng khai cả chất lẫn nhóm cụ thể hơn dòng khai chung. */
        int specificity() {
            return (nutrientCode == null ? 0 : 2) + (foodGroup == null ? 0 : 1);
        }
    }

    /**
     * Hệ số của mô hình EER.
     *
     * <p>{@code trimester2StartWeek} là ranh giới tuần thai tròn giữa hai bộ hệ số — 13 tuần 6 ngày
     * còn dùng bộ {@link TrimesterGroup#FIRST}, 14 tuần 0 ngày đã sang bộ kia (TC04).</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnergyEstimateRules(int minAgeYears, int maxAgeYears, int trimester2StartWeek,
                                      List<EnergyFormula> formulas,
                                      List<BmiAdjustment> bmiAdjustments) {
    }

    /**
     * Một dòng hệ số: {@code intercept + agePerYear·A + heightPerCm·H + weightPerKg·W
     * + gestationPerWeek·G (+ D)}.
     *
     * <p>Công thức <strong>đã gồm</strong> yếu tố thai kỳ. Không cộng thêm 340 hay 450 kcal lên
     * kết quả.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnergyFormula(TrimesterGroup trimesterGroup, ActivityCategory activity,
                                BigDecimal intercept, BigDecimal agePerYear, BigDecimal heightPerCm,
                                BigDecimal weightPerKg, BigDecimal gestationPerWeek,
                                boolean bmiAdjusted) {
    }

    /**
     * Thành phần {@code D} của mô hình, chọn theo BMI <strong>trước</strong> thai kỳ.
     *
     * <p>Đây là hệ số toán học của công thức, <strong>không phải</strong> thiết lập tăng hay giảm
     * cân, và không được trình bày cho người dùng như một lời khuyên về cân nặng.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BmiAdjustment(BigDecimal maxBmiExclusive, BigDecimal kcalPerDay) {
    }

    /** Đơn vị chuẩn của một chất, lấy từ danh mục chất — không lấy theo từng món. */
    public Optional<String> unitFor(String nutrientCode) {
        return Optional.ofNullable(nutrientUnits.get(nutrientCode));
    }

    public Optional<MicronutrientTarget> targetFor(String nutrientCode) {
        return micronutrientTargets.stream()
                .filter(target -> target.code().equals(nutrientCode))
                .findFirst();
    }

    /**
     * Hệ số giữ lại cho một chuyển trạng thái cụ thể.
     *
     * <p>Rỗng nghĩa là <strong>chưa có hệ số đối chiếu được nguồn</strong>. Người gọi vẫn tính với
     * {@code r = 1} nhưng phải ghi đó là giả định ước tính — xem
     * {@link NutritionTotals#methodNotes()}.</p>
     */
    public Optional<BigDecimal> retentionFactor(FoodState from, FoodState to, String nutrientCode,
                                                FoodGroup foodGroup) {
        return retentionFactors.stream()
                .filter(row -> row.matches(from, to, nutrientCode, foodGroup))
                .max(Comparator.comparingInt(RetentionFactor::specificity))
                .map(RetentionFactor::factor);
    }

    public Optional<EnergyFormula> formulaFor(TrimesterGroup group, ActivityCategory activity) {
        return energyEstimate.formulas().stream()
                .filter(row -> row.trimesterGroup() == group && row.activity() == activity)
                .findFirst();
    }

    /** Dòng {@code D} đầu tiên có cận trên lớn hơn BMI; dòng cuối (cận null) bắt mọi giá trị. */
    public Optional<BigDecimal> bmiAdjustmentFor(BigDecimal prePregnancyBmi) {
        return energyEstimate.bmiAdjustments().stream()
                .filter(row -> row.maxBmiExclusive() == null
                        || prePregnancyBmi.compareTo(row.maxBmiExclusive()) < 0)
                .findFirst()
                .map(BmiAdjustment::kcalPerDay);
    }
}
