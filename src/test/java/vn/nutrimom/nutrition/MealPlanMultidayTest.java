package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.NutrientStatus;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.TemplateMode;
import vn.nutrimom.nutrition.service.MealPlanGenerator;
import vn.nutrimom.nutrition.service.MealPlanSearch.Candidate;
import vn.nutrimom.nutrition.service.MealPlanSearch.Choice;
import vn.nutrimom.nutrition.service.MealPlanSearch.Outcome;
import vn.nutrimom.nutrition.service.MealPlanSearch.Position;
import vn.nutrimom.nutrition.service.MealPlanSearch.Slot;
import vn.nutrimom.nutrition.service.MealPlanSearch.Targets;
import vn.nutrimom.nutrition.service.MealPlanSearch.WeeklyAllowance;
import vn.nutrimom.nutrition.service.MealPlanSearch.WeeklyAllowance.Entry;
import vn.nutrimom.nutrition.service.NutritionCalculator;
import vn.nutrimom.nutrition.service.NutritionRules.WeeklyFoodLimit;
import vn.nutrimom.nutrition.service.NutritionRulesProvider;
import vn.nutrimom.nutrition.service.NutritionSnapshots.IngredientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.NutrientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeLineSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.PlanVarietyHistory;
import vn.nutrimom.nutrition.service.RankingInputs;
import vn.nutrimom.nutrition.service.RecipeCandidate;
import vn.nutrimom.nutrition.service.RecipeRanker;
import vn.nutrimom.nutrition.service.RecipeScore;
import vn.nutrimom.nutrition.service.SearchDeadline;
import vn.nutrimom.nutrition.service.WeeklyFoodLimits;

/**
 * Thực đơn ba và bảy ngày (nhánh 9), kiểm bằng fixture thuần — không Spring, không database.
 *
 * <p>Ba thứ chỉ tồn tại khi có nhiều hơn một ngày, và vì vậy không nhánh nào trước kiểm được:
 * điều kiện cứng đo trên <strong>cả kế hoạch</strong>, đa dạng mang lịch sử từ ngày trước sang
 * ngày sau, và hạn thời gian của một tuần.</p>
 */
class MealPlanMultidayTest {

    private final NutritionCalculator calculator =
            new NutritionCalculator(new NutritionRulesProvider());
    private final RecipeRanker ranker = new RecipeRanker();
    private final MealPlanGenerator generator = new MealPlanGenerator(calculator, ranker);
    private final WeeklyFoodLimits weeklyLimits = new WeeklyFoodLimits(calculator);

    private static final RankingInputs NO_PREFERENCES = new RankingInputs(
            null, Map.of(), null, null, Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Map.of(), 0L);

    /** Mã nguyên liệu cá trong seed thật — nhóm {@code SEAFOOD_BEST_CHOICE} khai đích danh nó. */
    private static final String SALMON = "ing.ca-hoi";
    /** Cùng nhóm thực phẩm MEAT_FISH_EGG với cá, nhưng KHÔNG thuộc nhóm giới hạn. */
    private static final String CHICKEN = "ing.thit-ga-uc";

    // =========================================================================================
    // Giới hạn theo tuần — tổng của cả kế hoạch, theo nhóm dữ liệu
    // =========================================================================================

