package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.NutrientStatus;
import vn.nutrimom.nutrition.service.MassUnits;
import vn.nutrimom.nutrition.service.NutritionCalculator;
import vn.nutrimom.nutrition.service.NutritionRulesProvider;
import vn.nutrimom.nutrition.service.NutritionSnapshots.IngredientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.NutrientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeLineSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.ShoppingAggregator;
import vn.nutrimom.nutrition.service.ShoppingAggregator.AggregatedLine;
import vn.nutrimom.nutrition.service.ShoppingAggregator.PlannedPortion;

/**
 * Quy tắc gộp của danh sách mua sắm — phần dễ sai nhất của nhánh 7, kiểm bằng fixture thuần.
 *
 * <p>Không Spring, không database: thứ cần chứng minh ở đây là một quyết định về dữ liệu (khóa gộp
 * gồm những gì), và nó phải đúng trước khi có bất kỳ thực đơn thật nào.</p>
 */
class ShoppingAggregatorTest {

    private final ShoppingAggregator aggregator =
            new ShoppingAggregator(new NutritionCalculator(new NutritionRulesProvider()));

    // ---------------------------------------------------------------- TC10

    /**
     * TC10 — gạo sống và cơm chín trong cùng một danh sách không bao giờ thành một dòng gram.
     */
    @Test
    void rawRiceAndCookedRiceNeverCollapseIntoOneQuantity() {
        RecipeSnapshot boiledRice = recipe("rec.com-trang", "1",
                line(rice(), "100", FoodState.RAW));
        RecipeSnapshot friedRice = recipe("rec.com-chien", "1",
                line(cookedRice(), "100", FoodState.COOKED));

        List<AggregatedLine> lines = aggregator.aggregate(List.of(
                portion("item-1", boiledRice, "1"),
                portion("item-2", friedRice, "1")));

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(AggregatedLine::ingredientId)
                .containsExactlyInAnyOrder("ing.gao-te", "ing.com-trang");
        for (AggregatedLine line : lines) {
            assertThat(line.quantityGram())
                    .describedAs("dòng %s không được mang lượng của dòng kia", line.ingredientId())
                    .isEqualByComparingTo("100");
        }
    }

    /**
     * Cùng một <em>mã</em> nguyên liệu ở hai trạng thái vẫn là hai dòng.
     *
     * <p>Đây là dạng khó hơn của TC10: hai dòng trùng mã nên một cài đặt gộp theo mã nguyên liệu sẽ
     * đi qua được bài trên mà vẫn hỏng ở đây.</p>
     */
    @Test
    void oneIngredientInTwoStatesIsTwoLines() {
        RecipeSnapshot dried = recipe("rec.canh-tom-kho", "1",
                line(shrimp(FoodState.DRIED), "20", FoodState.DRIED));
        RecipeSnapshot fresh = recipe("rec.tom-hap", "1",
                line(shrimp(FoodState.RAW), "150", FoodState.RAW));

        List<AggregatedLine> lines = aggregator.aggregate(List.of(
                portion("item-1", dried, "1"),
                portion("item-2", fresh, "1")));

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(AggregatedLine::foodState)
                .containsExactlyInAnyOrder(FoodState.DRIED, FoodState.RAW);
    }

