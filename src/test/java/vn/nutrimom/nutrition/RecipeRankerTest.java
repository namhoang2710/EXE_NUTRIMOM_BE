package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.MealFeedbackSignal;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RankingCriterion;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.service.NutritionTotals;
import vn.nutrimom.nutrition.service.NutritionTotals.NutrientTotal;
import vn.nutrimom.nutrition.service.RankingInputs;
import vn.nutrimom.nutrition.service.RecipeCandidate;
import vn.nutrimom.nutrition.service.RecipeRanker;
import vn.nutrimom.nutrition.service.RecipeScore;

/**
 * Công thức điểm, kiểm bằng test thuần — không Spring, không database.
 *
 * <p>Đây là phần <strong>phải chứng minh</strong> của nhánh 4: mọi con số trong
 * {@code branch-04-recommendation.md} phải tái lập được bằng fixture, nên fixture phải nhỏ và đọc
 * được bằng mắt.</p>
 */
class RecipeRankerTest {

    private final RecipeRanker ranker = new RecipeRanker();

    // ---------------------------------------------------------------------------------------
    // TC07 — ba dòng ví dụ trong tài liệu phải khớp chính xác
    // ---------------------------------------------------------------------------------------

    /**
     * TC07: 80 / 95 / 70 / 90 / 85 → <strong>84,5</strong>, không sai số.
     *
     * <pre>
     * 0,35·80 + 0,30·95 + 0,15·70 + 0,10·90 + 0,10·85
     *   = 28 + 28,5 + 10,5 + 9 + 8,5 = 84,5
     * </pre>
     */
    @Test
    void theWorkedExampleFromTheSpecAddsUpToExactly845() {
        assertThat(ranker.combine(parts("80", "95", "70", "90", "85")))
                .isEqualByComparingTo("84.5");
    }

    @Test
    void theOtherTwoWorkedExamplesAlsoMatchToTheDecimal() {
        assertThat(ranker.combine(parts("90", "60", "90", "70", "70")))
                .isEqualByComparingTo("77.0");
        assertThat(ranker.combine(parts("70", "80", "100", "100", "90")))
                .isEqualByComparingTo("82.5");
    }

    // ---------------------------------------------------------------------------------------
    // TC06 — thiếu tiêu chí thì chuẩn hóa lại, không cho 0
    // ---------------------------------------------------------------------------------------

    /**
     * TC06: không nhập ngân sách → B không hoạt động, trọng số còn lại được chuẩn hóa.
     *
     * <pre>
     * (0,35·80 + 0,30·95 + 0,15·70 + 0,10·85) / 0,90
     *   = 75,5 / 0,90 = 83,888...  →  83,889
     * </pre>
     *
     * <p>Con số này phải <strong>cao hơn</strong> kết quả của cách làm sai (cho B = 0, ra 75,5):
     * thiếu dữ liệu không được biến thành điểm kém.</p>
     */
    @Test
    void droppingBudgetRenormalisesTheRemainingWeightsInsteadOfScoringItZero() {
        Map<RankingCriterion, BigDecimal> withoutBudget = parts("80", "95", "70", null, "85");

        BigDecimal normalised = ranker.combine(withoutBudget);

        assertThat(normalised).isEqualByComparingTo("83.889");
        assertThat(normalised).isGreaterThan(ranker.combine(parts("80", "95", "70", "0", "85")));
    }

    @Test
    void everyCriterionMissingLeavesNoTotalAtAll() {
        assertThat(ranker.combine(parts(null, null, null, null, null))).isNull();
    }

    @Test
    void asingleCriterionScoresAsItself() {
        assertThat(ranker.combine(parts(null, "72", null, null, null)))
                .isEqualByComparingTo("72");
    }

    // ---------------------------------------------------------------------------------------
    // N — dinh dưỡng
    // ---------------------------------------------------------------------------------------

