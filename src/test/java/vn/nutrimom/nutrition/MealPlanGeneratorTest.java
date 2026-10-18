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
import vn.nutrimom.nutrition.domain.PlanCriterion;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.TemplateMode;
import vn.nutrimom.nutrition.service.MealPlanGenerator;
import vn.nutrimom.nutrition.service.MealPlanSearch.Candidate;
import vn.nutrimom.nutrition.service.MealPlanSearch.Choice;
import vn.nutrimom.nutrition.service.MealPlanSearch.Outcome;
import vn.nutrimom.nutrition.service.MealPlanSearch.Penalties;
import vn.nutrimom.nutrition.service.MealPlanSearch.Position;
import vn.nutrimom.nutrition.service.MealPlanSearch.Slot;
import vn.nutrimom.nutrition.service.MealPlanSearch.Targets;
import vn.nutrimom.nutrition.service.NutritionCalculator;
import vn.nutrimom.nutrition.service.NutritionRulesProvider;
import vn.nutrimom.nutrition.service.NutritionSnapshots.IngredientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.NutrientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeLineSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.RankingInputs;
import vn.nutrimom.nutrition.service.RecipeCandidate;
import vn.nutrimom.nutrition.service.RecipeRanker;
import vn.nutrimom.nutrition.service.SearchDeadline;

/**
 * Bộ sinh thực đơn, kiểm bằng fixture thuần — không Spring, không database.
 *
 * <p>Đây là nơi chứng minh ba thứ không thể chứng minh qua API thật với danh mục seed hiện tại:
 * hàm mục tiêu J bỏ đúng tiêu chí thiếu dữ liệu, hạn thời gian không bị diễn giải thành "không tồn
 * tại nghiệm" (TC19), và kế hoạch một phần ngày không bị chuẩn hóa lên thành cả ngày (TC23).</p>
 */
class MealPlanGeneratorTest {

    private final NutritionCalculator calculator =
            new NutritionCalculator(new NutritionRulesProvider());
    private final MealPlanGenerator generator =
            new MealPlanGenerator(calculator, new RecipeRanker());

    private static final RankingInputs NO_PREFERENCES = new RankingInputs(
            null, Map.of(), null, null, Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Map.of(), 0L);

    // =========================================================================================
    // Hàm mục tiêu
    // =========================================================================================

    /**
     * Trọng số của tiêu chí thiếu dữ liệu bị loại khỏi <strong>cả tử lẫn mẫu</strong>.
     *
     * <p>0,40·0,5 + 0,25·0,2 = 0,25 chia cho 0,65 = 0,384615. Nếu cài đặt cho tiêu chí thiếu bằng 0
     * thì kết quả sẽ là 0,25 — khác hẳn, và theo hướng khen một phương án chưa ai đánh giá được.</p>
     */
    @Test
    void droppedCriteriaLeaveTheRemainingWeightsRenormalised() {
        BigDecimal objective = generator.objective(new Penalties(
                new BigDecimal("0.5"), new BigDecimal("0.2"), null, null, null));

        assertThat(objective).isEqualByComparingTo("0.384615");
    }

    @Test
    void anObjectiveIsNullWhenNoCriterionCanBeEvaluated() {
        assertThat(generator.objective(new Penalties(null, null, null, null, null))).isNull();
    }

    @Test
    void everyWeightIsAccountedForWhenAllFiveCriteriaHaveData() {
        BigDecimal objective = generator.objective(new Penalties(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));

        assertThat(objective).isEqualByComparingTo("1");
        BigDecimal weights = BigDecimal.ZERO;
        for (PlanCriterion criterion : PlanCriterion.values()) {
            weights = weights.add(criterion.weight());
        }
        assertThat(weights).isEqualByComparingTo("1.00");
    }

    // =========================================================================================
    // TC23 — kế hoạch một phần ngày
    // =========================================================================================

