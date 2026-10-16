package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.NutrientStatus;
import vn.nutrimom.nutrition.service.NutritionCalculator;
import vn.nutrimom.nutrition.service.NutritionRulesProvider;
import vn.nutrimom.nutrition.service.NutritionSnapshots.IngredientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.NutrientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.Portion;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeLineSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;

/**
 * Bộ tính dinh dưỡng, kiểm bằng fixture thuần — không Spring, không database.
 *
 * <p>Quy tắc của cả lớp test này: <strong>so sánh trước làm tròn</strong>. Mọi khẳng định đều dùng
 * {@code isEqualByComparingTo} trên giá trị gốc; không có chỗ nào làm tròn rồi mới so.</p>
 */
class NutritionCalculatorTest {

    /** Nạp đúng tập quy tắc thật trong {@code nutritionRules.json}, không phải bản giả lập. */
    private final NutritionCalculator calculator =
            new NutritionCalculator(new NutritionRulesProvider());

    // ---------------------------------------------------------------- TC08

    @Test
    void scalesNutritionExactlyWithTheNumberOfServings() {
        // TC08: 1 phần lên 1,5 phần. servingsYield = 3 để phép chia không kết thúc — đúng chỗ dễ
        // sinh sai số nếu cài đặt nhân trước chia sau hoặc làm tròn giữa chừng.
        RecipeSnapshot recipe = recipe("rec.ga-kho", new BigDecimal("3"), null,
                line(rice(), "300", FoodState.RAW),
                line(chicken(), "300", FoodState.RAW));

        var onePortion = calculator.forPortion(recipe, BigDecimal.ONE);
        var onePointFive = calculator.forPortion(recipe, new BigDecimal("1.5"));

        for (String code : List.of("ENERGY", "FOLATE", "IRON")) {
            BigDecimal base = onePortion.nutrient(code).orElseThrow().knownValue();
            BigDecimal scaled = onePointFive.nutrient(code).orElseThrow().knownValue();
            assertThat(scaled)
                    .as("chất %s khi tăng phần", code)
                    .isEqualByComparingTo(base.multiply(new BigDecimal("1.5")));
        }
    }

    @Test
    void scalesIngredientQuantitiesExactlyWithTheNumberOfServings() {
        // Nửa còn lại của TC08: nguyên liệu phải tăng đúng cùng tỉ lệ với dinh dưỡng, nếu không
        // danh sách mua sắm ở nhánh 7 sẽ lệch với tổng dinh dưỡng của cùng một mục.
        RecipeSnapshot recipe = recipe("rec.ga-kho", new BigDecimal("3"), null,
                line(rice(), "300", FoodState.RAW),
                line(chicken(), "300", FoodState.RAW));

        var onePortion = calculator.ingredientsFor(recipe, BigDecimal.ONE);
        var onePointFive = calculator.ingredientsFor(recipe, new BigDecimal("1.5"));

        assertThat(onePointFive).hasSameSizeAs(onePortion);
        for (int i = 0; i < onePortion.size(); i++) {
            assertThat(onePointFive.get(i).quantityGram())
                    .as("nguyên liệu %s", onePortion.get(i).ingredientId())
                    .isEqualByComparingTo(
                            onePortion.get(i).quantityGram().multiply(new BigDecimal("1.5")));
        }
        assertThat(onePortion.get(0).quantityGram()).isEqualByComparingTo("100");
    }

    // ---------------------------------------------------------------- TC09