    /**
     * Thiếu toàn bộ dữ liệu vi chất → bỏ trọng số N và trả {@code PARTIAL}.
     *
     * <p>Không phải N = 0: một món chưa ai nhập số liệu không phải là một món nghèo dinh dưỡng.</p>
     */
    @Test
    void missingEveryMicronutrientDropsTheNutritionWeightAndReportsPartial() {
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()), inputs().build());

        assertThat(score.nutrition()).isNull();
        assertThat(score.criteriaUsed()).doesNotContain(RankingCriterion.NUTRITION);
        assertThat(score.nutritionStatus()).isEqualTo(CoverageStatus.PARTIAL);
        assertThat(score.total()).isNotNull();
    }

    /**
     * Hệ số chất lượng dữ liệu: khai đủ một nửa số chất thì điểm N chỉ còn một nửa.
     *
     * <p>Thiếu hệ số này, một món khai đúng một vi chất và khai vừa đủ sẽ được 100 điểm dinh
     * dưỡng, cao hơn món khai đủ cả sáu chất.</p>
     */
    @Test
    void coverageRatioStopsThinDataFromScoringLikeCompleteData() {
        Map<String, BigDecimal> targets = Map.of("FOLATE", new BigDecimal("180"),
                "IRON", new BigDecimal("8"));

        RecipeScore both = ranker.score(
                scorable(candidate("rec.full", 20),
                        totals(Map.of("FOLATE", "180", "IRON", "8"))),
                inputs().micronutrients(targets).build());
        RecipeScore half = ranker.score(
                scorable(candidate("rec.thin", 20), totals(Map.of("FOLATE", "180"))),
                inputs().micronutrients(targets).build());

        assertThat(both.nutrition()).isEqualByComparingTo("100");
        assertThat(half.nutrition()).isEqualByComparingTo("50");
    }

    @Test
    void exceedingATargetDoesNotScoreAboveFull() {
        // d_k = min(q_k / t_k, 1): ăn gấp ba nhu cầu không "tốt gấp ba".
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totals(Map.of("FOLATE", "540"))),
                inputs().micronutrients(Map.of("FOLATE", new BigDecimal("180"))).build());

        assertThat(score.nutrition()).isEqualByComparingTo("100");
    }

    @Test
    void energyFitFallsOffWithTheDistanceFromTheSlotTarget() {
        // |600 − 500| / 500 = 0,2  →  E_fit = 80. Không có vi chất nên N = E_fit.
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totals(Map.of("ENERGY", "600"))),
                inputs().energy(new BigDecimal("500")).build());

        assertThat(score.nutrition()).isEqualByComparingTo("80");
    }

    @Test
    void withNoEnergyTargetTheEnergyBranchIsSimplyAbsent() {
        // TC05 nối sang đây: bỏ qua bước 5 onboarding thì không có e_slot, và điểm N chỉ còn
        // nhánh vi chất — không mượn 2.000 kcal làm mục tiêu.
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totals(Map.of("FOLATE", "180", "ENERGY", "600"))),
                inputs().micronutrients(Map.of("FOLATE", new BigDecimal("180"))).build());

        assertThat(score.nutrition()).isEqualByComparingTo("100");
    }

    // ---------------------------------------------------------------------------------------
    // P — khẩu vị
    // ---------------------------------------------------------------------------------------

    @Test
    void preferenceStartsNeutralAtFifty() {
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()), inputs().build());

        assertThat(score.preference()).isEqualByComparingTo("50");
    }

    @Test
    void aFavouriteGainsTwentyAndSaysSo() {
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()),
                inputs().favorites(Set.of("rec.x")).build());

        assertThat(score.preference()).isEqualByComparingTo("70");
        assertThat(reasonCodes(score)).contains(RecommendationReason.FAVORITE);
    }

    @Test
    void aDislikedIngredientCostsThirtyFiveWithoutRemovingTheDish() {
        // Ưu tiên MỀM: hạ điểm, không loại. Việc loại thuộc EligibilityFilter và chỉ cho điều
        // kiện cứng.
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()),
                inputs().disliked(Set.of("ing.a")).build());

        assertThat(score.preference()).isEqualByComparingTo("15");
        assertThat(score.total()).isNotNull();
    }

    @Test
    void onlyTheLatestFeedbackApplies() {
        RecipeScore tasty = ranker.score(plainScorable(candidate("rec.x", 20)),
                withFeedback(MealFeedbackSignal.TASTY));
        RecipeScore dislike = ranker.score(plainScorable(candidate("rec.x", 20)),
                withFeedback(MealFeedbackSignal.DISLIKE));
        RecipeScore hardNow = ranker.score(plainScorable(candidate("rec.x", 20)),
                withFeedback(MealFeedbackSignal.HARD_NOW));

        assertThat(tasty.preference()).isEqualByComparingTo("60");
        assertThat(dislike.preference()).isEqualByComparingTo("25");
        assertThat(hardNow.preference()).isEqualByComparingTo("30");
    }

    @Test
    void preferenceIsClampedIntoZeroHundred() {
        RecipeScore floored = ranker.score(plainScorable(candidate("rec.x", 20)),
                inputs().feedback(Map.of("rec.x", MealFeedbackSignal.DISLIKE))
                        .disliked(Set.of("ing.a")).build());

        // 50 − 25 − 35 = −10 → kẹp về 0.
        assertThat(floored.preference()).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------------------------------
    // V — đa dạng
    // ---------------------------------------------------------------------------------------

    @Test
    void varietyPenalisesRepeatsExactlyAsTheFormulaSays() {
        // 100 − 40 (món lặp) − 20 (nguyên liệu chính lặp) − 10·2 (hai lần trong tuần) = 20
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()),
                inputs().recent(Set.of("rec.x"))
                        .yesterdayMain(Set.of("ing.main"))
                        .weekCounts(Map.of("rec.x", 2))
                        .build());

        assertThat(score.variety()).isEqualByComparingTo("20");
    }

    @Test
    void varietyNeverGoesNegative() {
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()),
                inputs().recent(Set.of("rec.x"))
                        .yesterdayMain(Set.of("ing.main"))
                        .weekCounts(Map.of("rec.x", 9))
                        .build());

        assertThat(score.variety()).isEqualByComparingTo("0");
    }

    @Test
    void aDishNotSeenRecentlySaysSo() {
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()),
                inputs().recent(Set.of("rec.other")).build());

        assertThat(reasonCodes(score)).contains(RecommendationReason.NOT_RECENTLY_EATEN);
    }

    // ---------------------------------------------------------------------------------------
    // B và C
    // ---------------------------------------------------------------------------------------

    @Test
    void stayingWithinBudgetScoresFullAndOvershootingCostsProportionally() {
        RecipeScore within = ranker.score(
                scorableWithCost(candidate("rec.x", 20), new BigDecimal("20000")),
                inputs().budget(new BigDecimal("30000")).build());
        // 45000 / 30000 − 1 = 0,5  →  B = 50
        RecipeScore over = ranker.score(
                scorableWithCost(candidate("rec.x", 20), new BigDecimal("45000")),
                inputs().budget(new BigDecimal("30000")).build());

        assertThat(within.budget()).isEqualByComparingTo("100");
        assertThat(reasonCodes(within)).contains(RecommendationReason.WITHIN_BUDGET);
        assertThat(over.budget()).isEqualByComparingTo("50");
    }

    /** Giá chưa có làm B <strong>không hoạt động</strong>, không phải B = 0. */
    @Test
    void anUnpricedDishDropsTheBudgetWeightInsteadOfScoringZero() {
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()),
                inputs().budget(new BigDecimal("30000")).build());

        assertThat(score.budget()).isNull();
        assertThat(score.criteriaUsed()).doesNotContain(RankingCriterion.BUDGET);
    }

    @Test
    void convenienceScoresFullWithinTheTimeLimitAndSaysHowLong() {
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()),
                inputs().maxMinutes(30).build());

        assertThat(score.convenience()).isEqualByComparingTo("100");
        assertThat(score.highlights().stream().map(note -> note.message()))
                .contains("Khoảng 20 phút nấu");
    }

    @Test
    void convenienceFallsWhenTheDishTakesLongerThanTheLimit() {
        // 60 / 30 − 1 = 1  →  C = 0, nhưng món vẫn ở lại: thời gian là ưu tiên mềm (BR13).
        RecipeScore score = ranker.score(
                scorable(candidate("rec.slow", 60), totalsWithout()),
                inputs().maxMinutes(30).build());

        assertThat(score.convenience()).isEqualByComparingTo("0");
        assertThat(score.total()).isNotNull();
    }

    @Test
    void noTimeLimitDropsTheConvenienceWeight() {
        RecipeScore score = ranker.score(
                scorable(candidate("rec.x", 20), totalsWithout()), inputs().build());

        assertThat(score.convenience()).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // Thứ tự
    // ---------------------------------------------------------------------------------------

    @Test
    void tiesFallBackToTheRecipeIdSoTheOrderIsReproducible() {
        List<RecipeScore> ranked = ranker.rank(List.of(
                scorable(candidate("rec.b", 20), totalsWithout()),
                scorable(candidate("rec.a", 20), totalsWithout())), inputs().build());

        assertThat(ranked).extracting(RecipeScore::recipeId)
                .containsExactly("rec.a", "rec.b");
    }

    @Test
    void theSameSeedAlwaysProducesTheSameOrder() {
        List<RecipeScore> first = ranker.rank(someTiedDishes(), inputs().seed(42L).build());
        List<RecipeScore> again = ranker.rank(someTiedDishes(), inputs().seed(42L).build());

        assertThat(first).extracting(RecipeScore::recipeId)
                .isEqualTo(again.stream().map(RecipeScore::recipeId).toList());
    }

    @Test
    void aDishScoredOnMoreCriteriaWinsATie() {
        // Cùng 100 điểm, nhưng món có giá được chấm trên cả B — hệ thống biết nó rõ hơn.
        List<RecipeScore> ranked = ranker.rank(List.of(
                scorable(candidate("rec.a", 20), totalsWithout()),
                scorableWithCost(candidate("rec.b", 20), new BigDecimal("1000"))),
                inputs().budget(new BigDecimal("30000")).maxMinutes(30).build());

        assertThat(ranked.get(0).recipeId()).isEqualTo("rec.b");
        assertThat(ranked.get(0).completeness()).isGreaterThan(ranked.get(1).completeness());
    }

    // ---------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------

    private List<RecipeRanker.Scorable> someTiedDishes() {
        return List.of(
                scorable(candidate("rec.a", 20), totalsWithout()),
                scorable(candidate("rec.b", 20), totalsWithout()),
                scorable(candidate("rec.c", 20), totalsWithout()));
    }

    private Map<RankingCriterion, BigDecimal> parts(String n, String p, String v, String b,
                                                    String c) {
        Map<RankingCriterion, BigDecimal> parts = new EnumMap<>(RankingCriterion.class);
        parts.put(RankingCriterion.NUTRITION, n == null ? null : new BigDecimal(n));
        parts.put(RankingCriterion.PREFERENCE, p == null ? null : new BigDecimal(p));
        parts.put(RankingCriterion.VARIETY, v == null ? null : new BigDecimal(v));
        parts.put(RankingCriterion.BUDGET, b == null ? null : new BigDecimal(b));
        parts.put(RankingCriterion.CONVENIENCE, c == null ? null : new BigDecimal(c));
        return parts;
    }

    private RecipeCandidate candidate(String recipeId, int minutes) {
        return new RecipeCandidate(recipeId, "Món " + recipeId, 1, RecipeRole.MAIN_DISH,
                Set.of(MealType.LUNCH), minutes, "ing.main", BigDecimal.ONE,
                List.of(new RecipeCandidate.IngredientFacts("ing.a", "Nguyên liệu A",
                        vn.nutrimom.nutrition.domain.FoodGroup.GRAIN,
                        vn.nutrimom.nutrition.domain.AllergenStatus.KNOWN, Set.of(), Set.of())));
    }

    private RecipeRanker.Scorable scorable(RecipeCandidate candidate, NutritionTotals totals) {
        return new RecipeRanker.Scorable(candidate, totals, null);
    }

    private RecipeRanker.Scorable scorableWithCost(RecipeCandidate candidate, BigDecimal cost) {
        return new RecipeRanker.Scorable(candidate, totalsWithout(), cost);
    }

    /** Phản hồi nằm ở {@link RankingInputs}, nên nó đi vào qua ngữ cảnh chấm điểm, không qua món. */
    private RankingInputs withFeedback(MealFeedbackSignal feedback) {
        return inputs().feedback(Map.of("rec.x", feedback)).build();
    }

    private RecipeRanker.Scorable plainScorable(RecipeCandidate candidate) {
        return new RecipeRanker.Scorable(candidate, totalsWithout(), null);
    }

    /** Món chưa có số liệu vi chất nào — null, không phải 0. */
    private NutritionTotals totalsWithout() {
        return new NutritionTotals("test-rules", Map.of("FOLATE",
                new NutrientTotal("FOLATE", "mcg_dfe", null, List.of("ing.a"),
                        CoverageStatus.PARTIAL)), List.of());
    }

    private NutritionTotals totals(Map<String, String> amounts) {
        Map<String, NutrientTotal> byNutrient = new java.util.LinkedHashMap<>();
        amounts.forEach((code, amount) -> byNutrient.put(code,
                new NutrientTotal(code, "unit", new BigDecimal(amount), List.of(),
                        CoverageStatus.COMPLETE)));
        return new NutritionTotals("test-rules", byNutrient, List.of());
    }

    private List<RecommendationReason> reasonCodes(RecipeScore score) {
        return score.highlights().stream().map(note -> note.reason()).toList();
    }

    private InputsBuilder inputs() {
        return new InputsBuilder();
    }

    /** Dựng {@link RankingInputs} mà không phải đếm mười hai tham số mỗi lần. */
    private static final class InputsBuilder {
        private BigDecimal energy;
        private Map<String, BigDecimal> micronutrients = Map.of();
        private BigDecimal budget;
        private Integer maxMinutes;
        private Set<String> favorites = Set.of();
        private Map<String, MealFeedbackSignal> feedback = Map.of();
        private Set<String> disliked = Set.of();
        private Set<String> recent = Set.of();
        private Set<String> yesterdayMain = Set.of();
        private Map<String, Integer> weekCounts = Map.of();
        private long seed;

        InputsBuilder energy(BigDecimal value) { energy = value; return this; }
        InputsBuilder micronutrients(Map<String, BigDecimal> value) {
            micronutrients = value; return this;
        }
        InputsBuilder budget(BigDecimal value) { budget = value; return this; }
        InputsBuilder maxMinutes(Integer value) { maxMinutes = value; return this; }
        InputsBuilder favorites(Set<String> value) { favorites = value; return this; }
        InputsBuilder feedback(Map<String, MealFeedbackSignal> value) {
            feedback = value; return this;
        }
        InputsBuilder disliked(Set<String> value) { disliked = value; return this; }
        InputsBuilder recent(Set<String> value) { recent = value; return this; }
        InputsBuilder yesterdayMain(Set<String> value) { yesterdayMain = value; return this; }
        InputsBuilder weekCounts(Map<String, Integer> value) { weekCounts = value; return this; }
        InputsBuilder seed(long value) { seed = value; return this; }

        RankingInputs build() {
            return new RankingInputs(energy, micronutrients, budget, maxMinutes, favorites,
                    feedback, disliked, Set.of(), Set.of(), recent, yesterdayMain, weekCounts,
                    seed);
        }
    }
}
