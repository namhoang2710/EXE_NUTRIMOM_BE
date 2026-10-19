package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.service.MealPlanSearch.WeeklyAllowance;
import vn.nutrimom.nutrition.service.MealPlanSearch.WeeklyAllowance.Entry;
import vn.nutrimom.nutrition.service.NutritionRules.WeeklyFoodLimit;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.ScaledIngredient;

/**
 * Giới hạn thực phẩm <strong>theo tuần</strong> (nhánh 9): từ các dòng khai trong seed thành một
 * {@link WeeklyAllowance} mà bộ sinh kiểm được trong vòng lặp.
 *
 * <h2>Theo nhóm và theo tổng lượng, không theo tên món</h2>
 *
 * <p>Mỗi dòng seed là một <em>nhóm dữ liệu</em> gồm những mã nguyên liệu cụ thể. Lớp này quy mỗi
 * công thức ra <strong>số gram của nhóm trong một khẩu phần</strong>, nên điều kiện cứng cộng trên
 * nguyên liệu thật của cả kế hoạch: đổi tên món, đổi khẩu phần hay chia một món thành hai bữa đều
 * không đi vòng được. Tên món không xuất hiện ở bất kỳ phép so sánh nào tại đây.</p>
 *
 * <p>Quy đổi gram đi qua {@link NutritionCalculator#ingredientsFor} — đúng đường mà danh sách mua
 * sắm dùng — nên lượng cá trong giới hạn này và lượng cá trong danh sách đi chợ là cùng một con số
 * (bất biến 4).</p>
 *
 * <h2>Chỉ nguyên liệu khai thẳng trong công thức</h2>
 *
 * <p>{@link EligibilityFilter} duyệt xuống nguyên liệu con vì dị ứng là câu hỏi
 * <em>có hay không</em>. Giới hạn theo tuần là câu hỏi <em>bao nhiêu gram</em>, mà danh mục không
 * khai gram cho nguyên liệu con: không có cách nào biết một muỗng nước mắm mang bao nhiêu gram cá
 * cơm. Đoán một con số ở đây sẽ làm một điều kiện an toàn chạy trên số bịa, còn cộng trọn gram của
 * nguyên liệu cha thì mỗi món kho nêm mười gram nước mắm lại ăn mất mười gram hạn mức cá. Nên lớp
 * này chỉ cộng dòng nguyên liệu viết thẳng trong công thức, và seed khai nguyên liệu dẫn xuất
 * thành dòng riêng khi muốn tính nó.</p>
 */
@Service
public class WeeklyFoodLimits {

    private static final int GRAM_SCALE = 2;

    private final NutritionCalculator calculator;

    public WeeklyFoodLimits(NutritionCalculator calculator) {
        this.calculator = calculator;
    }

    public List<WeeklyFoodLimit> limits() {
        return calculator.rules().weeklyFoodLimits();
    }

    /**
     * Hạn mức còn nguyên của một kế hoạch sắp lập.
     *
     * @param snapshots mọi công thức có thể xuất hiện trong kế hoạch — ứng viên của từng vị trí,
     *                  và với nhánh 6 là cả món đang nằm trong kế hoạch đã lưu. Món không có mặt ở
     *                  đây mà sau đó được chọn sẽ được tính là <strong>0 g</strong>, nên nơi gọi
     *                  phải đưa đủ
     */
    public WeeklyAllowance allowanceFor(Collection<RecipeSnapshot> snapshots) {
        List<WeeklyFoodLimit> rules = limits();
        if (rules.isEmpty()) {
            return WeeklyAllowance.unlimited();
        }

        Map<String, Map<String, BigDecimal>> gramByRule = new LinkedHashMap<>();
        rules.forEach(rule -> gramByRule.put(rule.code(), new LinkedHashMap<>()));

        for (RecipeSnapshot snapshot : dedupe(snapshots)) {
            // Một khẩu phần: bộ sinh nhân lên theo số phần thật lúc kiểm.
            List<ScaledIngredient> perServing =
                    calculator.ingredientsFor(snapshot, BigDecimal.ONE);
            for (WeeklyFoodLimit rule : rules) {
                BigDecimal gram = BigDecimal.ZERO;
                for (ScaledIngredient line : perServing) {
                    if (rule.covers(line.ingredientId())) {
                        gram = gram.add(line.quantityGram());
                    }
                }
                if (gram.signum() > 0) {
                    gramByRule.get(rule.code())
                            .put(snapshot.recipeId(), gram.setScale(GRAM_SCALE,
                                    RoundingMode.HALF_UP));
                }
            }
        }

        List<Entry> entries = new ArrayList<>();
        for (WeeklyFoodLimit rule : rules) {
            entries.add(new Entry(rule.code(), rule.label(), rule.maxGramPerWeek(),
                    BigDecimal.ZERO, gramByRule.get(rule.code())));
        }
        return new WeeklyAllowance(List.copyOf(entries));
    }

    /**
     * Lý do ở mức kế hoạch cho những gì giới hạn tuần đã làm.
     *
     * <p>Phát ra <strong>cả khi còn thừa mức</strong>: "cá và hải sản nhóm nên ăn 120/340 g" là
     * thứ cho thấy điều kiện đã chạy trên tổng của cả kế hoạch, còn im lặng thì người dùng không
     * phân biệt được "trong mức" với "chưa ai kiểm". Nhóm <strong>không có mặt</strong> trong kế
     * hoạch thì không nói gì: một dòng "0/340 g" cho mỗi nhóm trong seed là tiếng ồn, và một kế
     * hoạch không có cá thì không có gì để báo cáo về cá.</p>
     */
    public List<ReasonNote> notes(WeeklyAllowance allowance) {
        List<ReasonNote> notes = new ArrayList<>();
        for (Entry entry : allowance.entries()) {
            if (entry.consumedGram().signum() == 0) {
                continue;
            }
            notes.add(ReasonNote.of(RecommendationReason.WEEKLY_FOOD_LIMIT_CHECKED,
                    "%s %s/%s g".formatted(entry.label(),
                            plain(entry.consumedGram()), plain(entry.maxGram()))));
            if (entry.reached()) {
                notes.add(ReasonNote.of(RecommendationReason.WEEKLY_FOOD_LIMIT_REACHED,
                        entry.label()));
            }
        }
        return notes;
    }

    private List<RecipeSnapshot> dedupe(Collection<RecipeSnapshot> snapshots) {
        Map<String, RecipeSnapshot> byId = new LinkedHashMap<>();
        for (RecipeSnapshot snapshot : snapshots) {
            byId.putIfAbsent(snapshot.recipeId(), snapshot);
        }
        return List.copyOf(byId.values());
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
