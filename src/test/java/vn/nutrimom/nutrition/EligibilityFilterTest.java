package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.AllergyDeclarationStatus;
import vn.nutrimom.nutrition.domain.DietPattern;
import vn.nutrimom.nutrition.domain.EligibilityStatus;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.service.EligibilityFilter;
import vn.nutrimom.nutrition.service.EligibilityVerdict;
import vn.nutrimom.nutrition.service.ProfileConstraints;
import vn.nutrimom.nutrition.service.RecipeCandidate;
import vn.nutrimom.nutrition.service.RecipeCandidate.IngredientFacts;

/**
 * Bước lọc điều kiện, kiểm bằng test thuần.
 *
 * <p>Fixture cố tình nhỏ và dựng tay: tình huống đáng sợ nhất của TC01 là "thành phần gây dị ứng
 * nằm <strong>bên trong</strong> một nguyên liệu khác", và nó chỉ chứng minh được khi món
 * <em>không</em> chứa thẳng thứ gây dị ứng. Trong seed thật, món canh bí đỏ tôm vừa có nước dùng
 * tôm vừa có tôm sú, nên nó không phân biệt được hai đường — bài này dựng riêng món chỉ có nước
 * dùng.</p>
 */
class EligibilityFilterTest {

    private static final String SHRIMP = "alg.giap-xac";

    private final EligibilityFilter filter = new EligibilityFilter();

    // ---------------------------------------------------------------------------------------
    // TC01 — dị ứng qua nguyên liệu con
    // ---------------------------------------------------------------------------------------

    /**
     * TC01: dị ứng tôm, món chỉ có <strong>nước dùng</strong> nấu từ tôm khô → bị loại.
     *
     * <p>Danh sách nguyên liệu của món không hề viết chữ "tôm". Kiểm một tầng là lọt lưới.</p>
     */
    @Test
    void anAllergenHidingInsideABrothStillBlocksTheDish() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate soup = recipe("rec.canh", 25, catalog.get("ing.nuoc-dung-tom"));

        EligibilityVerdict verdict = filter.judge(soup, allergicToShrimp(), catalog,
                Optional.empty());