    /**
     * Mức của tuần đo trên <strong>tổng cả kế hoạch</strong>, không phải trên từng ngày.
     *
     * <p>Mỗi phần cá 120 g, mức 340 g: hai ngày đầu còn chọn được cá, ngày thứ ba thì không —
     * 360 g đã vượt. Không ngày nào trong ba ngày đó tự mình vượt mức; chỉ tổng mới vượt, và đó
     * chính là điều một phép kiểm theo ngày không bao giờ thấy.</p>
     */
    @Test
    void aWeeklyLimitIsMeasuredOnTheWholePlanNotOnOneDay() {
        WeeklyAllowance allowance = allowance("340", Map.of("rec.fish", new BigDecimal("120")));
        List<Slot> day = List.of(slot(MealType.DINNER, TemplateMode.SINGLE_DISH,
                position(RecipeRole.MAIN_DISH, candidate("rec.fish", 600, 20),
                        candidate("rec.tofu", 600, 20))));

        List<String> chosen = new ArrayList<>();
        for (int date = 0; date < 3; date++) {
            Outcome outcome = generator.generate(day, targets(new BigDecimal("600")),
                    NO_PREFERENCES, SearchDeadline.of(Duration.ofSeconds(2)), allowance);
            assertThat(outcome.complete()).isTrue();
            chosen.add(outcome.choices().get(0).candidate().recipeId());
            allowance = allowance.consume(outcome.choices());
        }

        assertThat(chosen).containsExactly("rec.fish", "rec.fish", "rec.tofu");
        assertThat(allowance.entries().get(0).consumedGram()).isEqualByComparingTo("240");
        assertThat(allowance.exceeded()).isEmpty();
    }

    /** Hết mức thì hết, kể cả khi đó là ứng viên duy nhất: điều kiện cứng không nới ra. */
    @Test
    void aPlanWithNoAlternativeLeftReportsNoMatchRatherThanBreakingTheLimit() {
        WeeklyAllowance allowance = allowance("100", Map.of("rec.fish", new BigDecimal("120")));
        List<Slot> day = List.of(slot(MealType.DINNER, TemplateMode.SINGLE_DISH,
                position(RecipeRole.MAIN_DISH, candidate("rec.fish", 600, 20))));

        Outcome outcome = generator.generate(day, targets(new BigDecimal("600")), NO_PREFERENCES,
                SearchDeadline.of(Duration.ofSeconds(2)), allowance);

        assertThat(outcome.complete()).isFalse();
        // Không phải hết giờ: mọi tổ hợp đều vi phạm một điều kiện cứng.
        assertThat(outcome.searchLimit()).isFalse();
    }

