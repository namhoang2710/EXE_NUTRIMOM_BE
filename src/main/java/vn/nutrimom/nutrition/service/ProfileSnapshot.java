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
 * @param userId         chủ hồ sơ. Khác {@code profileId} (vốn là mã thai kỳ) và cần thiết vì vài
 *                       thứ dùng khi chấm điểm không thuộc về hồ sơ ăn uống mà thuộc về
 *                       <em>người dùng</em> — phản hồi sau bữa ở {@code app.meal_feedbacks} là một
 *                       (QĐ-03). Thiếu trường này thì nơi gọi phải truyền userId song song với
 *                       snapshot, và hai thứ đi cạnh nhau sớm muộn sẽ lệch nhau
 * @param energyEstimate ước tính năng lượng cả ngày; {@code kcalPerDay} null nghĩa là chưa ước
 *                       tính được, và nhánh năng lượng của điểm N phải bị bỏ hẳn (BR20)
 */
public record ProfileSnapshot(String userId, String profileId, int profileVersion, GestationalAge age,
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

    /**
     * Chỉ những điều kiện <strong>bắt buộc</strong>: dị ứng, chế độ ăn, nguyên liệu phải tránh.
     *
     * <p>Dùng khi hồ sơ đổi và nhánh 10 phải quyết định một mục kế hoạch <em>đã lưu</em> có còn
     * dùng được không ({@link PlanRevalidationService}). Khác {@link #constraints} ở ba chỗ, và cả
     * ba đều theo mục 1 của tài liệu nhánh 10 — thay đổi <em>sở thích</em> chỉ ảnh hưởng lần gợi ý
     * tiếp theo, không bắt người dùng xem lại thực đơn đang có:</p>
     *
     * <ul>
     *   <li>món đã chặn để trống: "không gợi ý món này nữa" là một sở thích, không phải một giới
     *       hạn an toàn — nó giữ món khỏi các lượt gợi ý sau, chứ không biến một bữa đã lập thành
     *       một bữa phải đổi;</li>
     *   <li>không có giới hạn thời gian cứng;</li>
     *   <li>không có giới hạn ngân sách cứng — ngân sách thuộc mức "sở thích hoặc ngân sách", và
     *       nút "Áp dụng cho thực đơn hiện tại" mới là chỗ nó được áp vào kế hoạch đang có.</li>
     * </ul>
     */
    public ProfileConstraints mandatoryConstraints() {
        return new ProfileConstraints(allergenIds, allergyStatus, dietPattern, hardExclusionIds,
                Set.of(), null, null);
    }
}
