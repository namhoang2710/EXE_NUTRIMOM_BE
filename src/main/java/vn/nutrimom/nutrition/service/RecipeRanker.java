package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.MealFeedbackSignal;
import vn.nutrimom.nutrition.domain.RankingCriterion;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.service.NutritionTotals.NutrientTotal;
import vn.nutrimom.nutrition.service.RecipeCandidate.IngredientFacts;

/**
 * Bước <strong>chấm điểm</strong>. Chỉ nhận món đã qua {@link EligibilityFilter} (bất biến 2).
 *
 * <pre>
 * S = 0,35·N + 0,30·P + 0,15·V + 0,10·B + 0,10·C
 * </pre>
 *
 * <p>Mỗi thành phần 0–100. Đây là cấu hình thử nghiệm sản phẩm, <strong>không phải công thức y
 * khoa</strong>: điểm nói món nào hợp hơn với hồ sơ, không nói món nào tốt cho sức khỏe hơn.</p>
 *
 * <h2>Thiếu tiêu chí thì bỏ trọng số, không cho 0</h2>
 *
 * <pre>
 * S = Σ(w_i · f_i) / Σ(w_i),  i ∈ tập tiêu chí dùng được
 * </pre>
 *
 * <p>Cho 0 điểm nghĩa là coi "chưa biết" bằng "kém", và như thế mọi món thiếu dữ liệu sẽ nằm đáy
 * danh sách chỉ vì danh mục còn trống chứ không phải vì món dở (TC06).</p>
 *
 * <h2>Hai thành phần của P hiện chưa có nguồn dữ liệu</h2>
 *
 * <p>"Hợp nhóm ẩm thực thích" (+15) và "hợp tags dung nạp" (+15) đòi công thức trong danh mục phải
 * mang nhãn nhóm ẩm thực và nhãn mùi/kết cấu. Danh mục của nhánh 1 <strong>không có hai nhãn
 * đó</strong>, và nhánh này cố ý <em>không</em> bịa ra một cách suy chúng từ nguyên liệu chính —
 * suy bừa thì điểm khẩu vị sẽ nói dối một cách rất thuyết phục.</p>
 *
 * <p>Công thức vẫn hiện thực đủ và kiểm được bằng test; {@link RankingInputs#preferredCuisineTags()}
 * và {@link RankingInputs#toleranceTags()} hiện luôn rỗng ở tầng gọi. Khi seed có nhãn, chỉ cần đổ
 * dữ liệu vào là hai thành phần sống dậy, không phải sửa cách tính.</p>
 *
 * <h2>Seed của nút "Tạo lại"</h2>
 *
 * <p>Seed <strong>chỉ chạm tới các món bằng điểm</strong>, và chỉ sau khi đã ưu tiên món có dữ
 * liệu đầy đủ hơn. Thứ tự chuẩn (seed = 0) vẫn là thứ tự đã công bố trong tài liệu; seed khác 0
 * xáo lại các nhóm bằng điểm theo một cách <em>tái lập được</em> — cùng seed cho đúng danh sách
 * cũ. Nếu seed được phép chạm tới cả thứ hạng theo điểm thì không còn gì để chứng minh.</p>
 */