    /**
     * TC23: hai bữa không bị chuẩn hóa lên thành toàn bộ năng lượng ngày.
     *
     * <p>Cùng một thực đơn 1.200 kcal: so với mục tiêu <em>cả ngày</em> 2.000 kcal thì nó thiếu
     * 40%, còn so với phần 60% của trưa và tối (1.200 kcal) thì nó khớp. Bộ sinh phải dùng mục tiêu
     * thứ hai — nếu không, nó sẽ ép hai bữa gánh cả ngày và đẩy khẩu phần lên quá mức.</p>
     */
    @Test
    void aPartialDayPlanIsScoredAgainstThePartialTargetNotTheWholeDay() {
        List<Choice> choices = List.of(choice(0, RecipeRole.MAIN_DISH,
                candidate("rec.a", 1200, 20), new BigDecimal("1")));

        Penalties againstTwoMeals = generator.penalties(choices,
                targets(new BigDecimal("1200"), null, null), score(50), score(100));
        Penalties againstWholeDay = generator.penalties(choices,
                targets(new BigDecimal("2000"), null, null), score(50), score(100));

        assertThat(againstTwoMeals.nutrition()).isEqualByComparingTo("0");
        assertThat(againstWholeDay.nutrition()).isEqualByComparingTo("0.4");
    }

    @Test
    void theEnergyBranchIsDroppedWhenTheProfileCannotEstimateIt() {
        List<Choice> choices = List.of(choice(0, RecipeRole.MAIN_DISH,
                candidate("rec.a", 1200, 20), BigDecimal.ONE));

        Penalties penalties = generator.penalties(choices, targets(null, null, null),
                score(50), score(100));

        // Không có EER thì không có nhánh năng lượng, và fixture này chưa khai vi chất nào đủ dữ
        // liệu — cả thành phần dinh dưỡng bị bỏ, không phải bị cho 0.
        assertThat(penalties.nutrition()).isNull();
        assertThat(penalties.used()).doesNotContain(PlanCriterion.NUTRITION);
    }

    // =========================================================================================
    // TC19 — hết hạn thời gian
    // =========================================================================================

