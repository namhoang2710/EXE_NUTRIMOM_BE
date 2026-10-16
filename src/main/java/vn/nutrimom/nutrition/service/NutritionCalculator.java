package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.service.NutritionSnapshots.IngredientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.NutrientSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.Portion;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeLineSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.ScaledIngredient;
import vn.nutrimom.nutrition.service.NutritionTotals.NutrientTotal;

/**
 * Bộ tính dinh dưỡng <strong>duy nhất</strong> của hệ thống.
 *
 * <p>Lập kế hoạch (nhánh 5), đổi món (nhánh 6), danh sách mua sắm (nhánh 7) và nhật ký (nhánh 8)
 * đều gọi lại lớp này. <strong>Không nơi nào khác được tự cộng tổng dinh dưỡng</strong>: có nơi
 * thứ hai thì sớm muộn tổng API trả về sẽ lệch với tổng tính từ snapshot của từng mục, và không
 * ai biết bên nào đúng.</p>
 *
 * <h2>Công thức</h2>
 * <pre>
 * lượng chất k của công thức = Σ ( q_i / 100 × a_i,k × r_i,k )
 * lượng chất k một phần      = lượng của công thức / servings_yield
 * lượng chất k của mục       = lượng một phần × servings_selected
 * </pre>
 *
 * <p>Chia cho {@code servings_yield} <strong>trước</strong> rồi mới nhân số phần, đúng thứ tự đặc
 * tả. Nhờ vậy hai mục cùng món khác số phần luôn tỉ lệ đúng với nhau: 1,5 phần bằng đúng 1,5 lần
 * 1 phần, không lệch vì làm tròn trung gian (TC08).</p>
 *
 * <h2>Thiếu dữ liệu</h2>
 *
 * <p>Nguyên liệu không khai một chất, hoặc khai với trạng thái {@code MISSING}, thì chất đó
 * <strong>không được cộng 0</strong>: tổng chỉ gồm phần đã biết, nguyên liệu bị ghi vào
 * {@link NutrientTotal#missingIngredientIds()} và cả chất mang {@link CoverageStatus#PARTIAL}
 * (BR08).</p>
 *
 * <h2>Trạng thái thực phẩm</h2>
 *
 * <p>Hệ số giữ lại {@code r} chỉ xuất hiện khi bản ghi dinh dưỡng mô tả một trạng thái còn công
 * thức dùng nguyên liệu ở trạng thái khác. Bản ghi <strong>đã</strong> mô tả thực phẩm chín thì
 * {@code r = 1} và không áp hao hụt lần nữa. Chưa có hệ số phù hợp thì vẫn {@code r = 1} nhưng
 * {@link NutritionTotals#methodNotes()} ghi rõ đó là giả định ước tính.</p>
 *
 * <p>Lớp này <strong>không quy đổi ml sang gram</strong> và không có mật độ mặc định nào: đầu vào
 * đã phải là gram ở đúng trạng thái.</p>
 */
@Service
public class NutritionCalculator {

