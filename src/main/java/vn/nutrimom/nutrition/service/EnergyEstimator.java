package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import vn.nutrimom.nutrition.domain.TrimesterGroup;
import vn.nutrimom.nutrition.service.EnergyEstimate.Unmet;
import vn.nutrimom.nutrition.service.EnergyEstimate.UnmetKind;
import vn.nutrimom.nutrition.service.NutritionRules.EnergyEstimateRules;
import vn.nutrimom.nutrition.service.NutritionRules.EnergyFormula;

/**
 * Ước tính năng lượng EER cho thai kỳ — <strong>tùy chọn</strong>.
 *
 * <pre>
 * tam cá nguyệt 1    : intercept + a·A + h·H + w·W
 * tam cá nguyệt 2 - 3: intercept + a·A + h·H + w·W + 9,16·G + D
 * </pre>
 *
 * <p>Hệ số nạp từ {@code nutritionRules.json} nên đổi hệ số không phải sửa code, và
 * {@code rulesVersion} đi kèm kết quả để tái lập được.</p>
 *
 * <p>Ba điều lớp này cố ý <strong>không</strong> làm:</p>
 * <ul>
 *   <li><strong>Không cộng thêm 340 hay 450 kcal.</strong> Công thức đã gồm yếu tố thai kỳ; cộng
 *       nữa là tính hai lần.</li>
 *   <li><strong>Không đoán thay người dùng.</strong> Thiếu một đầu vào, giá trị vô lý, hay tuổi
 *       ngoài phạm vi mô hình thì trả về "chưa ước tính được" kèm lý do, không điền giá trị giả
 *       và không mặc định 2.000 kcal (BR20).</li>
 *   <li><strong>Không suy nhóm vận động</strong> từ số bước chân hay dữ liệu cảm biến.</li>
 * </ul>
 *
 * <p>{@code D} chọn theo BMI <strong>trước</strong> thai kỳ. Nó là thành phần toán học của mô
 * hình, không phải thiết lập giảm cân, và không được trình bày cho người dùng như lời khuyên về
 * cân nặng.</p>
 */
@Service
public class EnergyEstimator {

    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal DAYS_PER_WEEK = new BigDecimal("7");
    private static final BigDecimal CM_PER_METRE = new BigDecimal("100");

    /**
     * Khoảng hợp lý của số đo người lớn.
     *
     * <p>Đây là lưới chặn dữ liệu vô lý, không phải chuẩn y tế: 45 kg nhập nhầm thành 450, hay
     * chiều cao nhập bằng mét thành 1,6 thì phải dừng lại hỏi, chứ không tính ra một con số trông
     * vẫn như thật. Cân nặng khai bằng đơn vị khác kg mà không nói rõ thì người gọi để null, đừng
     * đoán — lớp này chỉ nhận kg.</p>
     */
    private static final BigDecimal MIN_HEIGHT_CM = new BigDecimal("120");
    private static final BigDecimal MAX_HEIGHT_CM = new BigDecimal("220");
    private static final BigDecimal MIN_WEIGHT_KG = new BigDecimal("30");
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("250");

    /** 45 tuần: quá mốc này thì dữ liệu tuổi thai gần như chắc chắn sai. */
    private static final int MAX_GESTATIONAL_DAYS = 315;

    private final NutritionRules rules;

    public EnergyEstimator(NutritionRulesProvider provider) {
        this.rules = provider.rules();
    }

