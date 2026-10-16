package vn.nutrimom.nutrition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RecipeRole;

/** Hợp đồng JSON của hai endpoint tra cứu công thức. Serialize snake_case như phần còn lại của API. */
public final class NutritionDtos {

    private NutritionDtos() {
    }

    /**
     * Một dòng trong danh sách món.
     *
     * <p>{@code imageUrl} được dựng từ object key lúc trả về, không lưu sẵn trong database — đổi
     * domain CDN thì không phải migrate dữ liệu. Null khi món chưa có ảnh hoặc chưa cấu hình CDN.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecipeSummary(String id, String name, int version, RecipeRole role,
                                Set<MealType> mealTypes, int minutes, BigDecimal servingsYield,
                                BigDecimal servingGram, String imageUrl, String mainIngredientTag) {
    }

    /**
     * Giá <strong>ước tính</strong> của một nguyên liệu.
     *
     * <p>{@code estimatedAt} luôn đi kèm để màn hình nói được giá tham khảo của thời điểm nào
     * (BR18). Nguyên liệu không có khối này nghĩa là <em>chưa có giá</em> — hiển thị "Chưa có giá",
     * không hiển thị 0 đồng.</p>
     */
    public record PriceView(BigDecimal pricePerBaseUnitVnd, String baseUnit, LocalDate estimatedAt) {
    }

    /**
     * Một dòng nguyên liệu của công thức.
     *
     * <p>Trả kèm {@code allergenStatus} và {@code allergenIds} để giao diện giải thích được vì sao
     * một món bị loại khỏi thực đơn cá nhân hoá, thay vì chỉ ẩn nó đi.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecipeIngredientView(String ingredientId, String canonicalName,
                                       BigDecimal quantityGram, FoodState preparationState,
                                       FoodGroup foodGroup, AllergenStatus allergenStatus,
                                       List<String> allergenIds, PriceView price) {
    }

    /**
     * Trạng thái dữ liệu của công thức — <strong>không phải</strong> tổng dinh dưỡng.
     *
     * <p>Việc tính tổng thuộc {@code NutritionCalculator} ở nhánh 2. Nhánh này chỉ báo dữ liệu
     * đang <em>thiếu</em> ở đâu, để giao diện ghi "chưa đủ dữ liệu" thay vì tô đầy thanh tiến trình
     * hay điền 0 (BR08).</p>
     *
     * @param nutrientCodesMissingData       chất có ít nhất một nguyên liệu chưa có số liệu
     * @param ingredientsWithUnknownAllergen nguyên liệu <em>chưa rà soát</em> thành phần dị ứng;
     *                                       món chạm vào chúng sẽ bị loại khỏi thực đơn cá nhân hoá
     *                                       (BR03) dù vẫn tra cứu được
     * @param priceKnownForAllIngredients    false thì tổng chi phí ở các nhánh sau phải mang nhãn
     *                                       chưa đầy đủ
     */
    public record RecipeDataState(List<String> nutrientCodesMissingData,
                                  List<String> ingredientsWithUnknownAllergen,
                                  boolean priceKnownForAllIngredients) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecipeDetail(RecipeSummary summary, String steps, String safetyNotes,
                               List<RecipeIngredientView> ingredients, RecipeDataState dataState) {
    }
}