@Service
public class RecipeRanker {

    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);
    private static final int SCORE_SCALE = 3;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal HALF = new BigDecimal("0.5");

    /** Điểm khởi đầu của P: trung tính, chưa biết gì về khẩu vị thì không khen cũng không chê. */
    private static final BigDecimal PREFERENCE_BASE = new BigDecimal("50");

    private static final BigDecimal FAVORITE_BONUS = new BigDecimal("20");
    private static final BigDecimal CUISINE_BONUS = new BigDecimal("15");
    private static final BigDecimal TOLERANCE_BONUS = new BigDecimal("15");
    private static final BigDecimal DISLIKED_INGREDIENT_PENALTY = new BigDecimal("35");

    private static final BigDecimal VARIETY_RECIPE_REPEAT = new BigDecimal("40");
    private static final BigDecimal VARIETY_MAIN_INGREDIENT_REPEAT = new BigDecimal("20");
    private static final BigDecimal VARIETY_WEEK_REPEAT = new BigDecimal("10");

    /**
     * Một món đã qua lọc, kèm mọi thứ cần để chấm điểm.
     *
     * @param perServing tổng dinh dưỡng của <strong>một khẩu phần</strong>, do
     *                   {@link NutritionCalculator} của nhánh 2 tính. Nhánh này
     *                   <strong>không tự cộng tổng dinh dưỡng</strong> (bất biến 4)
     * @param costVnd    chi phí ước tính một khẩu phần; null khi chưa quy đổi được giá → bỏ điểm B
     */
    public record Scorable(RecipeCandidate candidate, NutritionTotals perServing,
                           BigDecimal costVnd) {
    }

    /** Chấm điểm rồi xếp hạng. Danh sách trả về đã theo đúng thứ tự hiển thị. */
    public List<RecipeScore> rank(List<Scorable> scorables, RankingInputs inputs) {
        List<RecipeScore> scored = new ArrayList<>();
        for (Scorable scorable : scorables) {
            scored.add(score(scorable, inputs));
        }
        scored.sort(ordering(inputs.seed()));
        return List.copyOf(scored);
    }

    /**
     * Thứ tự hiển thị: điểm cao trước, rồi <strong>dữ liệu đầy đủ hơn</strong>, rồi seed, rồi
     * {@code recipe_id}.
     *
     * <p>Món không có điểm tổng (không tiêu chí nào dùng được) xếp cuối và giữ thứ tự ổn định theo
     * mã món — đó chính là "gợi ý cơ bản" mà lớp trên phải nói ra.</p>
     */
    private Comparator<RecipeScore> ordering(long seed) {
        Comparator<RecipeScore> byTotal = Comparator.comparing(
                RecipeScore::total, Comparator.nullsLast(Comparator.reverseOrder()));
        return byTotal
                .thenComparing(Comparator.comparingInt(RecipeScore::completeness).reversed())
                .thenComparingLong(score -> tieBreak(seed, score.recipeId()))
                .thenComparing(RecipeScore::recipeId);
    }

    /**
     * Khóa xáo trộn của các món bằng điểm.
     *
     * <p>Seed 0 trả 0 cho mọi món, nên nhánh này tắt hẳn và thứ tự rơi về {@code recipe_id} — thứ
     * tự chuẩn, tái lập được, đúng như tài liệu mô tả. Seed khác 0 cho ra một hoán vị cố định của
     * riêng seed đó.</p>
     */
    private long tieBreak(long seed, String recipeId) {
        if (seed == 0L) return 0L;
        return java.util.Objects.hash(seed, recipeId);
    }

    // -------------------------------------------------------------------------------------

    /** Chấm điểm đúng một món. Công khai vì đây là đơn vị nhỏ nhất kiểm chứng được của công thức. */
    public RecipeScore score(Scorable scorable, RankingInputs inputs) {
        RecipeCandidate candidate = scorable.candidate();
        List<ReasonNote> highlights = new ArrayList<>();

        BigDecimal nutrition = nutritionScore(scorable, inputs, highlights);
        BigDecimal preference = preferenceScore(scorable, inputs, highlights);
        BigDecimal variety = varietyScore(candidate, inputs, highlights);
        BigDecimal budget = budgetScore(scorable, inputs, highlights);
        BigDecimal convenience = convenienceScore(candidate, inputs, highlights);

        Map<RankingCriterion, BigDecimal> parts = new EnumMap<>(RankingCriterion.class);
        parts.put(RankingCriterion.NUTRITION, nutrition);
        parts.put(RankingCriterion.PREFERENCE, preference);
        parts.put(RankingCriterion.VARIETY, variety);
        parts.put(RankingCriterion.BUDGET, budget);
        parts.put(RankingCriterion.CONVENIENCE, convenience);

        Set<RankingCriterion> used = EnumSet.noneOf(RankingCriterion.class);
        parts.forEach((criterion, value) -> {
            if (value != null) used.add(criterion);
        });

        return new RecipeScore(candidate.recipeId(), candidate.recipeVersion(),
                nutrition, preference, variety, budget, convenience, combine(parts), used,
                scorable.perServing() == null ? CoverageStatus.PARTIAL
                        : scorable.perServing().coverage(),
                highlights);
    }

    /**
     * Tổng điểm, chuẩn hóa trên đúng tập tiêu chí dùng được.
     *
     * <pre>
     * S = Σ(w_i · f_i) / Σ(w_i),  i ∈ A
     * </pre>
     *
     * <p>Tách ra thành phương thức riêng vì đây là <strong>phần phải chứng minh</strong> của nhánh:
     * TC07 gọi thẳng vào đây với 80 / 95 / 70 / 90 / 85 và đòi đúng 84,5 — kiểm qua đường dựng cả
     * một món thì con số sẽ phụ thuộc vào fixture chứ không phải vào công thức.</p>
     *
     * <p>Giá trị null = tiêu chí không dùng được; trọng số của nó rơi khỏi cả tử lẫn mẫu. Trả null
     * khi không tiêu chí nào dùng được — lúc đó danh sách chỉ còn là thứ tự ổn định của bộ món mẫu
     * và lớp trên phải nói ra điều đó.</p>
     */
    public BigDecimal combine(Map<RankingCriterion, BigDecimal> parts) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (RankingCriterion criterion : RankingCriterion.values()) {
            BigDecimal value = parts.get(criterion);
            if (value == null) continue;
            weighted = weighted.add(criterion.weight().multiply(value));
            weightSum = weightSum.add(criterion.weight());
        }
        return weightSum.signum() == 0 ? null
                : weighted.divide(weightSum, MATH).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * N — mức khớp với phần năng lượng và vi chất tham chiếu của bữa.
     *
     * <p>Hai nhánh độc lập, mỗi nhánh tự mang hệ số chất lượng dữ liệu của mình:</p>
     * <pre>
     * d_k     = min(q_k / t_k, 1)
     * N_micro = 100 × Σ(ω_k · d_k) / Σ(ω_k)      (chỉ trên các chất CÓ dữ liệu)
     * E_fit   = 100 × max(0, 1 − |e − e_slot| / e_slot)
     * N       = 0,5·(N_micro × coverage_micro) + 0,5·E_fit     (khi có cả hai)
     * </pre>
     *
     * <p>{@code coverage_micro} = số chất có dữ liệu / số chất được cấu hình. Nó là
     * <strong>hệ số chất lượng dữ liệu</strong>, không phải cách thay giá trị thiếu bằng 0: mọi
     * {@code q_k} thiếu vẫn là null ở mọi nơi khác. Không có nó thì một món chỉ khai đúng một vi
     * chất và khai vừa đủ sẽ được 100 điểm dinh dưỡng, cao hơn món khai đủ sáu chất.</p>
     *
     * <p>Hệ số áp <strong>theo từng nhánh</strong> ("nhánh tương ứng" trong đặc tả) chứ không áp
     * một lần lên tổng: nhánh năng lượng chỉ có một chất, hoặc có dữ liệu hoặc không tồn tại, nên
     * nhân nó với độ phủ vi chất sẽ phạt oan.</p>
     *
     * @return null khi <strong>cả hai</strong> nhánh đều không dùng được → bỏ trọng số N
     */
    private BigDecimal nutritionScore(Scorable scorable, RankingInputs inputs,
                                      List<ReasonNote> highlights) {
        NutritionTotals totals = scorable.perServing();
        if (totals == null) return null;

        BigDecimal microScore = micronutrientFit(totals, inputs);
        BigDecimal energyScore = energyFit(totals, inputs);

        if (microScore == null && energyScore == null) {
            return null;
        }
        if (microScore != null && microScore.compareTo(new BigDecimal("60")) >= 0) {
            highlights.add(ReasonNote.of(RecommendationReason.MICRONUTRIENT_FIT));
        }
        if (energyScore != null && energyScore.compareTo(new BigDecimal("70")) >= 0) {
            highlights.add(ReasonNote.of(RecommendationReason.ENERGY_FIT));
        }
        if (microScore == null) return clamp(energyScore);
        if (energyScore == null) return clamp(microScore);
        return clamp(HALF.multiply(microScore).add(HALF.multiply(energyScore)));
    }

    private BigDecimal micronutrientFit(NutritionTotals totals, RankingInputs inputs) {
        var targets = inputs.micronutrientTargetsPerSlot();
        if (targets.isEmpty()) return null;

        BigDecimal sum = BigDecimal.ZERO;
        int withData = 0;
        for (var entry : targets.entrySet()) {
            BigDecimal target = entry.getValue();
            if (target == null || target.signum() <= 0) continue;
            Optional<NutrientTotal> total = totals.nutrient(entry.getKey());
            if (total.isEmpty() || total.get().knownValue() == null) continue;
            BigDecimal ratio = total.get().knownValue().divide(target, MATH);
            sum = sum.add(ratio.min(BigDecimal.ONE));
            withData++;
        }
        if (withData == 0) return null;

        // ω_k mặc định bằng nhau trong nhóm vi chất được cấu hình, nên Σ(ω·d)/Σ(ω) rút gọn thành
        // trung bình cộng của d trên các chất CÓ dữ liệu.
        BigDecimal mean = sum.divide(new BigDecimal(withData), MATH);
        BigDecimal coverage = new BigDecimal(withData).divide(new BigDecimal(targets.size()), MATH);
        return HUNDRED.multiply(mean).multiply(coverage);
    }

    private BigDecimal energyFit(NutritionTotals totals, RankingInputs inputs) {
        BigDecimal target = inputs.energyTargetPerSlot();
        if (target == null || target.signum() <= 0) return null;
        Optional<NutrientTotal> energy = totals.nutrient(NutritionCalculator.ENERGY);
        if (energy.isEmpty() || energy.get().knownValue() == null) return null;

        BigDecimal gap = energy.get().knownValue().subtract(target).abs().divide(target, MATH);
        BigDecimal fit = BigDecimal.ONE.subtract(gap).max(BigDecimal.ZERO);
        return HUNDRED.multiply(fit);
    }

    /**
     * P — khẩu vị. Bắt đầu ở 50, cộng trừ theo dữ liệu thật, chặn vào 0–100.
     *
     * <p>Chỉ áp <strong>một</strong> phản hồi gần nhất; các sở thích khác vẫn cộng trừ riêng.
     * Món {@code BLOCKED} không tới được đây — nó đã bị loại khỏi ứng viên ở bước lọc, không phải
     * bị trừ điểm (QĐ-03).</p>
     */
    private BigDecimal preferenceScore(Scorable scorable, RankingInputs inputs,
                                       List<ReasonNote> highlights) {
        RecipeCandidate candidate = scorable.candidate();
        BigDecimal score = PREFERENCE_BASE;

        if (inputs.favoriteRecipeIds().contains(candidate.recipeId())) {
            score = score.add(FAVORITE_BONUS);
            highlights.add(ReasonNote.of(RecommendationReason.FAVORITE));
        }
        if (!inputs.preferredCuisineTags().isEmpty()
                && inputs.preferredCuisineTags().contains(candidate.mainIngredientTag())) {
            score = score.add(CUISINE_BONUS);
            highlights.add(ReasonNote.of(RecommendationReason.MATCHES_TOLERANCE));
        }
        if (!inputs.toleranceTags().isEmpty() && matchesToleranceTags(candidate, inputs)) {
            score = score.add(TOLERANCE_BONUS);
            highlights.add(ReasonNote.of(RecommendationReason.MATCHES_TOLERANCE));
        }
        // Đúng MỘT phản hồi gần nhất còn hiệu lực, đọc từ cùng chỗ với yêu thích ở trên. Hạn bảy
        // ngày của HARD_NOW đã lọc trước khi tới đây (QĐ-03) — lớp này không biết ngày tháng.
        MealFeedbackSignal feedback = inputs.feedbackByRecipe().get(candidate.recipeId());
        if (feedback != null) {
            score = score.add(new BigDecimal(feedback.delta()));
        }
        if (hasDislikedIngredient(candidate, inputs)) {
            score = score.subtract(DISLIKED_INGREDIENT_PENALTY);
        }
        return clamp(score);
    }

    /**
     * Món có mang nhãn mùi/kết cấu người dùng dung nạp hay không.
     *
     * <p>Luôn false với danh mục hiện tại: công thức chưa có nhãn nào để đối chiếu. Giữ lại chỗ
     * này — thay vì xóa thành phần khỏi công thức — để khi seed có nhãn thì chỉ sửa đúng một
     * phương thức, và để đọc công thức điểm trong code vẫn thấy đủ năm dòng như tài liệu.</p>
     */
    private boolean matchesToleranceTags(RecipeCandidate candidate, RankingInputs inputs) {
        return false;
    }

    private boolean hasDislikedIngredient(RecipeCandidate candidate, RankingInputs inputs) {
        if (inputs.dislikedIngredientIds().isEmpty()) return false;
        for (IngredientFacts line : candidate.ingredients()) {
            if (inputs.dislikedIngredientIds().contains(line.ingredientId())) return true;
        }
        return false;
    }

    /**
     * V — đa dạng.
     *
     * <pre>
     * V = max(0, 100 − 40·I_recipe − 20·I_mainIngredient − 10·n_week)
     * </pre>
     *
     * <p>{@code n_week} <strong>không tính lần đang xét</strong> — nơi gọi truyền vào số lần món đã
     * có trong tuần đang tạo, chưa gồm vị trí đang cân nhắc.</p>
     *
     * <p>Nhãn nguyên liệu chính phục vụ đa dạng, <strong>không</strong> phục vụ phát hiện dị ứng:
     * việc đó đi qua bảng thành phần và duyệt đệ quy ở {@link EligibilityFilter}.</p>
     */
    private BigDecimal varietyScore(RecipeCandidate candidate, RankingInputs inputs,
                                    List<ReasonNote> highlights) {
        BigDecimal score = HUNDRED;
        boolean repeatedRecipe = inputs.recentRecipeIds().contains(candidate.recipeId());
        if (repeatedRecipe) {
            score = score.subtract(VARIETY_RECIPE_REPEAT);
        }
        if (candidate.mainIngredientTag() != null
                && inputs.yesterdayMainIngredientTags().contains(candidate.mainIngredientTag())) {
            score = score.subtract(VARIETY_MAIN_INGREDIENT_REPEAT);
        }
        int weekCount = inputs.weekRecipeCounts().getOrDefault(candidate.recipeId(), 0);
        score = score.subtract(VARIETY_WEEK_REPEAT.multiply(new BigDecimal(weekCount)));

        if (!repeatedRecipe && !inputs.recentRecipeIds().isEmpty()) {
            highlights.add(ReasonNote.of(RecommendationReason.NOT_RECENTLY_EATEN));
        }
        return score.max(BigDecimal.ZERO);
    }

    /**
     * B — chi phí so với ngân sách bữa.
     *
     * <pre>
     * B = clamp(100 − 100 × max(0, cost / budget_slot − 1), 0, 100)
     * </pre>
     *
     * <p>Người dùng bỏ qua ngân sách → <strong>bỏ hẳn thành phần</strong>, không tự cho 0 (TC06).
     * <strong>Giá chưa có cũng làm B không hoạt động</strong> — một món chưa ai nhập giá không phải
     * là một món đắt.</p>
     */
    private BigDecimal budgetScore(Scorable scorable, RankingInputs inputs,
                                   List<ReasonNote> highlights) {
        BigDecimal budget = inputs.budgetPerSlotVnd();
        BigDecimal cost = scorable.costVnd();
        if (budget == null || budget.signum() <= 0 || cost == null) return null;

        BigDecimal overshoot = cost.divide(budget, MATH).subtract(BigDecimal.ONE).max(BigDecimal.ZERO);
        BigDecimal score = clamp(HUNDRED.subtract(HUNDRED.multiply(overshoot)));
        if (overshoot.signum() == 0) {
            highlights.add(ReasonNote.of(RecommendationReason.WITHIN_BUDGET));
        }
        return score;
    }

    /**
     * C — thuận tiện.
     *
     * <pre>
     * C = clamp(100 − 100 × max(0, minutes / max_minutes − 1), 0, 100)
     * </pre>
     *
     * <p>Không khai giới hạn thời gian → bỏ hẳn thành phần. Đây là ưu tiên <strong>mềm</strong>
     * trừ khi người dùng bật giới hạn cứng, và lúc đó việc loại món thuộc
     * {@link EligibilityFilter} chứ không phải chỗ này (BR13).</p>
     */
    private BigDecimal convenienceScore(RecipeCandidate candidate, RankingInputs inputs,
                                        List<ReasonNote> highlights) {
        Integer maxMinutes = inputs.maxMinutes();
        if (maxMinutes == null || maxMinutes <= 0) return null;

        BigDecimal overshoot = new BigDecimal(candidate.minutes())
                .divide(new BigDecimal(maxMinutes), MATH)
                .subtract(BigDecimal.ONE)
                .max(BigDecimal.ZERO);
        BigDecimal score = clamp(HUNDRED.subtract(HUNDRED.multiply(overshoot)));
        if (overshoot.signum() == 0) {
            highlights.add(ReasonNote.of(RecommendationReason.QUICK_TO_COOK,
                    String.valueOf(candidate.minutes())));
        }
        return score;
    }

    private BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(HUNDRED).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }
}