    public EnergyEstimate estimate(EnergyInputs inputs) {
        EnergyEstimateRules config = rules.energyEstimate();
        List<Unmet> unmet = new ArrayList<>();

        checkAge(inputs.ageYears(), config, unmet);
        checkRange("height_cm", inputs.heightCm(), MIN_HEIGHT_CM, MAX_HEIGHT_CM, unmet);
        checkRange("current_weight_kg", inputs.currentWeightKg(), MIN_WEIGHT_KG, MAX_WEIGHT_KG, unmet);
        checkGestationalDays(inputs.gestationalDays(), unmet);
        if (inputs.activity() == null) {
            unmet.add(new Unmet("activity_category", UnmetKind.MISSING));
        }
        if (!unmet.isEmpty()) {
            return EnergyEstimate.unavailable(rules.rulesVersion(), unmet);
        }

        TrimesterGroup group = inputs.gestationalDays() < config.trimester2StartWeek() * 7
                ? TrimesterGroup.FIRST : TrimesterGroup.SECOND_THIRD;
        EnergyFormula formula = rules.formulaFor(group, inputs.activity()).orElse(null);
        if (formula == null) {
            // NutritionRulesProvider đã chặn trường hợp này lúc khởi động; còn lại ở đây để không
            // bao giờ trả ra một con số tính từ hệ số rỗng.
            return EnergyEstimate.unavailable(rules.rulesVersion(),
                    List.of(new Unmet("activity_category", UnmetKind.NOT_SUPPORTED)));
        }

        BigDecimal bmi = null;
        BigDecimal adjustment = null;
        if (formula.bmiAdjusted()) {
            // D là thành phần bắt buộc của bộ hệ số tam cá nguyệt 2 và 3. Thiếu cân nặng trước thai
            // kỳ thì không có D, và bỏ D đi là dùng một công thức khác chứ không phải ước tính thô.
            if (!inRange(inputs.prePregnancyWeightKg(), MIN_WEIGHT_KG, MAX_WEIGHT_KG)) {
                unmet.add(new Unmet("pre_pregnancy_weight_kg",
                        inputs.prePregnancyWeightKg() == null
                                ? UnmetKind.MISSING : UnmetKind.OUT_OF_RANGE));
                return EnergyEstimate.unavailable(rules.rulesVersion(), unmet);
            }
            bmi = bmi(inputs.prePregnancyWeightKg(), inputs.heightCm());
            adjustment = rules.bmiAdjustmentFor(bmi).orElse(null);
            if (adjustment == null) {
                return EnergyEstimate.unavailable(rules.rulesVersion(),
                        List.of(new Unmet("pre_pregnancy_weight_kg", UnmetKind.NOT_SUPPORTED)));
            }
        }

        BigDecimal gestationalWeeks =
                new BigDecimal(inputs.gestationalDays()).divide(DAYS_PER_WEEK, MATH);
        BigDecimal kcal = formula.intercept()
                .add(formula.agePerYear().multiply(new BigDecimal(inputs.ageYears())))
                .add(formula.heightPerCm().multiply(inputs.heightCm()))
                .add(formula.weightPerKg().multiply(inputs.currentWeightKg()))
                .add(formula.gestationPerWeek().multiply(gestationalWeeks));
        if (adjustment != null) {
            kcal = kcal.add(adjustment);
        }

        return new EnergyEstimate(kcal, group, inputs.activity(), bmi, adjustment,
                rules.rulesVersion(), List.of());
    }

    /** BMI trước thai kỳ: cân nặng trước thai kỳ chia bình phương chiều cao tính bằng mét. */
    private static BigDecimal bmi(BigDecimal weightKg, BigDecimal heightCm) {
        BigDecimal metres = heightCm.divide(CM_PER_METRE, MATH);
        return weightKg.divide(metres.multiply(metres), MATH);
    }

    private static void checkAge(Integer ageYears, EnergyEstimateRules config, List<Unmet> unmet) {
        if (ageYears == null) {
            unmet.add(new Unmet("age_years", UnmetKind.MISSING));
            return;
        }
        if (ageYears <= 0) {
            unmet.add(new Unmet("age_years", UnmetKind.OUT_OF_RANGE));
            return;
        }
        // Bộ hệ số chỉ có cho nhóm 19-50. Ngoài khoảng đó thì ngoại suy chứ không còn là ước tính,
        // nên dừng lại và nói rõ thay vì trả ra một con số không có cơ sở.
        if (ageYears < config.minAgeYears() || ageYears > config.maxAgeYears()) {
            unmet.add(new Unmet("age_years", UnmetKind.NOT_SUPPORTED));
        }
    }

    private static void checkGestationalDays(Integer gestationalDays, List<Unmet> unmet) {
        if (gestationalDays == null) {
            unmet.add(new Unmet("gestational_days", UnmetKind.MISSING));
        } else if (gestationalDays < 0 || gestationalDays > MAX_GESTATIONAL_DAYS) {
            unmet.add(new Unmet("gestational_days", UnmetKind.OUT_OF_RANGE));
        }
    }

    private static void checkRange(String field, BigDecimal value, BigDecimal min, BigDecimal max,
                                   List<Unmet> unmet) {
        if (value == null) {
            unmet.add(new Unmet(field, UnmetKind.MISSING));
        } else if (!inRange(value, min, max)) {
            unmet.add(new Unmet(field, UnmetKind.OUT_OF_RANGE));
        }
    }

    private static boolean inRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value != null && value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}