    @Test
    void marksANutrientPartialWhenOneIngredientHasNoDataForIt() {
        // TC09: nước mắm khai FOLATE với status MISSING. Tổng folate chỉ gồm phần đã biết của gạo,
        // nước mắm bị nêu tên, và cả chất mang PARTIAL.
        RecipeSnapshot recipe = recipe("rec.com-nuoc-mam", BigDecimal.ONE, null,
                line(rice(), "100", FoodState.RAW),
                line(fishSauceWithoutFolate(), "100", FoodState.PROCESSED));

        var totals = calculator.forPortion(recipe, BigDecimal.ONE);
        var folate = totals.nutrient("FOLATE").orElseThrow();

        assertThat(folate.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
        assertThat(folate.missingIngredientIds()).containsExactly("ing.nuoc-mam");
        // 100 g gạo, 8 mcg_dfe trên 100 g: chỉ phần đã biết, không cộng thêm 0 cho nước mắm.
        assertThat(folate.knownValue()).isEqualByComparingTo("8");
        assertThat(folate.unit()).isEqualTo("mcg_dfe");
        // Chất mà cả hai nguyên liệu đều có số liệu thì vẫn COMPLETE — thiếu một chất không làm
        // hỏng những chất còn lại.
        assertThat(totals.nutrient("ENERGY").orElseThrow().coverageStatus())
                .isEqualTo(CoverageStatus.COMPLETE);
        assertThat(totals.coverage()).isEqualTo(CoverageStatus.PARTIAL);
    }

    @Test
    void reportsANutrientNobodyDeclaredAsNullRatherThanZero() {
        // Không nguyên liệu nào khai iốt. Trả 0 ở đây là nói "món này không có iốt", trong khi sự
        // thật là chưa ai đo (BR08).
        RecipeSnapshot recipe = recipe("rec.com-trang", BigDecimal.ONE, null,
                line(rice(), "100", FoodState.RAW));

        var iodine = calculator.forPortion(recipe, BigDecimal.ONE).nutrient("IODINE").orElseThrow();

        assertThat(iodine.knownValue()).isNull();
        assertThat(iodine.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
        assertThat(iodine.missingIngredientIds()).containsExactly("ing.gao-te");
    }

    // ------------------------------------------------- trạng thái thực phẩm

    @Test
    void appliesNoRetentionWhenTheRecordAlreadyDescribesTheCookedFood() {
        // Bản ghi của cơm trắng đã là số liệu của thực phẩm chín. Áp hao hụt lần nữa là trừ hai lần.
        RecipeSnapshot recipe = recipe("rec.com-trang", BigDecimal.ONE, new BigDecimal("180"),
                line(cookedRice(), "200", FoodState.COOKED));

        var totals = calculator.forPortion(recipe, BigDecimal.ONE);

        // 200 g x 130 kcal/100 g = 260 kcal, không nhân thêm hệ số nào.
        assertThat(totals.nutrient("ENERGY").orElseThrow().knownValue()).isEqualByComparingTo("260");
        assertThat(totals.methodNotes()).isEmpty();
    }

    @Test
    void recordsAnAssumptionNoteWhenTheStateDiffersAndNoRetentionFactorExists() {
        // Bản ghi mô tả gạo sống nhưng công thức dùng ở trạng thái chín: r = 1 vẫn là giả định
        // ước tính, và phải nói ra, chứ không được trình bày như số đo sau nấu.
        RecipeSnapshot recipe = recipe("rec.com-la", BigDecimal.ONE, null,
                line(rice(), "100", FoodState.COOKED));

        var totals = calculator.forPortion(recipe, BigDecimal.ONE);

        assertThat(totals.nutrient("ENERGY").orElseThrow().knownValue()).isEqualByComparingTo("360");
        assertThat(totals.methodNotes()).hasSize(1);
        assertThat(totals.methodNotes().get(0))
                .contains("Gạo tẻ")
                .contains("r = 1")
                .contains("giả định ước tính");
    }

    // -------------------------------------------------------- gộp nhiều mục

    @Test
    void sumsSeveralPortionsAndKeepsEveryMissingIngredient() {
        RecipeSnapshot riceDish = recipe("rec.com-trang", BigDecimal.ONE, null,
                line(rice(), "100", FoodState.RAW));
        RecipeSnapshot sauced = recipe("rec.com-nuoc-mam", BigDecimal.ONE, null,
                line(fishSauceWithoutFolate(), "100", FoodState.PROCESSED));

        var day = calculator.forPortions(List.of(
                new Portion(riceDish, BigDecimal.ONE),
                new Portion(sauced, new BigDecimal("2"))));

        // 360 kcal + 2 x 50 kcal
        assertThat(day.nutrient("ENERGY").orElseThrow().knownValue()).isEqualByComparingTo("460");
        var folate = day.nutrient("FOLATE").orElseThrow();
        assertThat(folate.knownValue()).isEqualByComparingTo("8");
        assertThat(folate.missingIngredientIds()).containsExactly("ing.nuoc-mam");
        assertThat(folate.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    }

    @Test
    void carriesTheRulesVersionOnEveryResult() {
        RecipeSnapshot recipe = recipe("rec.com-trang", BigDecimal.ONE, null,
                line(rice(), "100", FoodState.RAW));

        assertThat(calculator.forRecipe(recipe).rulesVersion())
                .isEqualTo(calculator.rules().rulesVersion())
                .isNotBlank();
    }

    // ------------------------------------------------- gram món chín ra phần

    @Test
    void convertsCookedGramToServingsUsingTheCookedWeight() {
        // 180 g là khối lượng một phần cơm đã nấu. 270 g là 1,5 phần — không liên quan gì tới
        // 300 g gạo sống của công thức.
        RecipeSnapshot recipe = recipe("rec.com-trang", new BigDecimal("4"), new BigDecimal("180"),
                line(rice(), "300", FoodState.RAW));

        assertThat(calculator.servingsForCookedGram(recipe, new BigDecimal("270")))
                .get(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("1.5");
    }

    @Test
    void refusesToConvertGramWhenTheCookedWeightIsUnknown() {
        RecipeSnapshot recipe = recipe("rec.canh", new BigDecimal("3"), null,
                line(rice(), "300", FoodState.RAW));

        assertThat(calculator.servingsForCookedGram(recipe, new BigDecimal("270"))).isEmpty();
    }

    // ------------------------------------------------------------- đầu vào sai

    @Test
    void rejectsANonPositiveNumberOfServings() {
        RecipeSnapshot recipe = recipe("rec.com-trang", BigDecimal.ONE, null,
                line(rice(), "100", FoodState.RAW));

        assertThatThrownBy(() -> calculator.forPortion(recipe, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToAddTwoUnitsOfTheSameNutrient() {
        // mg cộng vào mcg cho ra một con số trông vẫn hợp lý — đúng loại lỗi không ai phát hiện
        // bằng mắt, nên phải ném.
        var odd = new IngredientSnapshot("ing.la", "Nguyên liệu lạ", FoodState.RAW, FoodGroup.OTHER,
                List.of(new NutrientSnapshot("IRON", new BigDecimal("1"), "mcg",
                        NutrientStatus.ESTIMATED)));
        RecipeSnapshot recipe = recipe("rec.la", BigDecimal.ONE, null,
                line(rice(), "100", FoodState.RAW),
                line(odd, "100", FoodState.RAW));

        assertThatThrownBy(() -> calculator.forPortion(recipe, BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IRON");
    }

    // ------------------------------------------------------------- fixture

    private static RecipeSnapshot recipe(String id, BigDecimal servingsYield, BigDecimal servingGram,
                                         RecipeLineSnapshot... lines) {
        return new RecipeSnapshot(id, id, 1, servingsYield, servingGram, List.of(lines));
    }

    private static RecipeLineSnapshot line(IngredientSnapshot ingredient, String quantityGram,
                                           FoodState preparationState) {
        return new RecipeLineSnapshot(ingredient, new BigDecimal(quantityGram), preparationState);
    }

    private static IngredientSnapshot rice() {
        return ingredient("ing.gao-te", "Gạo tẻ", FoodState.RAW, FoodGroup.GRAIN,
                amounts("ENERGY", "360", "PROTEIN", "6.8", "FOLATE", "8", "IRON", "0.8"));
    }

    private static IngredientSnapshot cookedRice() {
        return ingredient("ing.com-trang", "Cơm trắng", FoodState.COOKED, FoodGroup.GRAIN,
                amounts("ENERGY", "130", "PROTEIN", "2.7", "IRON", "0.2"));
    }

    private static IngredientSnapshot chicken() {
        return ingredient("ing.thit-ga-uc", "Ức gà", FoodState.RAW, FoodGroup.MEAT_FISH_EGG,
                amounts("ENERGY", "165", "PROTEIN", "31", "FOLATE", "4", "IRON", "1"));
    }

    /** Nước mắm cố ý <strong>thiếu</strong> folate, giống fixture trong seed (TC09). */
    private static IngredientSnapshot fishSauceWithoutFolate() {
        List<NutrientSnapshot> nutrients = new ArrayList<>(
                ingredient("ing.nuoc-mam", "Nước mắm", FoodState.PROCESSED, FoodGroup.SEASONING,
                        amounts("ENERGY", "50", "IRON", "0.5")).nutrients());
        nutrients.add(new NutrientSnapshot("FOLATE", null, "mcg_dfe", NutrientStatus.MISSING));
        return new IngredientSnapshot("ing.nuoc-mam", "Nước mắm", FoodState.PROCESSED,
                FoodGroup.SEASONING, nutrients);
    }

    private static IngredientSnapshot ingredient(String id, String name, FoodState state,
                                                 FoodGroup group, Map<String, String> amounts) {
        List<NutrientSnapshot> nutrients = amounts.entrySet().stream()
                .map(entry -> new NutrientSnapshot(entry.getKey(), new BigDecimal(entry.getValue()),
                        unitOf(entry.getKey()), NutrientStatus.ESTIMATED))
                .toList();
        return new IngredientSnapshot(id, name, state, group, nutrients);
    }

    private static Map<String, String> amounts(String... codeThenAmount) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < codeThenAmount.length; i += 2) {
            values.put(codeThenAmount[i], codeThenAmount[i + 1]);
        }
        return values;
    }

    /** Đơn vị theo đúng danh mục chất trong {@code nutrientSources.json}. */
    private static String unitOf(String code) {
        return switch (code) {
            case "ENERGY" -> "kcal";
            case "PROTEIN" -> "g";
            case "FOLATE" -> "mcg_dfe";
            case "IRON", "CALCIUM", "CHOLINE" -> "mg";
            case "IODINE", "VITAMIN_D" -> "mcg";
            default -> throw new IllegalArgumentException("Chất lạ trong fixture: " + code);
        };
    }
}