        assertThat(verdict.status()).isEqualTo(EligibilityStatus.BLOCKED);
        assertThat(reasons(verdict)).containsExactly(RecommendationReason.ALLERGEN_VIA_COMPONENT);
        // Lý do nói tên nguyên liệu người dùng ĐỌC ĐƯỢC trong công thức, không phải tên thứ nằm bên trong.
        assertThat(verdict.reasons().get(0).message()).contains("Nước dùng tôm");
    }

    @Test
    void anAllergenThreeLevelsDownIsStillFound() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        catalog.put("ing.sot-pha-san", facts("ing.sot-pha-san", "Sốt pha sẵn", FoodGroup.SEASONING,
                AllergenStatus.KNOWN, Set.of(), Set.of("ing.nuoc-dung-tom")));
        RecipeCandidate dish = recipe("rec.sot", 15, catalog.get("ing.sot-pha-san"));

        assertThat(filter.judge(dish, allergicToShrimp(), catalog, Optional.empty()).status())
                .isEqualTo(EligibilityStatus.BLOCKED);
    }

    @Test
    void anAllergenWrittenStraightIntoTheRecipeNamesItself() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate dish = recipe("rec.tom", 15, catalog.get("ing.tom-kho"));

        EligibilityVerdict verdict = filter.judge(dish, allergicToShrimp(), catalog,
                Optional.empty());

        assertThat(reasons(verdict)).containsExactly(RecommendationReason.ALLERGEN_MATCH);
    }

    @Test
    void aComponentCycleInTheCatalogDoesNotHangTheFilter() {
        // Database chặn được nguyên liệu tự nhận mình làm con, nhưng không chặn vòng A→B→A.
        Map<String, IngredientFacts> catalog = new LinkedHashMap<>();
        catalog.put("ing.a", facts("ing.a", "A", FoodGroup.OTHER, AllergenStatus.KNOWN, Set.of(),
                Set.of("ing.b")));
        catalog.put("ing.b", facts("ing.b", "B", FoodGroup.OTHER, AllergenStatus.KNOWN, Set.of(),
                Set.of("ing.a")));
        RecipeCandidate dish = recipe("rec.cycle", 10, catalog.get("ing.a"));

        assertThat(filter.judge(dish, allergicToShrimp(), catalog, Optional.empty()).status())
                .isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    // ---------------------------------------------------------------------------------------
    // TC02 — sở thích không vượt được dị ứng
    // ---------------------------------------------------------------------------------------

    /**
     * TC02: món yêu thích chứa thành phần gây dị ứng → yêu thích <strong>không</strong> làm nó
     * hợp lệ.
     *
     * <p>Bằng chứng mạnh nhất nằm ở kiểu dữ liệu: {@link ProfileConstraints} <em>không có chỗ
     * nào</em> để truyền món yêu thích vào, nên bước lọc không thể nhìn thấy sở thích kể cả khi ai
     * đó muốn. Bài này khóa hành vi đó lại để một lần refactor sau không lặng lẽ mở ra.</p>
     */
    @Test
    void markingTheDishAsFavouriteCannotMakeAnAllergenDishEligible() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate soup = recipe("rec.canh", 25, catalog.get("ing.nuoc-dung-tom"));

        EligibilityVerdict verdict = filter.judge(soup, allergicToShrimp(), catalog,
                Optional.empty());

        assertThat(verdict.eligible()).isFalse();
        assertThat(ProfileConstraints.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("favoriteRecipeIds");
    }

    @Test
    void aDishTheUserBlockedLeavesTheCandidateSetEntirely() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate dish = recipe("rec.khong-thich", 15, catalog.get("ing.gao"));
        ProfileConstraints profile = new ProfileConstraints(Set.of(),
                AllergyDeclarationStatus.KNOWN_NONE, null, Set.of(), Set.of("rec.khong-thich"),
                null, null);

        EligibilityVerdict verdict = filter.judge(dish, profile, catalog, Optional.empty());

        // Loại khỏi ứng viên, KHÔNG phải trừ điểm P (QĐ-03).
        assertThat(verdict.status()).isEqualTo(EligibilityStatus.BLOCKED);
        assertThat(reasons(verdict)).containsExactly(RecommendationReason.USER_BLOCKED_RECIPE);
    }

    // ---------------------------------------------------------------------------------------
    // TC03 — thành phần chưa rõ
    // ---------------------------------------------------------------------------------------

    /**
     * TC03: gia vị chưa rà soát thành phần → không vào thực đơn cá nhân hóa.
     *
     * <p>Kết luận là {@code UNKNOWN} chứ không phải {@code BLOCKED}: chưa rõ khác có hại, và món
     * vẫn tra cứu được ở {@code GET /api/v1/recipes}.</p>
     */
    @Test
    void anUnscreenedSeasoningKeepsTheDishOutOfThePersonalisedListAsUnknown() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate dish = recipe("rec.canh-nem", 12, catalog.get("ing.gia-vi-nem-san"));

        EligibilityVerdict verdict = filter.judge(dish, noAllergies(), catalog, Optional.empty());

        assertThat(verdict.status()).isEqualTo(EligibilityStatus.UNKNOWN);
        assertThat(reasons(verdict)).containsExactly(RecommendationReason.UNSCREENED_INGREDIENT);
    }

    @Test
    void aKnownIngredientWithAnEmptyAllergenListIsPerfectlyUsable() {
        // KNOWN + rỗng nghĩa là "đã rà và không ghi nhận" — khác hẳn UNKNOWN.
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate dish = recipe("rec.com", 25, catalog.get("ing.gao"));

        assertThat(filter.judge(dish, noAllergies(), catalog, Optional.empty()).status())
                .isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test
    void aKnownViolationOutranksMissingDataWhenBothArePresent() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate dish = new RecipeCandidate("rec.ca-hai", "Món cả hai", 1, RecipeRole.MAIN_DISH,
                Set.of(MealType.LUNCH), 20, "ing.tom-kho", BigDecimal.ONE,
                List.of(catalog.get("ing.tom-kho"), catalog.get("ing.gia-vi-nem-san")));

        EligibilityVerdict verdict = filter.judge(dish, allergicToShrimp(), catalog,
                Optional.empty());

        // Đã biết là vi phạm thì không được hạ xuống thành "chưa rõ".
        assertThat(verdict.status()).isEqualTo(EligibilityStatus.BLOCKED);
    }

    // ---------------------------------------------------------------------------------------
    // Nguyên liệu phải tránh và chế độ ăn
    // ---------------------------------------------------------------------------------------

    @Test
    void aHardExclusionAlsoReachesInsideCompoundIngredients() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate soup = recipe("rec.canh", 25, catalog.get("ing.nuoc-dung-tom"));
        ProfileConstraints profile = new ProfileConstraints(Set.of(),
                AllergyDeclarationStatus.KNOWN_NONE, null, Set.of("ing.tom-kho"), Set.of(),
                null, null);

        EligibilityVerdict verdict = filter.judge(soup, profile, catalog, Optional.empty());

        assertThat(reasons(verdict)).containsExactly(RecommendationReason.HARD_EXCLUSION);
    }

    @Test
    void aVeganProfileRejectsFishSauceThroughItsAnchovyComponent() {
        // Nước mắm mang nhóm SEASONING; chỉ nhìn nhóm của dòng công thức là bỏ lọt.
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        catalog.put("ing.ca-com", facts("ing.ca-com", "Cá cơm", FoodGroup.MEAT_FISH_EGG,
                AllergenStatus.KNOWN, Set.of("alg.ca"), Set.of()));
        catalog.put("ing.nuoc-mam", facts("ing.nuoc-mam", "Nước mắm", FoodGroup.SEASONING,
                AllergenStatus.KNOWN, Set.of("alg.ca"), Set.of("ing.ca-com")));
        RecipeCandidate dish = recipe("rec.kho", 30, catalog.get("ing.nuoc-mam"));

        EligibilityVerdict verdict = filter.judge(dish, vegan(), catalog, Optional.empty());

        assertThat(reasons(verdict)).containsExactly(RecommendationReason.DIET_PATTERN_CONFLICT);
    }

    @Test
    void anUndeclaredDietPatternAppliesNoConstraintAtAll() {
        // Chưa khai KHÁC "ăn tạp": không khai thì không có gì để áp.
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate dish = recipe("rec.tom", 15, catalog.get("ing.tom-kho"));

        assertThat(filter.judge(dish, noAllergies(), catalog, Optional.empty()).status())
                .isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test
    void theCoarseDietFilterIsReportedForThePatternsTheCatalogCannotExpress() {
        assertThat(filter.dietFilterIsCoarse(DietPattern.PESCATARIAN)).isTrue();
        assertThat(filter.dietFilterIsCoarse(DietPattern.LACTO_OVO_VEGETARIAN)).isTrue();
        assertThat(filter.dietFilterIsCoarse(DietPattern.VEGAN)).isFalse();
        assertThat(filter.dietFilterIsCoarse(DietPattern.OMNIVORE)).isFalse();
    }

    // ---------------------------------------------------------------------------------------
    // BR13 — giới hạn cứng chỉ khi người dùng bật
    // ---------------------------------------------------------------------------------------

    @Test
    void timeAndBudgetAreSoftUntilTheUserTurnsThemIntoHardLimits() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate slow = recipe("rec.cham", 90, catalog.get("ing.gao"));

        EligibilityVerdict soft = filter.judge(slow, noAllergies(), catalog,
                Optional.of(new BigDecimal("99000")));
        EligibilityVerdict hard = filter.judge(slow,
                new ProfileConstraints(Set.of(), AllergyDeclarationStatus.KNOWN_NONE, null,
                        Set.of(), Set.of(), 30, null),
                catalog, Optional.of(new BigDecimal("99000")));

        assertThat(soft.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(hard.status()).isEqualTo(EligibilityStatus.BLOCKED);
        assertThat(reasons(hard)).containsExactly(RecommendationReason.OVER_TIME_LIMIT);
    }

    @Test
    void aHardBudgetLimitBlocksAnOverpricedDish() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate dish = recipe("rec.dat", 20, catalog.get("ing.gao"));
        ProfileConstraints profile = new ProfileConstraints(Set.of(),
                AllergyDeclarationStatus.KNOWN_NONE, null, Set.of(), Set.of(), null,
                new BigDecimal("30000"));

        EligibilityVerdict verdict = filter.judge(dish, profile, catalog,
                Optional.of(new BigDecimal("45000")));

        assertThat(reasons(verdict)).containsExactly(RecommendationReason.OVER_BUDGET);
    }

    /** Chưa có giá thì giới hạn ngân sách cứng không được loại món — đó là lỗi của danh mục. */
    @Test
    void anUnpricedDishSurvivesAHardBudgetLimit() {
        Map<String, IngredientFacts> catalog = catalogWithShrimpBroth();
        RecipeCandidate dish = recipe("rec.chua-co-gia", 20, catalog.get("ing.gao"));
        ProfileConstraints profile = new ProfileConstraints(Set.of(),
                AllergyDeclarationStatus.KNOWN_NONE, null, Set.of(), Set.of(), null,
                new BigDecimal("30000"));

        assertThat(filter.judge(dish, profile, catalog, Optional.empty()).status())
                .isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    // ---------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------

    private Map<String, IngredientFacts> catalogWithShrimpBroth() {
        Map<String, IngredientFacts> catalog = new LinkedHashMap<>();
        catalog.put("ing.gao", facts("ing.gao", "Gạo tẻ", FoodGroup.GRAIN, AllergenStatus.KNOWN,
                Set.of(), Set.of()));
        catalog.put("ing.tom-kho", facts("ing.tom-kho", "Tôm khô", FoodGroup.MEAT_FISH_EGG,
                AllergenStatus.KNOWN, Set.of(SHRIMP), Set.of()));
        catalog.put("ing.nuoc-dung-tom", facts("ing.nuoc-dung-tom", "Nước dùng tôm",
                FoodGroup.SEASONING, AllergenStatus.KNOWN, Set.of(), Set.of("ing.tom-kho")));
        catalog.put("ing.gia-vi-nem-san", facts("ing.gia-vi-nem-san", "Gia vị nêm sẵn",
                FoodGroup.SEASONING, AllergenStatus.UNKNOWN, Set.of(), Set.of()));
        return catalog;
    }

    private IngredientFacts facts(String id, String name, FoodGroup group, AllergenStatus status,
                                  Set<String> allergenIds, Set<String> componentIds) {
        return new IngredientFacts(id, name, group, status, allergenIds, componentIds);
    }

    private RecipeCandidate recipe(String recipeId, int minutes, IngredientFacts... lines) {
        return new RecipeCandidate(recipeId, "Món " + recipeId, 1, RecipeRole.MAIN_DISH,
                Set.of(MealType.LUNCH), minutes, "ing.main", BigDecimal.ONE, List.of(lines));
    }

    private ProfileConstraints allergicToShrimp() {
        return new ProfileConstraints(Set.of(SHRIMP), AllergyDeclarationStatus.KNOWN_LIST, null,
                Set.of(), Set.of(), null, null);
    }

    private ProfileConstraints noAllergies() {
        return new ProfileConstraints(Set.of(), AllergyDeclarationStatus.KNOWN_NONE, null,
                Set.of(), Set.of(), null, null);
    }

    private ProfileConstraints vegan() {
        return new ProfileConstraints(Set.of(), AllergyDeclarationStatus.KNOWN_NONE,
                DietPattern.VEGAN, Set.of(), Set.of(), null, null);
    }

    private List<RecommendationReason> reasons(EligibilityVerdict verdict) {
        return verdict.reasons().stream().map(note -> note.reason()).toList();
    }
}