    /**
     * Mức tính theo <strong>gram nguyên liệu</strong>, nên số phần cũng tính.
     *
     * <p>Một phần 120 g lọt mức 200 g, hai phần thì không. Kiểm theo "số lần xuất hiện của món"
     * sẽ cho cả hai qua như nhau.</p>
     */
    @Test
    void servingsCountTowardsTheLimitBecauseItIsMeasuredInGrams() {
        WeeklyAllowance allowance = allowance("200", Map.of("rec.fish", new BigDecimal("120")));
        List<Slot> day = List.of(new Slot(MealType.DINNER, (short) 1, TemplateMode.SINGLE_DISH,
                List.of(new Position(RecipeRole.MAIN_DISH,
                        List.of(BigDecimal.ONE, new BigDecimal("2")),
                        List.of(candidate("rec.fish", 300, 20))))));

        Outcome outcome = generator.generate(day, targets(new BigDecimal("600")), NO_PREFERENCES,
                SearchDeadline.of(Duration.ofSeconds(2)), allowance);

        // 2 phần khớp mục tiêu 600 kcal hơn hẳn, nhưng 240 g vượt mức 200 g.
        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.choices().get(0).servings()).isEqualByComparingTo("1");
    }

    /**
     * Nhóm là <strong>nhóm dữ liệu</strong>, không phải tên món và cũng không phải nhóm thực phẩm.
     *
     * <p>Ba món: một món tên vô thưởng vô phạt nhưng có cá, một món tên có chữ "cá" mà nguyên liệu
     * là thịt gà, và một món thịt gà. Chỉ món đầu tiên tính vào mức — tên món không tham gia phép
     * so sánh nào, còn {@link FoodGroup#MEAT_FISH_EGG} thì gộp cả thịt lẫn cá nên không dùng để
     * nhận ra cá được.</p>
     */
    @Test
    void theLimitFollowsTheIngredientGroupNotTheDishNameAndNotTheFoodGroup() {
        WeeklyAllowance allowance = weeklyLimits.allowanceFor(List.of(
                snapshotOf("rec.bowl-of-the-day", "Cơm trưa văn phòng", SALMON, "150"),
                snapshotOf("rec.fake-fish", "Cá kho tộ (nấu bằng gà)", CHICKEN, "150"),
                snapshotOf("rec.chicken", "Gà kho gừng", CHICKEN, "150")));

        Entry seafood = entryOf(allowance, "SEAFOOD_BEST_CHOICE");
        assertThat(seafood.gramPerServingByRecipe()).containsOnlyKeys("rec.bowl-of-the-day");
        assertThat(seafood.gramOf("rec.bowl-of-the-day", BigDecimal.ONE))
                .isEqualByComparingTo("150");
        assertThat(seafood.gramOf("rec.fake-fish", BigDecimal.ONE)).isEqualByComparingTo("0");
    }

    /** Seed khai nhóm bằng danh sách mã nguyên liệu, mỗi nhóm một dòng và một mức riêng. */
    @Test
    void theSeedDeclaresEachSeafoodGroupAsItsOwnRowWithItsOwnLimit() {
        List<WeeklyFoodLimit> limits = weeklyLimits.limits();

        assertThat(limits).isNotEmpty();
        for (WeeklyFoodLimit limit : limits) {
            assertThat(limit.ingredientIds())
                    .describedAs("nhóm %s phải khai đích danh nguyên liệu", limit.code())
                    .isNotEmpty();
            assertThat(limit.maxGramPerWeek().signum()).isPositive();
        }
        assertThat(limits.stream().anyMatch(limit -> limit.covers(SALMON))).isTrue();
        assertThat(limits.stream().anyMatch(limit -> limit.covers(CHICKEN))).isFalse();
    }

    /** Nhóm chạm mức được nói ra, kèm con số của cả kế hoạch. */
    @Test
    void reachingTheWeeklyLimitIsReportedWithTheRealNumbers() {
        WeeklyAllowance allowance = allowance("340", Map.of("rec.fish", new BigDecimal("170")))
                .consume("rec.fish", new BigDecimal("2"));

        var notes = weeklyLimits.notes(allowance);

        assertThat(notes).extracting(note -> note.reason().name())
                .containsExactly("WEEKLY_FOOD_LIMIT_CHECKED", "WEEKLY_FOOD_LIMIT_REACHED");
        assertThat(notes.get(0).message()).contains("340/340 g");
    }

    /** Kế hoạch không chạm tới nhóm nào thì không có gì để báo cáo về nhóm đó. */
    @Test
    void aPlanWithoutAnyLimitedFoodSaysNothingAboutIt() {
        assertThat(weeklyLimits.notes(allowance("340", Map.of()))).isEmpty();
    }

    // =========================================================================================
    // Đa dạng liên ngày
    // =========================================================================================

    /**
     * Món ăn hôm trước mất <strong>đúng 40 điểm</strong> V — thành phần {@code I_recipe}.
     *
     * <p>Đo bằng cách bỏ đúng một yếu tố: cùng bối cảnh của ngày thứ hai, một lần có danh sách
     * "hai ngày gần đây" và một lần không. Chênh lệch phải là 40 chẵn, không lẫn với −20 của
     * nguyên liệu chính hay −10 của số lần trong tuần.</p>
     */
    @Test
    void aDishEatenYesterdayLosesExactlyFortyVarietyPoints() {
        Candidate repeated = candidate("rec.a", 600, 20);
        PlanVarietyHistory afterDayOne = PlanVarietyHistory.empty()
                .after(List.of(new Choice(0, RecipeRole.MAIN_DISH, repeated, BigDecimal.ONE)));

        RankingInputs withHistory = afterDayOne.applyTo(NO_PREFERENCES);
        RankingInputs withoutRecentDays = new RankingInputs(null, Map.of(), null, null, Set.of(),
                Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                withHistory.yesterdayMainIngredientTags(), withHistory.weekRecipeCounts(), 0L);

        RecipeScore penalised = score(repeated, withHistory);
        RecipeScore unpenalised = score(repeated, withoutRecentDays);

        assertThat(unpenalised.variety().subtract(penalised.variety()))
                .isEqualByComparingTo("40");
        assertThat(afterDayOne.recentRecipeIds()).containsExactly("rec.a");
    }

    /** Lịch sử chỉ nhớ hai ngày gần đây: ngày thứ tư không còn bị trừ vì món của ngày thứ nhất. */
    @Test
    void theRecipeRepeatWindowIsTwoDaysWide() {
        PlanVarietyHistory history = PlanVarietyHistory.empty()
                .after(List.of(choice(candidate("rec.a", 600, 20))))
                .after(List.of(choice(candidate("rec.b", 600, 20))))
                .after(List.of(choice(candidate("rec.c", 600, 20))));

        assertThat(history.recentRecipeIds()).containsExactlyInAnyOrder("rec.b", "rec.c");
        // n_week vẫn đếm cả ba: "tuần đang tạo" rộng hơn "hai ngày gần đây".
        assertThat(history.weekRecipeCounts()).containsKeys("rec.a", "rec.b", "rec.c");
    }

    /** Số lần trong tuần cộng dồn qua các ngày, nên lần thứ ba bị phạt nặng hơn lần thứ hai. */
    @Test
    void theWeekCountGrowsWithEachDayThatRepeatsADish() {
        Candidate repeated = candidate("rec.a", 600, 20);
        PlanVarietyHistory history = PlanVarietyHistory.empty()
                .after(List.of(choice(repeated)))
                .after(List.of(choice(repeated)));

        assertThat(history.weekRecipeCounts()).containsEntry("rec.a", 2);
        // 100 − 40 (hai ngày gần đây) − 20 (nguyên liệu chính hôm trước) − 10·2 = 20.
        assertThat(score(repeated, history.applyTo(NO_PREFERENCES)).variety())
                .isEqualByComparingTo("20");
    }

    /** Lịch sử của các ngày trước thật sự tới được bộ sinh, không dừng ở lớp gọi. */
    @Test
    void theGeneratorAvoidsADishTheEarlierDaysAlreadyUsed() {
        Candidate used = candidate("rec.a", 600, 20);
        PlanVarietyHistory history = PlanVarietyHistory.empty()
                .after(List.of(choice(used)));
        List<Slot> day = List.of(slot(MealType.DINNER, TemplateMode.SINGLE_DISH,
                position(RecipeRole.MAIN_DISH, used, candidate("rec.b", 600, 20))));

        Outcome outcome = generator.generate(day, targets(new BigDecimal("600")),
                history.applyTo(NO_PREFERENCES), SearchDeadline.of(Duration.ofSeconds(2)));

        // Hai món khớp năng lượng như nhau; thứ duy nhất khác là rec.a vừa được ăn hôm qua.
        assertThat(outcome.choices().get(0).candidate().recipeId()).isEqualTo("rec.b");
    }

    /** Ghi chú lặp món nói đúng tên món đã lặp. */
    @Test
    void aDishRepeatedAcrossTwoDaysIsNamedInTheRepeatNote() {
        Candidate repeated = candidate("rec.a", 600, 20);
        PlanVarietyHistory afterDayOne = PlanVarietyHistory.empty()
                .after(List.of(choice(repeated)));

        assertThat(afterDayOne.repeatsOf(List.of(choice(repeated)))).containsExactly("rec.a");
        assertThat(afterDayOne.repeatsOf(List.of(choice(candidate("rec.b", 600, 20))))).isEmpty();
    }

    /**
     * Món người dùng <strong>chủ động lập lịch lặp</strong> vẫn được giữ, và không sinh ghi chú.
     *
     * <p>Khóa một món ở một bữa nghĩa là họ muốn ăn nó mỗi ngày. Hạ điểm những món còn lại vì nó
     * lặp, hay báo cho họ biết đúng điều họ vừa yêu cầu, đều là trả lời một câu hỏi khác.</p>
     */
    @Test
    void aDishTheUserPinnedToRepeatIsNeitherPenalisedNorReported() {
        Candidate pinned = locked(candidate("rec.a", 600, 20), BigDecimal.ONE);
        PlanVarietyHistory afterDayOne = PlanVarietyHistory.empty()
                .after(List.of(choice(pinned)));

        assertThat(afterDayOne.recentRecipeIds()).isEmpty();
        assertThat(afterDayOne.weekRecipeCounts()).isEmpty();
        assertThat(afterDayOne.repeatsOf(List.of(choice(pinned)))).isEmpty();
    }

    // =========================================================================================
    // Hiệu năng
    // =========================================================================================

    /**
     * Bảy ngày trong <strong>5 giây</strong>, trên cấu trúc đầy đủ nhất mà API nhận.
     *
     * <p>Ba bữa chính theo tập vai trò, ba bữa phụ, 30 ứng viên và bốn mức khẩu phần cho mỗi vị
     * trí — tức là trần của tham số nhánh. Hạn là hạn của cả kế hoạch, nên bảy ngày chia nhau một
     * đồng hồ: con số in ra ở phần mô tả là số đo thật của lần chạy này.</p>
     */
    @Test
    void sevenDaysFinishInsideTheFiveSecondBudget() {
        List<Candidate> many = new ArrayList<>();
        for (int i = 0; i < MealPlanGenerator.EXPANDED_CANDIDATES_PER_POSITION; i++) {
            many.add(candidate("rec." + i, 200 + i * 20, 10 + i));
        }
        List<BigDecimal> servings = List.of(new BigDecimal("0.5"), BigDecimal.ONE,
                new BigDecimal("1.5"), new BigDecimal("2"));
        List<Slot> day = List.of(
                new Slot(MealType.BREAKFAST, (short) 1, TemplateMode.ROLE_SET, List.of(
                        new Position(RecipeRole.STAPLE, servings, many),
                        new Position(RecipeRole.PROTEIN, servings, many))),
                new Slot(MealType.LUNCH, (short) 1, TemplateMode.ROLE_SET, List.of(
                        new Position(RecipeRole.STAPLE, servings, many),
                        new Position(RecipeRole.PROTEIN, servings, many),
                        new Position(RecipeRole.VEGETABLE, servings, many))),
                new Slot(MealType.DINNER, (short) 1, TemplateMode.ROLE_SET, List.of(
                        new Position(RecipeRole.STAPLE, servings, many),
                        new Position(RecipeRole.PROTEIN, servings, many),
                        new Position(RecipeRole.VEGETABLE, servings, many))),
                new Slot(MealType.SNACK, (short) 1, TemplateMode.SINGLE_DISH,
                        List.of(new Position(RecipeRole.SNACK, servings, many))),
                new Slot(MealType.SNACK, (short) 2, TemplateMode.SINGLE_DISH,
                        List.of(new Position(RecipeRole.SNACK, servings, many))),
                new Slot(MealType.SNACK, (short) 3, TemplateMode.SINGLE_DISH,
                        List.of(new Position(RecipeRole.SNACK, servings, many))));

        SearchDeadline deadline = SearchDeadline.of(SearchDeadline.MULTI_DAY_BUDGET);
        PlanVarietyHistory history = PlanVarietyHistory.empty();
        for (int date = 0; date < 7; date++) {
            Outcome outcome = generator.generate(day, targets(new BigDecimal("2000")),
                    history.applyTo(NO_PREFERENCES), deadline);
            assertThat(outcome.complete())
                    .describedAs("ngày thứ %s phải ghép xong", date + 1)
                    .isTrue();
            history = history.after(outcome.choices());
        }

        assertThat(deadline.elapsed().toMillis())
                .describedAs("thời gian sinh thực tế cho bảy ngày, mục tiêu 5000 ms")
                .isLessThan(SearchDeadline.MULTI_DAY_BUDGET.toMillis());
    }

    /** Hạn là của cả kế hoạch: hết giờ ở ngày thứ tư cũng là hết giờ, không phải hết nghiệm. */
    @Test
    void runningOutOfTimeOnALaterDayIsStillASearchLimit() {
        Outcome outcome = generator.generate(
                List.of(slot(MealType.LUNCH, TemplateMode.SINGLE_DISH,
                        position(RecipeRole.MAIN_DISH, candidate("rec.a", 600, 20)))),
                targets(new BigDecimal("600")), NO_PREFERENCES, SearchDeadline.alreadyExpired(),
                allowance("340", Map.of()));

        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.searchLimit()).isTrue();
    }

    // =========================================================================================
    // Fixture
    // =========================================================================================

    private Entry entryOf(WeeklyAllowance allowance, String code) {
        return allowance.entries().stream()
                .filter(entry -> entry.code().equals(code))
                .findFirst().orElseThrow();
    }

    private WeeklyAllowance allowance(String maxGram, Map<String, BigDecimal> gramPerServing) {
        return new WeeklyAllowance(List.of(new Entry("TEST_GROUP", "nhóm thử",
                new BigDecimal(maxGram), BigDecimal.ZERO, gramPerServing)));
    }

    private RecipeScore score(Candidate candidate, RankingInputs inputs) {
        return ranker.score(new RecipeRanker.Scorable(candidate.recipe(), null, null), inputs);
    }

    private Targets targets(BigDecimal energy) {
        return new Targets(energy, Map.of(), null, null, false, false);
    }

    private Slot slot(MealType mealType, TemplateMode mode, Position... positions) {
        return new Slot(mealType, (short) 1, mode, List.of(positions));
    }

    private Position position(RecipeRole role, Candidate... candidates) {
        return new Position(role, List.of(BigDecimal.ONE), List.of(candidates));
    }

    private Choice choice(Candidate candidate) {
        return new Choice(0, RecipeRole.MAIN_DISH, candidate, BigDecimal.ONE);
    }

    private Candidate candidate(String recipeId, int kcalPerServing, int minutes) {
        RecipeCandidate recipe = new RecipeCandidate(recipeId, recipeId, 1, RecipeRole.MAIN_DISH,
                EnumSet.allOf(MealType.class), minutes, recipeId + ".tag", BigDecimal.ONE,
                List.of());
        return new Candidate(recipe, snapshot(recipeId, recipeId, recipeId + ".ing",
                new BigDecimal(kcalPerServing), "100"), null, null);
    }

    private Candidate locked(Candidate candidate, BigDecimal servings) {
        return new Candidate(candidate.recipe(), candidate.snapshot(), candidate.costPerServing(),
                servings);
    }

    /** Một món một phần, dựng quanh đúng một mã nguyên liệu và một lượng gram. */
    private RecipeSnapshot snapshotOf(String recipeId, String name, String ingredientId,
                                      String gram) {
        return snapshot(recipeId, name, ingredientId, new BigDecimal("100"), gram);
    }

    private RecipeSnapshot snapshot(String recipeId, String name, String ingredientId,
                                    BigDecimal kcalPer100g, String gram) {
        IngredientSnapshot ingredient = new IngredientSnapshot(ingredientId, ingredientId,
                FoodState.RAW, FoodGroup.MEAT_FISH_EGG,
                List.of(new NutrientSnapshot(NutritionCalculator.ENERGY, kcalPer100g, "kcal",
                        NutrientStatus.MEASURED)));
        return new RecipeSnapshot(recipeId, name, 1, BigDecimal.ONE, new BigDecimal("200"),
                List.of(new RecipeLineSnapshot(ingredient, new BigDecimal(gram), FoodState.RAW)));
    }
}
