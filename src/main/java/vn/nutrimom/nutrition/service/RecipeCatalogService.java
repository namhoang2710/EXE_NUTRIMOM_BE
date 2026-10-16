package vn.nutrimom.nutrition.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.NutrientStatus;
import vn.nutrimom.nutrition.domain.PriceEstimateEntity;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.dto.NutritionDtos.PriceView;
import vn.nutrimom.nutrition.dto.NutritionDtos.RecipeDataState;
import vn.nutrimom.nutrition.dto.NutritionDtos.RecipeDetail;
import vn.nutrimom.nutrition.dto.NutritionDtos.RecipeIngredientView;
import vn.nutrimom.nutrition.dto.NutritionDtos.RecipeSummary;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.PriceEstimateRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;

/**
 * Tra cứu công thức cho hai endpoint công khai.
 *
 * <p>Chỉ đọc. Dữ liệu do {@link NutritionSeedLoader} nạp; không có đường ghi nào từ API.</p>
 *
 * <p><strong>Cố ý không tính tổng dinh dưỡng và không cộng tổng chi phí của món.</strong> Tổng
 * dinh dưỡng thuộc {@code NutritionCalculator} ở nhánh 2. Tổng chi phí đòi quy đổi đơn vị
 * (kg, lít, quả) sang lượng gram của công thức, mà quy tắc quy đổi chưa được định nghĩa — đoán bừa
 * một mật độ chung chính là lỗi mà đặc tả cấm. Ở đây chỉ trả giá từng nguyên liệu kèm đơn vị gốc.</p>
 */
@Service
public class RecipeCatalogService {

    /** Chặn trên của {@code limit} để một lượt gọi không kéo cả danh mục. */
    private static final int MAX_PAGE_SIZE = 50;

    private final RecipeRepository recipes;
    private final IngredientRepository ingredients;
    private final PriceEstimateRepository prices;
    private final String publicBaseUrl;

    public RecipeCatalogService(RecipeRepository recipes, IngredientRepository ingredients,
                                PriceEstimateRepository prices,
                                @Value("${app.knowledge.r2.public-base-url:}") String publicBaseUrl) {
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.prices = prices;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Danh sách món, phân trang bằng con trỏ.
     *
     * <p>Đọc dư một dòng để biết còn trang sau hay không, rồi cắt bỏ — rẻ hơn một câu COUNT trên
     * bảng có join điều kiện.</p>
     */
    @Transactional(readOnly = true)
    public CursorPage<RecipeSummary> list(MealType mealType, RecipeRole role, Integer maxMinutes,
                                          String cursor, Integer limit) {
        int pageSize = limit == null ? 20 : Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        List<RecipeEntity> rows =
                recipes.search(mealType, role, maxMinutes, cursor, Limit.of(pageSize + 1));

        boolean hasMore = rows.size() > pageSize;
        List<RecipeEntity> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<RecipeSummary> items = page.stream().map(this::toSummary).toList();
        String nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;
        return new CursorPage<>(items, nextCursor, hasMore);
    }

    @Transactional(readOnly = true)
    public RecipeDetail detail(String recipeId) {
        RecipeEntity recipe = recipes.findById(recipeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy món này."));

        List<String> ingredientIds = recipe.getIngredients().stream()
                .map(line -> line.getIngredientId())
                .toList();
        Map<String, IngredientEntity> byId = ingredients.findAllById(ingredientIds).stream()
                .collect(Collectors.toMap(IngredientEntity::getId, Function.identity()));
        Map<String, PriceEstimateEntity> priceById =
                prices.findByIngredientIdIn(ingredientIds).stream()
                        .collect(Collectors.toMap(PriceEstimateEntity::getIngredientId,
                                Function.identity()));

        List<RecipeIngredientView> lines = new ArrayList<>();
        Set<String> missingNutrients = new LinkedHashSet<>();
        List<String> unknownAllergen = new ArrayList<>();
        boolean priceComplete = true;

        for (var line : recipe.getIngredients()) {
            IngredientEntity ingredient = byId.get(line.getIngredientId());
            if (ingredient == null) {
                // Seed đã qua validator nên trường hợp này chỉ xảy ra khi ai đó sửa tay database.
                // Bỏ qua dòng còn hơn trả nửa vời mà không nói gì, nên đánh dấu dữ liệu chưa đủ.
                priceComplete = false;
                continue;
            }
            PriceEstimateEntity price = priceById.get(ingredient.getId());
            if (price == null) {
                priceComplete = false;
            }
            if (ingredient.getAllergenStatus() == AllergenStatus.UNKNOWN) {
                unknownAllergen.add(ingredient.getId());
            }
            ingredient.getNutrients().stream()
                    .filter(value -> value.getStatus() == NutrientStatus.MISSING
                            || value.getAmountPer100g() == null)
                    .forEach(value -> missingNutrients.add(value.getNutrientCode()));

            lines.add(new RecipeIngredientView(
                    ingredient.getId(),
                    ingredient.getCanonicalName(),
                    line.getQuantityGram(),
                    line.getPreparationState(),
                    ingredient.getFoodGroup(),
                    ingredient.getAllergenStatus(),
                    List.copyOf(ingredient.getAllergenIds()),
                    price == null ? null : new PriceView(price.getPricePerBaseUnitVnd(),
                            price.getBaseUnit(), price.getEstimatedAt())));
        }

        var dataState = new RecipeDataState(
                missingNutrients.stream().sorted().toList(),
                unknownAllergen.stream().sorted().toList(),
                priceComplete);

        return new RecipeDetail(toSummary(recipe), recipe.getSteps(), recipe.getSafetyNotes(),
                lines, dataState);
    }

    private RecipeSummary toSummary(RecipeEntity recipe) {
        return new RecipeSummary(
                recipe.getId(),
                recipe.getName(),
                recipe.getVersion(),
                recipe.getRole(),
                recipe.getMealTypes().stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                recipe.getMinutes(),
                recipe.getServingsYield(),
                recipe.getServingGram(),
                imageUrl(recipe.getImageKey()),
                recipe.getMainIngredientTag());
    }

    /** Dựng URL ảnh từ object key. Null khi món chưa có ảnh hoặc chưa cấu hình CDN công khai. */
    private String imageUrl(String imageKey) {
        if (imageKey == null || imageKey.isBlank() || publicBaseUrl.isEmpty()) {
            return null;
        }
        return publicBaseUrl + "/" + imageKey;
    }
}
