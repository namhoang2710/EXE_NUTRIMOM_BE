package vn.nutrimom.nutrition.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.NutrientStatus;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.TemplateMode;

/**
 * Hình dạng của bốn file seed trong {@code src/main/resources/nutrition/}.
 *
 * <p>Mọi record đều bỏ qua trường lạ: các file JSON có sẵn những khoá {@code _note} giải thích
 * cho người nhập liệu, chúng không phải dữ liệu.</p>
 *
 * <p>Các trường kiểu bao bọc ({@link BigDecimal}, {@link Integer}) cố ý <strong>không</strong>
 * dùng kiểu nguyên thuỷ: thiếu giá trị trong JSON phải hiện ra thành {@code null} để
 * {@link NutritionSeedValidator} báo lỗi, chứ không âm thầm thành 0.</p>
 */
public final class NutritionSeedData {

    private NutritionSeedData() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourcesFile(List<Source> sources, List<Nutrient> nutrients) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String id, String name, String recordRef, LocalDate retrievedOn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nutrient(String code, String name, String unit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RulesFile(String rulesVersion,
                            List<MicronutrientTarget> micronutrientTargets,
                            List<MealTemplate> mealTemplates,
                            List<WeeklyFoodLimit> weeklyFoodLimits) {
    }

    /**
     * Một nhóm thực phẩm có giới hạn theo tuần (nhánh 9).
     *
     * <p>Hình dạng này trùng với {@link NutritionRules.WeeklyFoodLimit} nhưng ở tầng khác:
     * {@code NutritionSeedData} là hình dạng <em>file</em> để validator kiểm trước khi nhập, còn
     * {@code NutritionRules} là tập quy tắc đã nạp. Cùng lý do với
     * {@link MicronutrientTarget} — chỉ record đó được dùng lại ở cả hai tầng vì nó không có tham
     * chiếu nào cần kiểm.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WeeklyFoodLimit(String code, String label, List<String> ingredientIds,
                                  BigDecimal maxGramPerWeek, String sourceId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MicronutrientTarget(String code, BigDecimal amountPerDay, String unit, String kind) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MealTemplate(MealType mealType, TemplateMode mode, List<RecipeRole> requiredRoles,
                               BigDecimal roleTargetShare, String defaultServingOptions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IngredientsFile(List<Allergen> allergens, List<Ingredient> ingredients) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Allergen(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ingredient(String id, String canonicalName, FoodState foodState, FoodGroup foodGroup,
                             AllergenStatus allergenStatus, List<String> allergenIds, List<String> aliases,
                             List<String> components, String sourceId,
                             List<NutrientAmount> nutrients, Price price) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NutrientAmount(String code, BigDecimal amountPer100g, String unit,
                                 NutrientStatus status, String sourceId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Price(BigDecimal pricePerBaseUnitVnd, String baseUnit, LocalDate estimatedAt,
                        String sourceNote) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipesFile(List<Recipe> recipes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recipe(String id, String name, Integer version, RecipeRole role,
                         List<MealType> mealTypes, BigDecimal servingsYield, BigDecimal servingGram,
                         Integer minutes, String mainIngredientTag, String imageKey,
                         String safetyNotes, String steps, List<RecipeIngredient> ingredients) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipeIngredient(String ingredientId, BigDecimal quantityGram,
                                   FoodState preparationState) {
    }
}
