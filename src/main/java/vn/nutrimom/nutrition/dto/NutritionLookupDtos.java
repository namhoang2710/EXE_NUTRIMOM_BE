package vn.nutrimom.nutrition.dto;

import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.FoodGroup;

/** Hai danh sách chọn mà bước 2 và bước 3 của onboarding cần. */
public final class NutritionLookupDtos {

    private NutritionLookupDtos() {
    }

    /** Một chip thành phần gây dị ứng. */
    public record AllergenOption(String id, String name) {
    }

    /**
     * Một dòng gợi ý nguyên liệu.
     *
     * <p>{@code allergenStatus} đi kèm để giao diện nói trước được rằng nguyên liệu này
     * <em>chưa được rà soát</em> thành phần gây dị ứng, thay vì để người dùng ngạc nhiên vì món
     * chứa nó bị loại khỏi thực đơn cá nhân hóa (BR03).</p>
     */
    public record IngredientOption(String id, String canonicalName, FoodGroup foodGroup,
                                   AllergenStatus allergenStatus) {
    }
}
