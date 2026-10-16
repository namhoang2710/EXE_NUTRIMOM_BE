package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.NutrientStatus;
import vn.nutrimom.nutrition.service.NutritionSeedData.Ingredient;
import vn.nutrimom.nutrition.service.NutritionSeedData.IngredientsFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.Recipe;
import vn.nutrimom.nutrition.service.NutritionSeedData.RecipesFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.RulesFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.SourcesFile;

/**
 * Kiểm tra tập seed <strong>trước khi</strong> nhập vào database.
 *
 * <p>Đây là "script kiểm tra seed" mà đặc tả yêu cầu, hiện thực thành một lớp thuần để vừa chạy
 * lúc khởi động vừa chạy được trong test mà không cần database.</p>
 *
 * <p>Validator <strong>gom hết</strong> vi phạm rồi mới trả về, thay vì dừng ở lỗi đầu tiên: người
 * nhập liệu sửa một lượt được, khỏi phải chạy lại mười lần cho mười lỗi chính tả.</p>
 *
 * <p>Giới hạn đã biết: yêu cầu "gia vị và nước dùng hỗn hợp phải khai thành phần phụ"
 * <em>không</em> kiểm tự động được — muối và dầu ăn cũng thuộc nhóm gia vị mà không có thành phần
 * con nào, nên mọi quy tắc máy móc đều cho báo động giả. Thay vào đó validator kiểm điều kiểm
 * được: tham chiếu thành phần con phải phân giải được, không có vòng, và công thức nào chạm vào
 * nguyên liệu {@link AllergenStatus#UNKNOWN} thì bắt buộc phải có ghi chú an toàn.</p>
 */
public final class NutritionSeedValidator {

    private NutritionSeedValidator() {
    }

    /**
     * @return danh sách vi phạm; rỗng nghĩa là tập seed hợp lệ.
     */
    public static List<String> validate(SourcesFile sources, RulesFile rules,
                                        IngredientsFile ingredientsFile, RecipesFile recipesFile) {
        List<String> problems = new ArrayList<>();

        if (rules.rulesVersion() == null || rules.rulesVersion().isBlank()) {
            problems.add("nutritionRules.json: thiếu rulesVersion");
        }

        Map<String, String> nutrientUnits = new HashMap<>();
        Set<String> sourceIds = new HashSet<>();
        collectCatalogs(sources, nutrientUnits, sourceIds, problems);

        Set<String> allergenIds = new HashSet<>();
        for (var allergen : safe(ingredientsFile.allergens())) {
            if (allergen.id() == null || allergen.id().isBlank()) {
                problems.add("ingredients.json: có allergen thiếu id");
            } else if (!allergenIds.add(allergen.id())) {
                problems.add("ingredients.json: allergen trùng id " + allergen.id());
            }
        }

        Map<String, Ingredient> ingredients = new HashMap<>();
        for (var ingredient : safe(ingredientsFile.ingredients())) {
            if (ingredient.id() == null || ingredient.id().isBlank()) {
                problems.add("ingredients.json: có nguyên liệu thiếu id");
                continue;
            }
            if (ingredients.put(ingredient.id(), ingredient) != null) {
                problems.add("ingredients.json: nguyên liệu trùng id " + ingredient.id());
            }
        }

        ingredients.values().forEach(ingredient ->
                checkIngredient(ingredient, nutrientUnits, sourceIds, allergenIds, ingredients, problems));
        checkNoComponentCycle(ingredients, problems);

        Set<String> recipeIds = new HashSet<>();
        for (var recipe : safe(recipesFile.recipes())) {
            if (recipe.id() == null || recipe.id().isBlank()) {
                problems.add("recipes.json: có công thức thiếu id");
                continue;
            }
            if (!recipeIds.add(recipe.id())) {
                problems.add("recipes.json: công thức trùng id " + recipe.id());
            }
            checkRecipe(recipe, ingredients, problems);
        }

        for (var target : safe(rules.micronutrientTargets())) {
            if (!nutrientUnits.containsKey(target.code())) {
                problems.add("nutritionRules.json: mục tiêu vi chất tham chiếu chất lạ " + target.code());
            } else if (!nutrientUnits.get(target.code()).equals(target.unit())) {
                problems.add("nutritionRules.json: mục tiêu " + target.code() + " khai đơn vị "
                        + target.unit() + " nhưng danh mục chất là " + nutrientUnits.get(target.code()));
            }
        }

        for (var template : safe(rules.mealTemplates())) {
            if (template.requiredRoles() == null || template.requiredRoles().isEmpty()) {
                problems.add("nutritionRules.json: template " + template.mealType() + "/" + template.mode()
                        + " không khai vai trò nào");
            }
            if (isNotPositive(template.roleTargetShare())
                    || template.roleTargetShare().compareTo(BigDecimal.ONE) > 0) {
                problems.add("nutritionRules.json: template " + template.mealType() + "/" + template.mode()
                        + " có roleTargetShare ngoài khoảng (0, 1]");
            }
        }

        return problems;
    }

