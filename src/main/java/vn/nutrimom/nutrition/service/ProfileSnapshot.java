package vn.nutrimom.nutrition.service;

import java.util.Set;
import vn.nutrimom.nutrition.domain.AllergyDeclarationStatus;
import vn.nutrimom.nutrition.domain.DietPattern;
import vn.nutrimom.nutrition.domain.MealType;

/**
 * Hồ sơ ăn uống dạng phẳng, cho các nhánh sau dùng lại mà không phải tự đi tìm hồ sơ thai kỳ.
 *
 * <p>Khác {@code NutritionProfileDtos.NutritionProfileView}: cái kia là <strong>hợp đồng JSON</strong>
 * với giao diện, cái này là dữ liệu nội bộ giữa các service. Tách hai thứ ra vì chúng đổi theo hai
 * nhịp khác nhau — thêm một trường cho bộ gợi ý không nên buộc phải đổi hình dạng response đã công
 * bố, và ngược lại.</p>
 *
 * <p>Đi qua đây thay vì đọc thẳng repository còn giữ được một điều quan trọng: chủ sở hữu luôn suy
 * từ JWT ở đúng một chỗ ({@link NutritionProfileService}), nên không nhánh nào vô tình mở ra lối
 * đọc hồ sơ của người khác (BR19).</p>
 *
 * @param energyEstimate ước tính năng lượng cả ngày; {@code kcalPerDay} null nghĩa là chưa ước
 *                       tính được, và nhánh năng lượng của điểm N phải bị bỏ hẳn (BR20)
 */
public record ProfileSnapshot(String profileId, int profileVersion, GestationalAge age,
                              AllergyDeclarationStatus allergyStatus, Set<String> allergenIds,
                              DietPattern dietPattern, Set<String> hardExclusionIds,
                              Set<String> dislikedIngredientIds, Set<String> toleranceTags,
                              Set<String> favoriteRecipeIds, Set<String> blockedRecipeIds,
                              Long dailyBudgetVnd, Integer maxMinutesPerMeal,
                              Set<MealType> mealTypes, Short snackCount,
                              EnergyEstimate energyEstimate) {

    public ProfileSnapshot {
        allergenIds = allergenIds == null ? Set.of() : Set.copyOf(allergenIds);
        hardExclusionIds = hardExclusionIds == null ? Set.of() : Set.copyOf(hardExclusionIds);
        dislikedIngredientIds = dislikedIngredientIds == null
                ? Set.of() : Set.copyOf(dislikedIngredientIds);
        toleranceTags = toleranceTags == null ? Set.of() : Set.copyOf(toleranceTags);
        favoriteRecipeIds = favoriteRecipeIds == null ? Set.of() : Set.copyOf(favoriteRecipeIds);
        blockedRecipeIds = blockedRecipeIds == null ? Set.of() : Set.copyOf(blockedRecipeIds);
        mealTypes = mealTypes == null ? Set.of() : Set.copyOf(mealTypes);
    }

    /**
     * Danh sách gợi ý có được gắn nhãn "đã kiểm tra theo dị ứng của bạn" hay không (BR01).
     *
     * <p>Hồ sơ {@link AllergyDeclarationStatus#UNKNOWN} vẫn nhận gợi ý bình thường — không có gì
     * để lọc thì cũng không lọc sai — nhưng nhãn kia là một lời khẳng định mà hệ thống chưa có cơ
     * sở để đưa ra.</p>
     */
    public boolean allergyChecked() {
        return allergyStatus != AllergyDeclarationStatus.UNKNOWN;
    }

    /** Điều kiện cứng cho {@link EligibilityFilter}, kèm hai giới hạn chỉ bật khi người dùng yêu cầu. */
    public ProfileConstraints constraints(Integer strictMaxMinutes,
                                          java.math.BigDecimal strictBudgetVnd) {
        return new ProfileConstraints(allergenIds, allergyStatus, dietPattern, hardExclusionIds,
                blockedRecipeIds, strictMaxMinutes, strictBudgetVnd);
    }
}