    /** Mã chất năng lượng — luôn có mặt trong kết quả để lớp trên so với mục tiêu EER. */
    public static final String ENERGY = "ENERGY";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Độ chính xác của phép chia, <strong>không phải</strong> làm tròn hiển thị.
     *
     * <p>Chia cho {@code servings_yield} có thể không kết thúc (chia 3), nên BigDecimal buộc phải
     * có giới hạn chữ số. 16 chữ số có nghĩa là thừa xa so với mọi con số dinh dưỡng thật; làm
     * tròn về 1 hay 2 chữ số là việc của lớp hiển thị, không bao giờ của lớp này.</p>
     */
    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);

    private final NutritionRules rules;

    public NutritionCalculator(NutritionRulesProvider provider) {
        this.rules = provider.rules();
    }

    /** Tập quy tắc đang dùng — nhánh 5 ghi {@code rulesVersion} vào snapshot kế hoạch. */
    public NutritionRules rules() {
        return rules;
    }

    /** Tổng của cả nồi, tức {@code servings_yield} phần. */
    public NutritionTotals forRecipe(RecipeSnapshot recipe) {
        return forPortion(recipe, recipe.servingsYield());
    }

    /** Tổng của một mục: {@code servings} phần của một món. */
    public NutritionTotals forPortion(RecipeSnapshot recipe, BigDecimal servings) {
        return forPortions(List.of(new Portion(recipe, servings)));
    }

    /**
     * Tổng của nhiều mục — một bữa, một ngày, hay cả tuần.
     *
     * <p>Cố ý <strong>không</strong> có hàm cộng hai {@link NutritionTotals} đã tính: kết quả
     * không còn mang theo nguyên liệu nào thiếu chất nào, nên cộng chúng lại sẽ làm mất danh sách
     * thiếu và có thể báo COMPLETE cho một chất mà phía kia chưa hề có dữ liệu. Muốn tổng của một
     * tập mục thì đưa cả tập vào đây.</p>
     */
    public NutritionTotals forPortions(List<Portion> portions) {
        Set<String> notes = new LinkedHashSet<>();
        Map<String, NutrientTotal> totals = new LinkedHashMap<>();
        for (String code : nutrientCodes(portions)) {
            totals.put(code, totalFor(code, portions, notes));
        }
        return new NutritionTotals(rules.rulesVersion(), totals, List.copyOf(notes));
    }

    /**
     * Lượng nguyên liệu cần cho {@code servings} phần.
     *
     * <p>Chia rồi mới nhân, giống hệt đường tính dinh dưỡng, nên nguyên liệu và dinh dưỡng của
     * cùng một mục luôn cùng một tỉ lệ (TC08). Danh sách mua sắm ở nhánh 7 dựng từ đây, và
     * <strong>không gộp</strong> hai dòng khác {@code preparationState}.</p>
     */
    public List<ScaledIngredient> ingredientsFor(RecipeSnapshot recipe, BigDecimal servings) {
        BigDecimal portion = requirePositive(servings, recipe.recipeId());
        List<ScaledIngredient> scaled = new ArrayList<>();
        for (var line : recipe.lines()) {
            BigDecimal perServing = line.quantityGram().divide(recipe.servingsYield(), MATH);
            scaled.add(new ScaledIngredient(line.ingredient().ingredientId(),
                    line.ingredient().canonicalName(),
                    perServing.multiply(portion),
                    line.preparationState()));
        }
        return List.copyOf(scaled);
    }

    /**
     * Quy một lượng gram <strong>món đã nấu</strong> ra số phần.
     *
     * <p>Dùng khối lượng chín của một phần, nên tỉ lệ lấy theo món chín chứ không theo gram nguyên
     * liệu sống — 300 g gạo không phải 300 g cơm.</p>
     *
     * @return rỗng khi món <strong>chưa cân được</strong> khối lượng chín. Lúc đó hãy hỏi người
     *         dùng theo số phần; tuyệt đối không lấy tổng gram nguyên liệu sống thay vào.
     */
    public Optional<BigDecimal> servingsForCookedGram(RecipeSnapshot recipe, BigDecimal cookedGram) {
        if (recipe.servingGram() == null || recipe.servingGram().compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        BigDecimal gram = requirePositive(cookedGram, recipe.recipeId());
        return Optional.of(gram.divide(recipe.servingGram(), MATH));
    }

    /**
     * Những chất sẽ có mặt trong kết quả.
     *
     * <p>Gồm cả chất <strong>không nguyên liệu nào khai</strong>: mục tiêu vi chất và năng lượng
     * luôn xuất hiện, mang giá trị null và PARTIAL. Im lặng bỏ qua một chất sẽ khiến giao diện
     * không có gì để nói "chưa đủ dữ liệu cho iốt", và người dùng tưởng mình đã xem đủ.</p>
     */
    private Set<String> nutrientCodes(List<Portion> portions) {
        Set<String> codes = new LinkedHashSet<>();
        codes.add(ENERGY);
        rules.micronutrientTargets().forEach(target -> codes.add(target.code()));
        for (var portion : portions) {
            for (var line : portion.recipe().lines()) {
                line.ingredient().nutrients().forEach(value -> codes.add(value.nutrientCode()));
            }
        }
        return codes;
    }

    private NutrientTotal totalFor(String code, List<Portion> portions, Set<String> notes) {
        BigDecimal total = null;
        String unit = rules.unitFor(code).orElse(null);
        Set<String> missing = new LinkedHashSet<>();

        for (var portion : portions) {
            RecipeSnapshot recipe = portion.recipe();
            BigDecimal recipeAmount = null;
            for (var line : recipe.lines()) {
                Optional<NutrientSnapshot> value = line.ingredient().nutrient(code);
                if (value.isEmpty() || !value.get().hasValue()) {
                    // Không khai và khai MISSING đều là chưa có dữ liệu. Cộng 0 ở đây chính là lỗi
                    // mà BR08 cấm: nó biến "chưa đo" thành "không chứa".
                    missing.add(line.ingredient().ingredientId());
                    continue;
                }
                unit = checkUnit(code, unit, value.get(), line.ingredient());
                BigDecimal contribution = line.quantityGram()
                        .divide(HUNDRED, MATH)
                        .multiply(value.get().amountPer100g())
                        .multiply(retention(line, code, notes));
                recipeAmount = recipeAmount == null ? contribution : recipeAmount.add(contribution);
            }
            if (recipeAmount == null) {
                continue;
            }
            BigDecimal perServing = recipeAmount.divide(recipe.servingsYield(), MATH);
            BigDecimal amount = perServing.multiply(portion.servings());
            total = total == null ? amount : total.add(amount);
        }

        // null không bao giờ là COMPLETE: không có số thì không có gì để khẳng định là đã đủ.
        CoverageStatus coverage = CoverageStatus.of(missing.isEmpty() && total != null);
        return new NutrientTotal(code, unit, total, List.copyOf(missing), coverage);
    }

    /**
     * Hệ số giữ lại của một dòng nguyên liệu.
     *
     * <p>Bản ghi dinh dưỡng mô tả đúng trạng thái đang dùng thì {@code r = 1} và
     * <strong>không</strong> ghi chú gì: đó là số đo của chính trạng thái đó, áp thêm hao hụt là
     * trừ hai lần.</p>
     */
    private BigDecimal retention(RecipeLineSnapshot line, String code, Set<String> notes) {
        IngredientSnapshot ingredient = line.ingredient();
        FoodState record = ingredient.recordState();
        FoodState used = line.preparationState();
        if (record == used) {
            return BigDecimal.ONE;
        }

        Optional<BigDecimal> factor =
                rules.retentionFactor(record, used, code, ingredient.foodGroup());
        if (factor.isPresent()) {
            notes.add("%s: chất %s dùng hệ số giữ lại %s cho chuyển trạng thái %s sang %s."
                    .formatted(ingredient.canonicalName(), code, factor.get().toPlainString(),
                            record, used));
            return factor.get();
        }

        notes.add(("%s: bản ghi dinh dưỡng mô tả trạng thái %s nhưng món dùng ở trạng thái %s, "
                + "chưa có hệ số giữ lại phù hợp nên tính với r = 1. Đây là giả định ước tính, "
                + "không phải số đo sau nấu.")
                .formatted(ingredient.canonicalName(), record, used));
        return BigDecimal.ONE;
    }

    /**
     * Hai nguyên liệu khai cùng một chất bằng hai đơn vị là lỗi dữ liệu, không phải chuyện cộng
     * được. Ném to còn hơn cộng mg vào mcg rồi trả ra một con số trông vẫn hợp lý.
     */
    private String checkUnit(String code, String unit, NutrientSnapshot value,
                             IngredientSnapshot ingredient) {
        if (unit != null && !unit.equals(value.unit())) {
            throw new IllegalStateException("Chất " + code + " của nguyên liệu "
                    + ingredient.ingredientId() + " khai đơn vị " + value.unit()
                    + " nhưng đơn vị đang dùng là " + unit);
        }
        return value.unit();
    }

    private static BigDecimal requirePositive(BigDecimal value, String recipeId) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Giá trị cho món " + recipeId + " phải lớn hơn 0");
        }
        return value;
    }
}