    private static void collectCatalogs(SourcesFile sources, Map<String, String> nutrientUnits,
                                        Set<String> sourceIds, List<String> problems) {
        for (var source : safe(sources.sources())) {
            if (source.id() == null || source.id().isBlank()) {
                problems.add("nutrientSources.json: có nguồn thiếu id");
            } else if (!sourceIds.add(source.id())) {
                problems.add("nutrientSources.json: nguồn trùng id " + source.id());
            }
        }
        for (var nutrient : safe(sources.nutrients())) {
            if (nutrient.code() == null || nutrient.code().isBlank()) {
                problems.add("nutrientSources.json: có chất thiếu code");
                continue;
            }
            if (nutrient.unit() == null || nutrient.unit().isBlank()) {
                problems.add("nutrientSources.json: chất " + nutrient.code() + " thiếu đơn vị");
                continue;
            }
            if (nutrientUnits.put(nutrient.code(), nutrient.unit()) != null) {
                problems.add("nutrientSources.json: chất trùng code " + nutrient.code());
            }
        }
    }

    private static void checkIngredient(Ingredient ingredient, Map<String, String> nutrientUnits,
                                        Set<String> sourceIds, Set<String> allergenIds,
                                        Map<String, Ingredient> ingredients, List<String> problems) {
        String id = ingredient.id();
        if (ingredient.canonicalName() == null || ingredient.canonicalName().isBlank()) {
            problems.add(id + ": thiếu canonicalName");
        }
        if (ingredient.foodState() == null) {
            problems.add(id + ": thiếu foodState");
        }
        if (ingredient.foodGroup() == null) {
            problems.add(id + ": thiếu foodGroup");
        }
        if (ingredient.allergenStatus() == null) {
            problems.add(id + ": thiếu allergenStatus");
        }
        if (ingredient.sourceId() != null && !sourceIds.contains(ingredient.sourceId())) {
            problems.add(id + ": sourceId không tồn tại " + ingredient.sourceId());
        }
        if (ingredient.allergenStatus() == AllergenStatus.UNKNOWN
                && !safe(ingredient.allergenIds()).isEmpty()) {
            problems.add(id + ": allergenStatus UNKNOWN nhưng vẫn liệt kê thành phần dị ứng — "
                    + "hai trạng thái này loại trừ nhau");
        }
        for (String allergenId : safe(ingredient.allergenIds())) {
            if (!allergenIds.contains(allergenId)) {
                problems.add(id + ": tham chiếu allergen lạ " + allergenId);
            }
        }
        for (String childId : safe(ingredient.components())) {
            if (childId.equals(id)) {
                problems.add(id + ": tự khai mình là thành phần con");
            } else if (!ingredients.containsKey(childId)) {
                problems.add(id + ": thành phần con không tồn tại " + childId);
            }
        }

        Set<String> seenCodes = new HashSet<>();
        for (var nutrient : safe(ingredient.nutrients())) {
            if (!seenCodes.add(nutrient.code())) {
                problems.add(id + ": khai trùng chất " + nutrient.code());
            }
            String expectedUnit = nutrientUnits.get(nutrient.code());
            if (expectedUnit == null) {
                problems.add(id + ": tham chiếu chất lạ " + nutrient.code());
            } else if (!expectedUnit.equals(nutrient.unit())) {
                problems.add(id + ": chất " + nutrient.code() + " khai đơn vị " + nutrient.unit()
                        + " nhưng danh mục là " + expectedUnit);
            }
            if (nutrient.status() == null) {
                problems.add(id + ": chất " + nutrient.code() + " thiếu status");
                continue;
            }
            // Thiếu dữ liệu phải là null, không được là 0 (BR08). Hai cột không được nói ngược nhau.
            boolean missing = nutrient.status() == NutrientStatus.MISSING;
            if (missing && nutrient.amountPer100g() != null) {
                problems.add(id + ": chất " + nutrient.code() + " status MISSING nhưng vẫn có số");
            }
            if (!missing && nutrient.amountPer100g() == null) {
                problems.add(id + ": chất " + nutrient.code() + " có status " + nutrient.status()
                        + " nhưng thiếu số — dùng status MISSING nếu chưa có dữ liệu");
            }
            if (nutrient.amountPer100g() != null
                    && nutrient.amountPer100g().compareTo(BigDecimal.ZERO) < 0) {
                problems.add(id + ": chất " + nutrient.code() + " có giá trị âm");
            }
            if (nutrient.sourceId() != null && !sourceIds.contains(nutrient.sourceId())) {
                problems.add(id + ": chất " + nutrient.code() + " tham chiếu nguồn lạ "
                        + nutrient.sourceId());
            }
        }

        var price = ingredient.price();
        if (price != null) {
            if (isNegative(price.pricePerBaseUnitVnd())) {
                problems.add(id + ": giá âm");
            }
            if (price.pricePerBaseUnitVnd() == null || price.baseUnit() == null
                    || price.baseUnit().isBlank() || price.estimatedAt() == null) {
                // estimatedAt bắt buộc (BR18): màn hình phải nói được giá tham khảo của thời điểm nào.
                problems.add(id + ": khối price thiếu pricePerBaseUnitVnd, baseUnit hoặc estimatedAt");
            }
        }
    }

