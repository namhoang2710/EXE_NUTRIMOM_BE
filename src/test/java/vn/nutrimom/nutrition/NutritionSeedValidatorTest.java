package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.NutrientStatus;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.TemplateMode;
import vn.nutrimom.nutrition.service.NutritionSeedData.Allergen;
import vn.nutrimom.nutrition.service.NutritionSeedData.Ingredient;
import vn.nutrimom.nutrition.service.NutritionSeedData.IngredientsFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.MealTemplate;
import vn.nutrimom.nutrition.service.NutritionSeedData.MicronutrientTarget;
import vn.nutrimom.nutrition.service.NutritionSeedData.Nutrient;
import vn.nutrimom.nutrition.service.NutritionSeedData.NutrientAmount;
import vn.nutrimom.nutrition.service.NutritionSeedData.Price;
import vn.nutrimom.nutrition.service.NutritionSeedData.Recipe;
import vn.nutrimom.nutrition.service.NutritionSeedData.RecipeIngredient;
import vn.nutrimom.nutrition.service.NutritionSeedData.RecipesFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.RulesFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.Source;
import vn.nutrimom.nutrition.service.NutritionSeedData.SourcesFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.WeeklyFoodLimit;
import vn.nutrimom.nutrition.service.NutritionSeedValidator;

/**
 * Bộ kiểm tập seed chạy TRƯỚC khi nhập database.
 *
 * <p>Mỗi test dưới đây tương ứng một cách làm hỏng dữ liệu mà người nhập liệu thực sự có thể mắc,
 * và hậu quả của nó ở các nhánh sau.</p>
 */
class NutritionSeedValidatorTest {

    private static final SourcesFile SOURCES = new SourcesFile(
            List.of(new Source("src.a", "Nguồn A", null, LocalDate.of(2026, 10, 14))),
            List.of(new Nutrient("ENERGY", "Năng lượng", "kcal"),
                    new Nutrient("FOLATE", "Folate", "mcg_dfe")));

    private static final RulesFile RULES = new RulesFile("v1",
            List.of(new MicronutrientTarget("FOLATE", new BigDecimal("600"), "mcg_dfe", "RDA")),
            List.of(new MealTemplate(MealType.LUNCH, TemplateMode.ROLE_SET,
                    List.of(RecipeRole.STAPLE, RecipeRole.PROTEIN), new BigDecimal("0.3"), "1")),
            List.of());

    @Test
    void acceptsAWellFormedSeed() {
        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                ingredientsOf(rice()), recipesOf(riceDish()));

