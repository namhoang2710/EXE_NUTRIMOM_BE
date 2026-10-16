package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;

/**
 * Một dòng nguyên liệu của công thức (bảng {@code app.recipe_ingredients}, migration V57).
 *
 * <p>{@code quantityGram} là gram <strong>đúng trạng thái</strong> {@code preparationState} — đây
 * chính là trạng thái mà bản ghi dinh dưỡng được tham chiếu mô tả. Không quy đổi thìa hay ml sang
 * gram bằng một mật độ chung.</p>
 */
@Embeddable
public class RecipeIngredientValue {

    @Column(name = "ingredient_id", nullable = false, length = 64)
    private String ingredientId;

    @Column(name = "quantity_gram", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityGram;

    @Enumerated(EnumType.STRING)
    @Column(name = "preparation_state", nullable = false, length = 20)
    private FoodState preparationState;

    protected RecipeIngredientValue() {
    }

    public RecipeIngredientValue(String ingredientId, BigDecimal quantityGram,
                                 FoodState preparationState) {
        this.ingredientId = ingredientId;
        this.quantityGram = quantityGram;
        this.preparationState = preparationState;
    }

    public String getIngredientId() { return ingredientId; }

    public BigDecimal getQuantityGram() { return quantityGram; }

    public FoodState getPreparationState() { return preparationState; }
}