    /**
     * Phát hiện vòng trong quan hệ thành phần con bằng DFS màu ba trạng thái.
     *
     * <p>Ràng buộc CHECK ở database chỉ chặn được trường hợp tự trỏ vào chính mình; vòng bắc cầu
     * (A chứa B, B chứa C, C chứa A) phải bắt ở đây. Vòng sẽ làm EligibilityFilter ở nhánh 4 đệ quy
     * vô hạn khi đi tìm thành phần dị ứng.</p>
     */
    private static void checkNoComponentCycle(Map<String, Ingredient> ingredients,
                                              List<String> problems) {
        Set<String> settled = new HashSet<>();
        Set<String> onPath = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String id : ingredients.keySet()) {
            if (!settled.contains(id) && walk(id, ingredients, settled, onPath, path)) {
                problems.add("Vòng trong thành phần con, đi qua: " + String.join(" -> ", path));
                return;
            }
        }
    }

    private static boolean walk(String id, Map<String, Ingredient> ingredients, Set<String> settled,
                                Set<String> onPath, Deque<String> path) {
        if (onPath.contains(id)) {
            path.addLast(id);
            return true;
        }
        if (settled.contains(id) || !ingredients.containsKey(id)) {
            return false;
        }
        onPath.add(id);
        path.addLast(id);
        for (String childId : safe(ingredients.get(id).components())) {
            if (walk(childId, ingredients, settled, onPath, path)) {
                return true;
            }
        }
        path.removeLast();
        onPath.remove(id);
        settled.add(id);
        return false;
    }

    private static void checkRecipe(Recipe recipe, Map<String, Ingredient> ingredients,
                                    List<String> problems) {
        String id = recipe.id();
        if (recipe.name() == null || recipe.name().isBlank()) {
            problems.add(id + ": thiếu tên");
        }
        if (recipe.role() == null) {
            problems.add(id + ": thiếu vai trò");
        }
        if (recipe.mealTypes() == null || recipe.mealTypes().isEmpty()) {
            problems.add(id + ": không khai loại bữa nào");
        }
        if (isNotPositive(recipe.servingsYield())) {
            problems.add(id + ": servingsYield phải lớn hơn 0 — mọi phép chia dinh dưỡng một phần "
                    + "đều dùng số này");
        }
        if (recipe.servingGram() != null && isNotPositive(recipe.servingGram())) {
            problems.add(id + ": servingGram phải lớn hơn 0 khi đã khai");
        }
        if (recipe.minutes() == null || recipe.minutes() <= 0) {
            problems.add(id + ": minutes phải lớn hơn 0");
        }
        if (recipe.version() == null || recipe.version() < 1) {
            problems.add(id + ": version phải từ 1 trở lên");
        }
        if (recipe.steps() == null || recipe.steps().isBlank()) {
            problems.add(id + ": thiếu các bước nấu");
        }
        if (recipe.ingredients() == null || recipe.ingredients().isEmpty()) {
            problems.add(id + ": không có nguyên liệu nào");
            return;
        }

        Set<String> seen = new HashSet<>();
        boolean touchesUnknown = false;
        for (var line : recipe.ingredients()) {
            if (line.ingredientId() == null || !ingredients.containsKey(line.ingredientId())) {
                problems.add(id + ": nguyên liệu không tồn tại " + line.ingredientId());
                continue;
            }
            if (!seen.add(line.ingredientId())) {
                problems.add(id + ": khai trùng nguyên liệu " + line.ingredientId());
            }
            if (isNegative(line.quantityGram()) || line.quantityGram() == null) {
                problems.add(id + ": nguyên liệu " + line.ingredientId() + " có quantityGram âm hoặc trống");
            }
            if (line.preparationState() == null) {
                problems.add(id + ": nguyên liệu " + line.ingredientId() + " thiếu preparationState");
            }
            Ingredient ingredient = ingredients.get(line.ingredientId());
            if (ingredient.allergenStatus() == AllergenStatus.UNKNOWN) {
                touchesUnknown = true;
            }
        }

        // Món chạm vào nguyên liệu chưa rà soát dị ứng sẽ bị EligibilityFilter loại khỏi thực đơn cá
        // nhân hoá (BR03), nhưng vẫn xem được trong phần tra cứu — nên phải nói rõ lý do cho người đọc.
        if (touchesUnknown && (recipe.safetyNotes() == null || recipe.safetyNotes().isBlank())) {
            problems.add(id + ": dùng nguyên liệu chưa rà soát dị ứng nhưng không có safetyNotes");
        }

        if (recipe.mainIngredientTag() != null
                && !ingredients.containsKey(recipe.mainIngredientTag())) {
            problems.add(id + ": mainIngredientTag trỏ tới nguyên liệu lạ " + recipe.mainIngredientTag());
        }
    }

    private static boolean isNotPositive(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0;
    }

    private static boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