        assertThat(problems).isEmpty();
    }

    @Test
    void rejectsMissingValueThatIsNotMarkedMissing() {
        // Giá trị trống mà status không phải MISSING nghĩa là ai đó xoá số nhưng quên đổi status.
        // Để lọt thì nhánh 2 sẽ coi như chất đó bằng 0 thay vì PARTIAL (BR08).
        var broken = new Ingredient("ing.rice", "Gạo", FoodState.RAW, FoodGroup.GRAIN,
                AllergenStatus.KNOWN, List.of(), List.of(), List.of(), "src.a",
                List.of(new NutrientAmount("ENERGY", null, "kcal", NutrientStatus.ESTIMATED, "src.a")),
                null);

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                ingredientsOf(broken), recipesOf(riceDish()));

        assertThat(problems).anyMatch(p -> p.contains("ENERGY") && p.contains("thiếu số"));
    }

    @Test
    void rejectsMissingStatusThatStillCarriesANumber() {
        var broken = new Ingredient("ing.rice", "Gạo", FoodState.RAW, FoodGroup.GRAIN,
                AllergenStatus.KNOWN, List.of(), List.of(), List.of(), "src.a",
                List.of(new NutrientAmount("ENERGY", new BigDecimal("360"), "kcal",
                        NutrientStatus.MISSING, "src.a")),
                null);

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                ingredientsOf(broken), recipesOf(riceDish()));

        assertThat(problems).anyMatch(p -> p.contains("MISSING nhưng vẫn có số"));
    }

    @Test
    void rejectsUnitThatDisagreesWithTheNutrientCatalog() {
        // Đây chính là lý do danh mục Nutrient tồn tại: mg và mcg lẫn nhau giữa các món là lỗi
        // không ai phát hiện bằng mắt.
        var broken = new Ingredient("ing.rice", "Gạo", FoodState.RAW, FoodGroup.GRAIN,
                AllergenStatus.KNOWN, List.of(), List.of(), List.of(), "src.a",
                List.of(new NutrientAmount("FOLATE", new BigDecimal("8"), "mg",
                        NutrientStatus.ESTIMATED, "src.a")),
                null);

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                ingredientsOf(broken), recipesOf(riceDish()));

        assertThat(problems).anyMatch(p -> p.contains("FOLATE") && p.contains("đơn vị"));
    }

    @Test
    void rejectsACycleInIngredientComponents() {
        // Vòng bắc cầu: CHECK ở database chỉ chặn được trường hợp tự trỏ vào chính mình. Vòng kiểu
        // này sẽ làm EligibilityFilter ở nhánh 4 đệ quy vô hạn khi đi tìm thành phần dị ứng.
        var a = ingredient("ing.a", List.of("ing.b"));
        var b = ingredient("ing.b", List.of("ing.c"));
        var c = ingredient("ing.c", List.of("ing.a"));

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                new IngredientsFile(List.of(), List.of(a, b, c)), new RecipesFile(List.of()));

        assertThat(problems).anyMatch(p -> p.startsWith("Vòng trong thành phần con"));
    }

    @Test
    void rejectsSelfReferenceInComponents() {
        var self = ingredient("ing.a", List.of("ing.a"));

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                new IngredientsFile(List.of(), List.of(self)), new RecipesFile(List.of()));

        assertThat(problems).anyMatch(p -> p.contains("tự khai mình là thành phần con"));
    }

    @Test
    void rejectsDanglingReferences() {
        var orphanComponent = ingredient("ing.a", List.of("ing.khong-ton-tai"));
        var recipe = new Recipe("rec.x", "Món lạ", 1, RecipeRole.MAIN_DISH, List.of(MealType.LUNCH),
                BigDecimal.ONE, null, 10, null, null, null, "1. Nấu.",
                List.of(new RecipeIngredient("ing.cung-khong-ton-tai", BigDecimal.TEN, FoodState.RAW)));

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                new IngredientsFile(List.of(), List.of(orphanComponent)),
                new RecipesFile(List.of(recipe)));

        assertThat(problems)
                .anyMatch(p -> p.contains("thành phần con không tồn tại"))
                .anyMatch(p -> p.contains("nguyên liệu không tồn tại"));
    }

    @Test
    void rejectsZeroServingsYieldBecauseEveryPerServingDivisionUsesIt() {
        var recipe = new Recipe("rec.x", "Món", 1, RecipeRole.MAIN_DISH, List.of(MealType.LUNCH),
                BigDecimal.ZERO, null, 10, null, null, null, "1. Nấu.",
                List.of(new RecipeIngredient("ing.rice", BigDecimal.TEN, FoodState.RAW)));

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                ingredientsOf(rice()), new RecipesFile(List.of(recipe)));

        assertThat(problems).anyMatch(p -> p.contains("servingsYield phải lớn hơn 0"));
    }

    @Test
    void rejectsADishUsingAnUnscreenedIngredientWithoutASafetyNote() {
        // Món chạm vào nguyên liệu UNKNOWN bị loại khỏi thực đơn cá nhân hoá (BR03) nhưng vẫn tra
        // cứu được — nên phải nói rõ lý do cho người đọc.
        var seasoning = new Ingredient("ing.nem", "Gia vị nêm sẵn", FoodState.PROCESSED,
                FoodGroup.SEASONING, AllergenStatus.UNKNOWN, List.of(), List.of(), List.of(),
                null, List.of(), null);
        var recipe = new Recipe("rec.x", "Canh nêm sẵn", 1, RecipeRole.VEGETABLE,
                List.of(MealType.LUNCH), BigDecimal.ONE, null, 10, null, null, null, "1. Nấu.",
                List.of(new RecipeIngredient("ing.nem", BigDecimal.TEN, FoodState.PROCESSED)));

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                new IngredientsFile(List.of(), List.of(seasoning)), new RecipesFile(List.of(recipe)));

        assertThat(problems).anyMatch(p -> p.contains("chưa rà soát dị ứng")
                && p.contains("safetyNotes"));
    }

    @Test
    void rejectsUnknownAllergenStatusThatStillListsAllergens() {
        var contradiction = new Ingredient("ing.x", "Thứ gì đó", FoodState.RAW,
                FoodGroup.OTHER, AllergenStatus.UNKNOWN, List.of("alg.tom"), List.of(), List.of(),
                null, List.of(), null);

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                new IngredientsFile(List.of(new Allergen("alg.tom", "Tôm")), List.of(contradiction)),
                new RecipesFile(List.of()));

        assertThat(problems).anyMatch(p -> p.contains("UNKNOWN nhưng vẫn liệt kê"));
    }

    @Test
    void rejectsAPriceWithoutTheEstimateDate() {
        // BR18: màn hình phải nói được giá tham khảo của thời điểm nào, không ngụ ý đây là báo giá
        // hiện tại của cửa hàng.
        var priced = new Ingredient("ing.rice", "Gạo", FoodState.RAW, FoodGroup.GRAIN,
                AllergenStatus.KNOWN, List.of(), List.of(), List.of(), "src.a", List.of(),
                new Price(new BigDecimal("22000"), "kg", null, null));

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                ingredientsOf(priced), recipesOf(riceDish()));

        assertThat(problems).anyMatch(p -> p.contains("estimatedAt"));
    }

    @Test
    void collectsEveryProblemInOneRunInsteadOfStoppingAtTheFirst() {
        var broken = new Ingredient("ing.rice", "", null, FoodGroup.GRAIN, AllergenStatus.KNOWN,
                List.of("alg.khong-ton-tai"), List.of(), List.of(), "src.khong-ton-tai",
                List.of(), null);

        var problems = NutritionSeedValidator.validate(SOURCES, RULES,
                new IngredientsFile(List.of(), List.of(broken)), new RecipesFile(List.of()));

        assertThat(problems).hasSizeGreaterThanOrEqualTo(4);
    }

    /**
     * Một mã gõ sai trong giới hạn theo tuần <strong>không có triệu chứng</strong>: nhóm vẫn tồn
     * tại, chỉ là không khớp nguyên liệu nào, nên điều kiện an toàn im lặng không chạy. Đúng loại
     * lỗi phải chặn ở bước kiểm seed thay vì chờ ai đó phát hiện trên một thực đơn thật.
     */
    @Test
    void rejectsAWeeklyFoodLimitThatNamesAnIngredientTheCatalogDoesNotHave() {
        var rules = weeklyLimitRules(new WeeklyFoodLimit("FISH", "cá", List.of("ing.ca-go-sai"),
                new BigDecimal("340"), "src.a"));

        var problems = NutritionSeedValidator.validate(SOURCES, rules,
                ingredientsOf(rice()), recipesOf(riceDish()));

        assertThat(problems).anyMatch(p -> p.contains("FISH") && p.contains("ing.ca-go-sai"));
    }

    @Test
    void rejectsAWeeklyFoodLimitWithNoIngredientsOrNoLimit() {
        var empty = weeklyLimitRules(new WeeklyFoodLimit("FISH", "cá", List.of(),
                new BigDecimal("340"), null));
        var zero = weeklyLimitRules(new WeeklyFoodLimit("FISH", "cá", List.of("ing.rice"),
                BigDecimal.ZERO, null));

        assertThat(NutritionSeedValidator.validate(SOURCES, empty, ingredientsOf(rice()),
                recipesOf(riceDish())))
                .anyMatch(p -> p.contains("không giới hạn gì cả"));
        assertThat(NutritionSeedValidator.validate(SOURCES, zero, ingredientsOf(rice()),
                recipesOf(riceDish())))
                .anyMatch(p -> p.contains("maxGramPerWeek"));
    }

    /** Hai nhóm trùng mã thì nhóm sau che nhóm trước, và một mức giới hạn biến mất lặng lẽ. */
    @Test
    void rejectsTwoWeeklyFoodLimitsSharingACode() {
        var rules = weeklyLimitRules(
                new WeeklyFoodLimit("FISH", "cá ít thuỷ ngân", List.of("ing.rice"),
                        new BigDecimal("340"), null),
                new WeeklyFoodLimit("FISH", "cá nhiều thuỷ ngân", List.of("ing.rice"),
                        new BigDecimal("170"), null));

        var problems = NutritionSeedValidator.validate(SOURCES, rules, ingredientsOf(rice()),
                recipesOf(riceDish()));

        assertThat(problems).anyMatch(p -> p.contains("trùng code FISH"));
    }

    private static RulesFile weeklyLimitRules(WeeklyFoodLimit... limits) {
        return new RulesFile(RULES.rulesVersion(), RULES.micronutrientTargets(),
                RULES.mealTemplates(), List.of(limits));
    }

    private static Ingredient rice() {
        return new Ingredient("ing.rice", "Gạo", FoodState.RAW, FoodGroup.GRAIN,
                AllergenStatus.KNOWN, List.of(), List.of("gạo tẻ"), List.of(), "src.a",
                List.of(new NutrientAmount("ENERGY", new BigDecimal("360"), "kcal",
                        NutrientStatus.ESTIMATED, "src.a")),
                new Price(new BigDecimal("22000"), "kg", LocalDate.of(2026, 10, 14), null));
    }

    private static Ingredient ingredient(String id, List<String> components) {
        return new Ingredient(id, "Tên " + id, FoodState.PROCESSED, FoodGroup.SEASONING,
                AllergenStatus.KNOWN, List.of(), List.of(), components, null, List.of(), null);
    }

    private static Recipe riceDish() {
        return new Recipe("rec.com", "Cơm trắng", 1, RecipeRole.STAPLE, List.of(MealType.LUNCH),
                new BigDecimal("4"), new BigDecimal("180"), 25, "ing.rice", null, null,
                "1. Vo gạo.\n2. Nấu.",
                List.of(new RecipeIngredient("ing.rice", new BigDecimal("300"), FoodState.RAW)));
    }

    private static IngredientsFile ingredientsOf(Ingredient... values) {
        return new IngredientsFile(List.of(), List.of(values));
    }

    private static RecipesFile recipesOf(Recipe... values) {
        return new RecipesFile(List.of(values));
    }
}