    /** Cùng mã, cùng trạng thái, hai món khác nhau — <strong>mới</strong> là một dòng. */
    @Test
    void theSameIngredientInTheSameStateMergesAndKeepsBothDishes() {
        RecipeSnapshot soup = recipe("rec.canh-bi-do", "1",
                line(pumpkin(), "300", FoodState.RAW));
        RecipeSnapshot stew = recipe("rec.bi-do-ham", "1",
                line(pumpkin(), "200", FoodState.RAW));

        List<AggregatedLine> lines = aggregator.aggregate(List.of(
                portion("item-1", soup, "1"),
                portion("item-2", stew, "1")));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).quantityGram()).isEqualByComparingTo("500");
        assertThat(lines.get(0).usages()).extracting(usage -> usage.recipeName())
                .containsExactly("rec.canh-bi-do", "rec.bi-do-ham");
    }

    /** Hai loại cá là hai thứ phải mua, dù cùng nhóm thực phẩm và cùng trạng thái. */
    @Test
    void twoKindsOfFishStayTwoLines() {
        RecipeSnapshot salmonDish = recipe("rec.ca-hoi-ap-chao", "1",
                line(fish("ing.ca-hoi", "Cá hồi phi lê"), "240", FoodState.RAW));
        RecipeSnapshot anchovyDish = recipe("rec.ca-com-kho", "1",
                line(fish("ing.ca-com", "Cá cơm"), "60", FoodState.RAW));

        List<AggregatedLine> lines = aggregator.aggregate(List.of(
                portion("item-1", salmonDish, "1"),
                portion("item-2", anchovyDish, "1")));

        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line ->
                assertThat(line.foodGroup()).isEqualTo(FoodGroup.MEAT_FISH_EGG));
    }

    // ---------------------------------------------------------------- tỉ lệ và thứ tự

    /**
     * Lượng cần đi đúng cùng tỉ lệ với bộ tính dinh dưỡng (bất biến 4, TC08).
     *
     * <p>{@code servingsYield = 3} để phép chia không kết thúc — đúng chỗ một cài đặt nhân trước
     * chia sau sẽ lệch.</p>
     */
    @Test
    void quantitiesFollowTheNumberOfServingsThroughTheCalculator() {
        RecipeSnapshot chickenDish = recipe("rec.ga-kho-gung", "3",
                line(chicken(), "300", FoodState.RAW));

        List<AggregatedLine> onePortion =
                aggregator.aggregate(List.of(portion("item-1", chickenDish, "1")));
        List<AggregatedLine> onePointFive =
                aggregator.aggregate(List.of(portion("item-1", chickenDish, "1.5")));

        assertThat(onePortion.get(0).quantityGram()).isEqualByComparingTo("100");
        assertThat(onePointFive.get(0).quantityGram()).isEqualByComparingTo("150");
    }

    /** Thứ tự đi chợ: rau quả, thịt cá trứng, ngũ cốc, rồi các nhóm còn lại. */
    @Test
    void linesAreOrderedByFoodGroupTheWayAMarketIsWalked() {
        RecipeSnapshot mixed = recipe("rec.com-ga-rau", "1",
                line(rice(), "100", FoodState.RAW),
                line(chicken(), "100", FoodState.RAW),
                line(pumpkin(), "100", FoodState.RAW),
                line(fishSauce(), "10", FoodState.PROCESSED));

        List<AggregatedLine> lines = aggregator.aggregate(List.of(portion("item-1", mixed, "1")));

        assertThat(lines).extracting(AggregatedLine::foodGroup)
                .containsExactly(FoodGroup.VEGETABLE_FRUIT, FoodGroup.MEAT_FISH_EGG,
                        FoodGroup.GRAIN, FoodGroup.SEASONING);
    }

    /** Bỏ chọn một món là bỏ bớt các lần dùng rồi cộng lại — không phải một đường tính thứ hai. */
    @Test
    void droppingOneDishLeavesTheOtherDishesQuantityIntact() {
        RecipeSnapshot soup = recipe("rec.canh-bi-do", "1", line(pumpkin(), "300", FoodState.RAW));
        RecipeSnapshot stew = recipe("rec.bi-do-ham", "1", line(pumpkin(), "200", FoodState.RAW));

        AggregatedLine line = aggregator.aggregate(List.of(
                portion("item-1", soup, "1"),
                portion("item-2", stew, "1"))).get(0);

        AggregatedLine kept = line.retainUsages(line.usages().stream()
                .filter(usage -> !usage.itemId().equals("item-1"))
                .toList());

        assertThat(kept.quantityGram()).isEqualByComparingTo("200");
        assertThat(line.quantityGram())
                .describedAs("dòng gốc không bị sửa khi lọc")
                .isEqualByComparingTo("500");
    }

    // ---------------------------------------------------------------- đơn vị

    /**
     * Danh sách đơn vị quy đổi được là <strong>đóng và rất ngắn</strong>.
     *
     * <p>"Thìa", "lít", "quả" không nằm trong đó, và đó là toàn bộ điểm: quy chúng sang gram cần
     * một mật độ hoặc một khối lượng trung bình mà danh mục chưa có.</p>
     */
    @Test
    void onlyRealMassUnitsConvertToGram() {
        assertThat(MassUnits.isConvertible("kg")).isTrue();
        assertThat(MassUnits.isConvertible("G")).isTrue();
        assertThat(MassUnits.isConvertible("gram")).isTrue();

        for (String unit : List.of("thìa", "muỗng", "lít", "quả", "bó", "hộp", "")) {
            assertThat(MassUnits.isConvertible(unit))
                    .describedAs("đơn vị %s chưa có quy đổi", unit)
                    .isFalse();
            assertThat(MassUnits.toGram(BigDecimal.ONE, unit))
                    .describedAs("không được đoán %s ra gram", unit)
                    .isEmpty();
        }
    }

    @Test
    void aKilogramIsAThousandGramsAndBackAgain() {
        assertThat(MassUnits.toGram(new BigDecimal("1.5"), "kg")).get()
                .isEqualTo(new BigDecimal("1500.0"));
        assertThat(MassUnits.fromGram(new BigDecimal("250"), "kg"))
                .hasValueSatisfying(value ->
                        assertThat(value).isEqualByComparingTo(new BigDecimal("0.25")));
    }

    // ---------------------------------------------------------------- fixture

    private static PlannedPortion portion(String itemId, RecipeSnapshot recipe, String servings) {
        return new PlannedPortion(itemId, recipe, new BigDecimal(servings));
    }

    private static RecipeSnapshot recipe(String id, String servingsYield,
                                         RecipeLineSnapshot... lines) {
        return new RecipeSnapshot(id, id, 1, new BigDecimal(servingsYield), null, List.of(lines));
    }

    private static RecipeLineSnapshot line(IngredientSnapshot ingredient, String quantityGram,
                                           FoodState preparationState) {
        return new RecipeLineSnapshot(ingredient, new BigDecimal(quantityGram), preparationState);
    }

    private static IngredientSnapshot rice() {
        return ingredient("ing.gao-te", "Gạo tẻ", FoodState.RAW, FoodGroup.GRAIN,
                amounts("ENERGY", "360"));
    }

    private static IngredientSnapshot cookedRice() {
        return ingredient("ing.com-trang", "Cơm trắng", FoodState.COOKED, FoodGroup.GRAIN,
                amounts("ENERGY", "130"));
    }

    private static IngredientSnapshot chicken() {
        return ingredient("ing.thit-ga-uc", "Ức gà", FoodState.RAW, FoodGroup.MEAT_FISH_EGG,
                amounts("ENERGY", "165"));
    }

    private static IngredientSnapshot pumpkin() {
        return ingredient("ing.bi-do", "Bí đỏ", FoodState.RAW, FoodGroup.VEGETABLE_FRUIT,
                amounts("ENERGY", "26"));
    }

    private static IngredientSnapshot fishSauce() {
        return ingredient("ing.nuoc-mam", "Nước mắm", FoodState.PROCESSED, FoodGroup.SEASONING,
                amounts("ENERGY", "50"));
    }

    private static IngredientSnapshot shrimp(FoodState state) {
        return ingredient("ing.tom", "Tôm", state, FoodGroup.MEAT_FISH_EGG, amounts("ENERGY", "90"));
    }

    private static IngredientSnapshot fish(String id, String name) {
        return ingredient(id, name, FoodState.RAW, FoodGroup.MEAT_FISH_EGG, amounts("ENERGY", "120"));
    }

    private static IngredientSnapshot ingredient(String id, String name, FoodState state,
                                                 FoodGroup group, Map<String, String> amounts) {
        List<NutrientSnapshot> nutrients = amounts.entrySet().stream()
                .map(entry -> new NutrientSnapshot(entry.getKey(), new BigDecimal(entry.getValue()),
                        "kcal", NutrientStatus.ESTIMATED))
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
}