    /**
     * TC19: hết thời gian mà chưa có phương án hoàn chỉnh thì nói rõ là <em>hết thời gian tìm</em>.
     *
     * <p>{@code searchLimit} là thứ phân biệt "chưa tìm xong" với "không có nghiệm": nơi gọi dựa
     * vào đúng cờ này để chọn câu chữ, nên nếu nó sai thì người dùng nhận một lời khẳng định mà hệ
     * thống chưa có cơ sở để đưa ra.</p>
     */
    @Test
    void anExhaustedTimeBudgetReportsTheSearchLimitInsteadOfClaimingNoSolutionExists() {
        Outcome outcome = generator.generate(
                List.of(slot(MealType.LUNCH, TemplateMode.SINGLE_DISH,
                        position(RecipeRole.MAIN_DISH, candidate("rec.a", 600, 20)))),
                targets(new BigDecimal("600"), null, null), NO_PREFERENCES,
                SearchDeadline.alreadyExpired());

        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.searchLimit()).isTrue();
    }

    /** Hết ứng viên vì điều kiện cứng là chuyện khác hẳn, và không được mang nhãn hết giờ. */
    @Test
    void anImpossibleHardLimitIsNotReportedAsATimeout() {
        Outcome outcome = generator.generate(
                List.of(slot(MealType.LUNCH, TemplateMode.SINGLE_DISH,
                        position(RecipeRole.MAIN_DISH, candidate("rec.slow", 600, 90)))),
                new Targets(null, Map.of(), null, 30, false, true), NO_PREFERENCES,
                SearchDeadline.of(Duration.ofSeconds(2)));

        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.searchLimit()).isFalse();
    }

    // =========================================================================================
    // Điều kiện cứng ở mức tổ hợp
    // =========================================================================================

    /**
     * Giới hạn thời gian kiểm trên <strong>toàn bữa</strong>, không phải từng món.
     *
     * <p>Hai món 20 phút đều lọt giới hạn 30 phút khi đứng riêng, nhưng bữa gồm cả hai thì mất 40
     * phút. MVP ước tính bảo thủ: thời gian bữa là tổng thời gian các món.</p>
     */
    @Test
    void aStrictTimeLimitAppliesToTheWholeMealNotEachDish() {
        List<Slot> slots = List.of(slot(MealType.LUNCH, TemplateMode.ROLE_SET,
                position(RecipeRole.STAPLE, candidate("rec.rice", 300, 20)),
                position(RecipeRole.PROTEIN, candidate("rec.fish", 300, 20),
                        candidate("rec.egg", 300, 5))));

        Outcome outcome = generator.generate(slots,
                new Targets(null, Map.of(), null, 30, false, true), NO_PREFERENCES,
                SearchDeadline.of(Duration.ofSeconds(2)));

        assertThat(outcome.complete()).isTrue();
        // 20 + 20 vượt giới hạn, nên chỉ còn món 5 phút đi cùng được với món tinh bột.
        assertThat(recipeIds(outcome)).containsExactly("rec.rice", "rec.egg");
    }

    @Test
    void aStrictBudgetIsCheckedOnTheWholeDay() {
        List<Slot> slots = List.of(slot(MealType.LUNCH, TemplateMode.ROLE_SET,
                position(RecipeRole.STAPLE, priced(candidate("rec.rice", 300, 20), "20000")),
                position(RecipeRole.PROTEIN,
                        priced(candidate("rec.salmon", 300, 20), "90000"),
                        priced(candidate("rec.tofu", 300, 20), "15000"))));

        Outcome outcome = generator.generate(slots,
                new Targets(null, Map.of(), new BigDecimal("50000"), null, true, false),
                NO_PREFERENCES, SearchDeadline.of(Duration.ofSeconds(2)));

        assertThat(recipeIds(outcome)).containsExactly("rec.rice", "rec.tofu");
    }

    /**
     * Món chưa có giá <strong>không</strong> được coi là đã nằm trong ngân sách cứng.
     *
     * <p>Lớp trên đã loại chúng trước khi vào đây; bộ sinh vẫn chặn lần nữa, vì "không biết giá"
     * tuyệt đối không được trượt thành "giá bằng 0".</p>
     */
    @Test
    void aDishWithoutAPriceNeverPassesAStrictBudget() {
        List<Slot> slots = List.of(slot(MealType.LUNCH, TemplateMode.SINGLE_DISH,
                position(RecipeRole.MAIN_DISH, candidate("rec.unpriced", 600, 20))));

        Outcome outcome = generator.generate(slots,
                new Targets(null, Map.of(), new BigDecimal("500000"), null, true, false),
                NO_PREFERENCES, SearchDeadline.of(Duration.ofSeconds(2)));

        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.searchLimit()).isFalse();
    }

    // =========================================================================================
    // Ghép món
    // =========================================================================================

    /** Phương án có J nhỏ hơn thắng: ở đây là món khớp mức năng lượng của ngày. */
    @Test
    void theCombinationClosestToTheDayEnergyTargetWins() {
        List<Slot> slots = List.of(slot(MealType.LUNCH, TemplateMode.SINGLE_DISH,
                position(RecipeRole.MAIN_DISH,
                        candidate("rec.light", 200, 20),
                        candidate("rec.right", 600, 20),
                        candidate("rec.heavy", 2000, 20))));

        Outcome outcome = generator.generate(slots, targets(new BigDecimal("600"), null, null),
                NO_PREFERENCES, SearchDeadline.of(Duration.ofSeconds(2)));

        assertThat(recipeIds(outcome)).containsExactly("rec.right");
        assertThat(outcome.choices().get(0).servings()).isEqualByComparingTo("1");
    }

    /** Khẩu phần lấy từ tập cho phép của template, không nhân liên tục để ép đủ năng lượng. */
    @Test
    void servingsComeFromTheTemplateOptionsOnly() {
        List<Slot> slots = List.of(new Slot(MealType.LUNCH, (short) 1, TemplateMode.SINGLE_DISH,
                List.of(new Position(RecipeRole.MAIN_DISH,
                        List.of(new BigDecimal("0.5"), BigDecimal.ONE),
                        List.of(candidate("rec.only", 1200, 20))))));

        Outcome outcome = generator.generate(slots, targets(new BigDecimal("600"), null, null),
                NO_PREFERENCES, SearchDeadline.of(Duration.ofSeconds(2)));

        // 0,5 phần cho đúng 600 kcal; không có lựa chọn 0,25 nào được bịa thêm.
        assertThat(outcome.choices().get(0).servings()).isEqualByComparingTo("0.5");
    }

    /**
     * Đa dạng cập nhật <strong>sau từng lựa chọn</strong>.
     *
     * <p>Hai bữa đều có thể chọn cùng một món, và nếu điểm đa dạng lấy từ một danh sách cố định thì
     * lặp lại không bị phạt. Ở đây món thứ hai phải khác món thứ nhất, dù cả hai cùng khớp năng
     * lượng như nhau.</p>
     */
    @Test
    void varietyIsRecomputedAfterEachChoiceSoTheDayDoesNotRepeatOneDish() {
        List<Candidate> twins = List.of(candidate("rec.a", 300, 20), candidate("rec.b", 300, 20));
        List<Slot> slots = List.of(
                new Slot(MealType.LUNCH, (short) 1, TemplateMode.SINGLE_DISH,
                        List.of(new Position(RecipeRole.MAIN_DISH, List.of(BigDecimal.ONE), twins))),
                new Slot(MealType.DINNER, (short) 1, TemplateMode.SINGLE_DISH,
                        List.of(new Position(RecipeRole.MAIN_DISH, List.of(BigDecimal.ONE), twins))));

        Outcome outcome = generator.generate(slots, targets(new BigDecimal("600"), null, null),
                NO_PREFERENCES, SearchDeadline.of(Duration.ofSeconds(2)));

        assertThat(recipeIds(outcome)).containsExactlyInAnyOrder("rec.a", "rec.b");
    }

    /** Món khóa giữ nguyên vị trí và số phần người dùng đã chọn. */
    @Test
    void aLockedDishKeepsItsPlaceAndItsServings() {
        Candidate locked = new Candidate(recipeCandidate("rec.locked", 20),
                snapshot("rec.locked", 300), null, new BigDecimal("1.5"));
        List<Slot> slots = List.of(slot(MealType.LUNCH, TemplateMode.ROLE_SET,
                new Position(RecipeRole.STAPLE, List.of(new BigDecimal("1.5")), List.of(locked)),
                position(RecipeRole.PROTEIN, candidate("rec.fish", 300, 20))));

        Outcome outcome = generator.generate(slots, targets(new BigDecimal("900"), null, null),
                NO_PREFERENCES, SearchDeadline.of(Duration.ofSeconds(2)));

        Choice first = outcome.choices().get(0);
        assertThat(first.candidate().recipeId()).isEqualTo("rec.locked");
        assertThat(first.servings()).isEqualByComparingTo("1.5");
        assertThat(first.candidate().locked()).isTrue();
    }

    /** Tổng của phương án đi qua đúng một bộ tính, nên nó khớp với tổng của từng mục cộng lại. */
    @Test
    void theTotalsOfAPlanComeFromTheOneCalculator() {
        List<Choice> choices = List.of(
                choice(0, RecipeRole.STAPLE, candidate("rec.rice", 300, 20), BigDecimal.ONE),
                choice(1, RecipeRole.MAIN_DISH, candidate("rec.fish", 500, 20), BigDecimal.ONE));

        BigDecimal planEnergy = generator.totalsOf(choices)
                .nutrient(NutritionCalculator.ENERGY).orElseThrow().knownValue();
        BigDecimal perItemSum = BigDecimal.ZERO;
        for (Choice choice : choices) {
            perItemSum = perItemSum.add(calculator
                    .forPortion(choice.candidate().snapshot(), choice.servings())
                    .nutrient(NutritionCalculator.ENERGY).orElseThrow().knownValue());
        }

        assertThat(planEnergy).isEqualByComparingTo(perItemSum);
    }

    /** Mục tiêu 2 giây cho một ngày, trên cấu trúc đầy đủ nhất mà nhánh này sinh ra. */
    @Test
    void aFullDayFinishesWellInsideTheTwoSecondBudget() {
        List<Candidate> many = new ArrayList<>();
        for (int i = 0; i < MealPlanGenerator.EXPANDED_CANDIDATES_PER_POSITION; i++) {
            many.add(candidate("rec." + i, 200 + i * 20, 10 + i));
        }
        List<BigDecimal> servings = List.of(new BigDecimal("0.5"), BigDecimal.ONE,
                new BigDecimal("1.5"), new BigDecimal("2"));
        List<Slot> slots = List.of(
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

        Outcome outcome = generator.generate(slots, targets(new BigDecimal("2000"), null, null),
                NO_PREFERENCES, SearchDeadline.of(SearchDeadline.ONE_DAY_BUDGET));

        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.elapsedMillis())
                .describedAs("thời gian sinh thực tế cho một ngày, mục tiêu 2000 ms")
                .isLessThan(2000L);
    }

    // =========================================================================================
    // Fixture
    // =========================================================================================

    private List<String> recipeIds(Outcome outcome) {
        return outcome.choices().stream().map(choice -> choice.candidate().recipeId()).toList();
    }

    private BigDecimal score(int value) {
        return new BigDecimal(value);
    }

    private Targets targets(BigDecimal energy, BigDecimal budget, Integer maxMinutes) {
        return new Targets(energy, Map.of(), budget, maxMinutes, false, false);
    }

    private Slot slot(MealType mealType, TemplateMode mode, Position... positions) {
        return new Slot(mealType, (short) 1, mode, List.of(positions));
    }

    private Position position(RecipeRole role, Candidate... candidates) {
        return new Position(role, List.of(BigDecimal.ONE), List.of(candidates));
    }

    private Choice choice(int slotIndex, RecipeRole role, Candidate candidate,
                          BigDecimal servings) {
        return new Choice(slotIndex, role, candidate, servings);
    }

    private Candidate candidate(String recipeId, int kcalPerServing, int minutes) {
        return new Candidate(recipeCandidate(recipeId, minutes),
                snapshot(recipeId, kcalPerServing), null, null);
    }

    private Candidate priced(Candidate candidate, String costPerServing) {
        return new Candidate(candidate.recipe(), candidate.snapshot(),
                new BigDecimal(costPerServing), candidate.lockedServings());
    }

    private RecipeCandidate recipeCandidate(String recipeId, int minutes) {
        return new RecipeCandidate(recipeId, recipeId, 1, RecipeRole.MAIN_DISH,
                EnumSet.allOf(MealType.class), minutes, recipeId + ".tag", BigDecimal.ONE,
                List.of());
    }

    /**
     * Một món một phần, 100 g nguyên liệu khai đúng {@code kcalPerServing} kcal trên 100 g.
     *
     * <p>Chỉ khai năng lượng: các bài ở trên kiểm hàm mục tiêu, và để vi chất trống chính là cách
     * dựng lại tình huống "nhánh vi chất không đánh giá được".</p>
     */
    private RecipeSnapshot snapshot(String recipeId, int kcalPerServing) {
        IngredientSnapshot ingredient = new IngredientSnapshot(recipeId + ".ing", recipeId + " ing",
                FoodState.COOKED, FoodGroup.GRAIN,
                List.of(new NutrientSnapshot(NutritionCalculator.ENERGY,
                        new BigDecimal(kcalPerServing), "kcal", NutrientStatus.MEASURED)));
        return new RecipeSnapshot(recipeId, recipeId, 1, BigDecimal.ONE, new BigDecimal("200"),
                List.of(new RecipeLineSnapshot(ingredient, new BigDecimal("100"),
                        FoodState.COOKED)));
    }
}
