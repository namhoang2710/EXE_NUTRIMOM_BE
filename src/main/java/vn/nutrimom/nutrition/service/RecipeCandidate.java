package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.domain.RecipeRole;

/**
 * Một món ứng viên, dạng ảnh chụp — <strong>không phải</strong> entity JPA.
 *
 * <p>Cùng lý do với {@code NutritionSnapshots} ở nhánh 2: {@link EligibilityFilter} và
 * {@link RecipeRanker} duyệt qua hàng chục món và hàng trăm nguyên liệu con, nên mọi thứ chúng cần
 * phải nằm sẵn trong bộ nhớ. Một lượt {@code findAllById} ở lớp trên, rồi không truy vấn gì nữa
 * trong vòng lặp.</p>
 *
 * <p>Nhờ vậy cả hai lớp kia kiểm được bằng test thuần: dựng vài {@link IngredientFacts} bằng tay
 * là đủ dựng lại đúng tình huống "nước dùng có tôm khô" của TC01, không cần database.</p>
 */
public record RecipeCandidate(String recipeId, String name, int recipeVersion, RecipeRole role,
                              Set<MealType> mealTypes, int minutes, String mainIngredientTag,
                              BigDecimal servingsYield, List<IngredientFacts> ingredients) {

    public RecipeCandidate {
        mealTypes = mealTypes == null ? Set.of() : Set.copyOf(mealTypes);
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
    }

    /**
     * Những gì bước lọc cần biết về một nguyên liệu.
     *
     * <p>{@code allergenIds} rỗng chỉ có nghĩa "đã rà và không ghi nhận" khi
     * {@code allergenStatus} là {@link AllergenStatus#KNOWN}. Với {@link AllergenStatus#UNKNOWN}
     * thì rỗng nghĩa là <em>chưa rà</em> — hai thứ khác hẳn nhau (BR03).</p>
     *
     * <p>{@code componentIngredientIds} là nguyên liệu con của gia vị và nước dùng hỗn hợp.
     * {@link EligibilityFilter} phải duyệt <strong>đệ quy</strong> qua đây; bỏ qua một tầng là
     * món canh nấu bằng nước dùng có tôm khô lọt lưới với người dị ứng tôm (TC01).</p>
     */
    public record IngredientFacts(String ingredientId, String canonicalName, FoodGroup foodGroup,
                                  AllergenStatus allergenStatus, Set<String> allergenIds,
                                  Set<String> componentIngredientIds) {

        public IngredientFacts {
            allergenIds = allergenIds == null ? Set.of() : Set.copyOf(allergenIds);
            componentIngredientIds = componentIngredientIds == null
                    ? Set.of() : Set.copyOf(componentIngredientIds);
        }

        public static IngredientFacts of(IngredientEntity ingredient) {
            return new IngredientFacts(ingredient.getId(), ingredient.getCanonicalName(),
                    ingredient.getFoodGroup(), ingredient.getAllergenStatus(),
                    new LinkedHashSet<>(ingredient.getAllergenIds()),
                    new LinkedHashSet<>(ingredient.getComponentIngredientIds()));
        }
    }

    /**
     * Dựng ứng viên từ entity.
     *
     * <p>Thiếu một nguyên liệu trong {@code ingredientsById} là <strong>lỗi của người gọi</strong>
     * và phải ném ngay: lọc tiếp với một dòng bị bỏ qua nghĩa là bỏ sót đúng cái dòng có thể chứa
     * thành phần gây dị ứng, mà kết quả nhìn vẫn như đã kiểm đủ.</p>
     */
    public static RecipeCandidate of(RecipeEntity recipe,
                                     Map<String, IngredientEntity> ingredientsById) {
        List<IngredientFacts> facts = new ArrayList<>();
        for (var line : recipe.getIngredients()) {
            IngredientEntity ingredient = ingredientsById.get(line.getIngredientId());
            if (ingredient == null) {
                throw new IllegalArgumentException("Thiếu nguyên liệu " + line.getIngredientId()
                        + " khi dựng ứng viên cho món " + recipe.getId());
            }
            facts.add(IngredientFacts.of(ingredient));
        }
        return new RecipeCandidate(recipe.getId(), recipe.getName(), recipe.getVersion(),
                recipe.getRole(), recipe.getMealTypes(), recipe.getMinutes(),
                recipe.getMainIngredientTag(), recipe.getServingsYield(), facts);
    }
}
