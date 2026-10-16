package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.NutrientStatus;
import vn.nutrimom.nutrition.domain.RecipeEntity;

/**
 * Dữ liệu đầu vào của {@link NutritionCalculator}: ảnh chụp công thức và nguyên liệu tại một thời
 * điểm, <strong>không phải</strong> entity JPA.
 *
 * <p>Hai lý do bắt buộc phải là snapshot:</p>
 * <ol>
 *   <li><strong>Không truy vấn database trong vòng lặp tính.</strong> Mọi giá trị cần dùng đã nằm
 *       sẵn trong đối tượng; người gọi nạp nguyên liệu một lượt rồi dựng snapshot.</li>
 *   <li><strong>Lịch sử không bị viết lại.</strong> Nhật ký và mục kế hoạch ở nhánh 5 và 8 lưu lại
 *       chính snapshot này kèm {@code recipeVersion}. Seed sửa công thức về sau thì bữa đã ăn giữ
 *       nguyên con số lúc ăn (BR17).</li>
 * </ol>
 */
public final class NutritionSnapshots {

    private NutritionSnapshots() {
    }

    /**
     * Một giá trị dinh dưỡng trên 100 g phần ăn được.
     *
     * <p>{@code amountPer100g} null nghĩa là <strong>chưa có dữ liệu</strong>, không phải bằng 0
     * (BR08).</p>
     */
    public record NutrientSnapshot(String nutrientCode, BigDecimal amountPer100g, String unit,
                                   NutrientStatus status) {

        /** Chỉ dòng vừa có số vừa không mang trạng thái MISSING mới được cộng vào tổng. */
        public boolean hasValue() {
            return amountPer100g != null && status != NutrientStatus.MISSING;
        }
    }

    /**
     * Một nguyên liệu kèm bảng dinh dưỡng của nó.
     *
     * <p>{@code recordState} là trạng thái mà <strong>bản ghi dinh dưỡng</strong> mô tả, không phải
     * trạng thái trong món. Hai thứ lệch nhau thì mới cần hệ số giữ lại.</p>
     */
    public record IngredientSnapshot(String ingredientId, String canonicalName, FoodState recordState,
                                     FoodGroup foodGroup, List<NutrientSnapshot> nutrients) {

        public IngredientSnapshot {
            nutrients = nutrients == null ? List.of() : List.copyOf(nutrients);
        }

        public Optional<NutrientSnapshot> nutrient(String nutrientCode) {
            return nutrients.stream()
                    .filter(value -> value.nutrientCode().equals(nutrientCode))
                    .findFirst();
        }
    }

    /**
     * Một dòng nguyên liệu của công thức.
     *
     * <p>{@code quantityGram} là gram ở đúng {@code preparationState}. <strong>Không quy đổi ml
     * sang gram bằng một mật độ chung</strong> — dữ liệu vào đây đã phải là gram.</p>
     */
    public record RecipeLineSnapshot(IngredientSnapshot ingredient, BigDecimal quantityGram,
                                     FoodState preparationState) {

        public RecipeLineSnapshot {
            if (ingredient == null) {
                throw new IllegalArgumentException("Dòng nguyên liệu thiếu snapshot nguyên liệu");
            }
            if (quantityGram == null || quantityGram.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "quantityGram của " + ingredient.ingredientId() + " âm hoặc trống");
            }
        }
    }

    /**
     * Một công thức.
     *
     * <p>{@code servingGram} là khối lượng <strong>một phần đã nấu</strong>; null khi món chưa cân
     * được. Không bao giờ thay nó bằng tổng gram nguyên liệu sống.</p>
     */
    public record RecipeSnapshot(String recipeId, String name, int recipeVersion,
                                 BigDecimal servingsYield, BigDecimal servingGram,
                                 List<RecipeLineSnapshot> lines) {

        public RecipeSnapshot {
            if (servingsYield == null || servingsYield.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "servingsYield của " + recipeId + " phải lớn hơn 0 — mọi phép chia dinh "
                                + "dưỡng một phần đều dùng số này");
            }
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** Một món với số phần đã chọn — đơn vị tính của mục kế hoạch và mục nhật ký. */
    public record Portion(RecipeSnapshot recipe, BigDecimal servings) {

        public Portion {
            if (recipe == null) {
                throw new IllegalArgumentException("Thiếu công thức");
            }
            if (servings == null || servings.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "Số phần của " + recipe.recipeId() + " phải lớn hơn 0");
            }
        }
    }

    /** Lượng nguyên liệu sau khi quy theo số phần đã chọn — đầu vào của danh sách mua sắm. */
    public record ScaledIngredient(String ingredientId, String canonicalName, BigDecimal quantityGram,
                                   FoodState preparationState) {
    }

    /**
     * Dựng snapshot từ entity.
     *
     * <p>Người gọi nạp sẵn mọi nguyên liệu của món vào {@code ingredientsById} — thường bằng một
     * lượt {@code findAllById}. Thiếu một nguyên liệu là <strong>lỗi của người gọi</strong>, ném
     * ngay: tính tiếp với một dòng bị bỏ qua sẽ cho ra tổng thấp hơn thực tế mà nhìn vẫn như số
     * đầy đủ.</p>
     */
    public static RecipeSnapshot snapshotOf(RecipeEntity recipe,
                                            Map<String, IngredientEntity> ingredientsById) {
        List<RecipeLineSnapshot> lines = new ArrayList<>();
        for (var line : recipe.getIngredients()) {
            IngredientEntity ingredient = ingredientsById.get(line.getIngredientId());
            if (ingredient == null) {
                throw new IllegalArgumentException("Thiếu nguyên liệu " + line.getIngredientId()
                        + " khi dựng snapshot cho món " + recipe.getId());
            }
            lines.add(new RecipeLineSnapshot(snapshotOf(ingredient), line.getQuantityGram(),
                    line.getPreparationState()));
        }
        return new RecipeSnapshot(recipe.getId(), recipe.getName(), recipe.getVersion(),
                recipe.getServingsYield(), recipe.getServingGram(), lines);
    }

    public static IngredientSnapshot snapshotOf(IngredientEntity ingredient) {
        List<NutrientSnapshot> nutrients = ingredient.getNutrients().stream()
                .map(value -> new NutrientSnapshot(value.getNutrientCode(), value.getAmountPer100g(),
                        value.getUnit(), value.getStatus()))
                .toList();
        return new IngredientSnapshot(ingredient.getId(), ingredient.getCanonicalName(),
                ingredient.getFoodState(), ingredient.getFoodGroup(), nutrients);
    }
}
