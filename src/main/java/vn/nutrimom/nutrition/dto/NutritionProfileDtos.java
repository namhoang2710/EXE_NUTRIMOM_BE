package vn.nutrimom.nutrition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import vn.nutrimom.nutrition.domain.ActivityCategory;
import vn.nutrimom.nutrition.domain.AllergyDeclarationStatus;
import vn.nutrimom.nutrition.domain.DietPattern;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.TrimesterGroup;

/**
 * Hợp đồng JSON của hai endpoint hồ sơ ăn uống. Serialize snake_case như phần còn lại của API.
 *
 * <p>Tách khỏi {@link NutritionDtos} (danh mục món, công khai) vì đây là dữ liệu cá nhân, mọi
 * endpoint đều cần JWT và suy chủ sở hữu từ token.</p>
 */
public final class NutritionProfileDtos {

    private NutritionProfileDtos() {
    }

    /**
     * Mốc thai kỳ hiện tại, đọc từ hồ sơ thai kỳ đã có (V4) — nhánh này không tạo lại hồ sơ đó.
     *
     * <p>{@code localDate} là ngày lịch trong múi giờ của người dùng, theo đúng quy ước của module
     * lịch ({@code CalendarZone}), không phải ngày UTC. Mọi con số tuổi thai ở đây đều tính tại
     * ngày đó.</p>
     *
     * @param gestationalDays   tổng số ngày thai, nguồn của hai con số hiển thị bên dưới
     * @param gestationalWeek   số tuần tròn — con số người dùng đọc
     * @param gestationalDay    số ngày lẻ, 0 đến 6
     * @param trimester         giai đoạn hiển thị 1/2/3; 13 tuần 6 ngày vẫn là 1 (TC04). Đây là
     *                          giai đoạn <em>cho người đọc</em>, không phải
     *                          {@code energy_estimate.trimester_group} — nhóm hệ số của công thức
     *                          EER chỉ có hai giá trị và lấy ranh giới từ tập quy tắc seed. Cố ý
     *                          không lặp lại nhóm đó ở đây: hai con số tên gần giống nhau nằm cạnh
     *                          nhau là chỗ chắc chắn sẽ có người dùng nhầm
     * @param dueDatePassed     đã qua ngày dự sinh; <strong>không</strong> có nghĩa là đã sinh
     * @param autoPlanPaused    tạm dừng tự tạo kế hoạch mới vì thông tin thai kỳ có thể không còn
     *                          phù hợp — giao diện hỏi cập nhật trạng thái thay vì lặng lẽ tạo tiếp
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PregnancyMilestone(String pregnancyId, String timezone, LocalDate localDate,
                                     LocalDate estimatedDueDate, int gestationalDays,
                                     int gestationalWeek, int gestationalDay, int trimester,
                                     Long daysUntilDue,
                                     boolean dueDatePassed, boolean autoPlanPaused) {
    }

    /**
     * Thông tin ước tính năng lượng người dùng tự khai (bước 5).
     *
     * <p>Toàn bộ tùy chọn — nút "Để sau" phải dùng được mà không chặn chức năng cơ bản (TC05).
     * Null nghĩa là chưa khai, không phải 0.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnergyInputsView(Integer ageYears, BigDecimal heightCm,
                                   BigDecimal prePregnancyWeightKg, BigDecimal currentWeightKg,
                                   ActivityCategory activityCategory) {

        /** Chưa khai gì cả thì trả null cả khối thay vì một khối toàn null. */
        public boolean isEmpty() {
            return ageYears == null && heightCm == null && prePregnancyWeightKg == null
                    && currentWeightKg == null && activityCategory == null;
        }
    }

    /**
     * Kết quả ước tính năng lượng, lấy nguyên từ {@code EnergyEstimator} của nhánh 2.
     *
     * <p>{@code kcalPerDay} null nghĩa là <strong>chưa ước tính được</strong>, và {@code unmet}
     * nói thiếu ở trường nào. Giao diện lúc đó <strong>không</strong> hiển thị phần trăm hoàn
     * thành năng lượng và không mượn một mục tiêu mặc định nào (BR20, TC05).</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnergyEstimateView(BigDecimal kcalPerDay, TrimesterGroup trimesterGroup,
                                     BigDecimal prePregnancyBmi, String rulesVersion,
                                     List<UnmetView> unmet) {
    }

    /** Một điều kiện chưa thỏa của ước tính năng lượng, gắn đúng tên trường để UI chỉ chỗ cần sửa. */
    public record UnmetView(String field, String kind) {
    }

    /**
     * Hồ sơ ăn uống đầy đủ — thân của {@code GET} và phần {@code profile} của {@code PUT}.
     *
     * @param profileVersion              tăng đúng một mỗi lần lưu có thay đổi; kế hoạch lưu kèm
     *                                    số này để nhánh 10 biết nó còn hợp lệ không
     * @param allergyStatus               ba trạng thái, không phải boolean (BR01)
     * @param allergenIds                 chỉ có nghĩa khi {@code allergyStatus = KNOWN_LIST}
     * @param allergyCheckedLabelAllowed  false khi trạng thái là {@code UNKNOWN}: danh sách gợi ý
     *                                    <strong>không được gắn nhãn</strong> "đã kiểm tra theo dị
     *                                    ứng của bạn" (BR01)
     * @param needsAllergyConfirmation    true khi cần nhắc xác nhận trạng thái dị ứng trước khi
     *                                    lưu thực đơn cá nhân hóa
     * @param hardExclusionIngredientIds  điều kiện cứng, không bao giờ nới ra để có kết quả
     * @param dislikedIngredientIds       ưu tiên mềm, được phép mời người dùng nới ra
     * @param mealTypes                   loại bữa mặc định; rỗng = chưa khai, không phải "không bữa nào"
     * @param energyInputs                null khi bước 5 bị bỏ qua
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NutritionProfileView(String profileId, int profileVersion,
                                       PregnancyMilestone pregnancy,
                                       AllergyDeclarationStatus allergyStatus,
                                       List<String> allergenIds,
                                       boolean allergyCheckedLabelAllowed,
                                       boolean needsAllergyConfirmation,
                                       DietPattern dietPattern,
                                       List<String> hardExclusionIngredientIds,
                                       List<String> dislikedIngredientIds,
                                       List<String> toleranceTags,
                                       List<String> favoriteRecipeIds,
                                       List<String> blockedRecipeIds,
                                       Long dailyBudgetVnd, Integer maxMinutesPerMeal,
                                       List<MealType> mealTypes, Short snackCount,
                                       EnergyInputsView energyInputs,
                                       EnergyEstimateView energyEstimate) {
    }

    /**
     * Thân phản hồi của {@code PUT}.
     *
     * @param itemsToRevalidate {@code item_id} của các mục kế hoạch <strong>chưa ăn</strong> không
     *                          còn hợp điều kiện bắt buộc sau khi hồ sơ đổi (nhánh 10), theo thứ
     *                          tự ngày rồi buổi trong ngày. Rỗng là trường hợp thường gặp: chỉ dị
     *                          ứng, chế độ ăn và nguyên liệu phải tránh mới đưa tên vào đây — đổi
     *                          sở thích hay ngân sách chỉ ảnh hưởng lần gợi ý tiếp theo.
     *                          <p>Cố ý vẫn là danh sách mã, không phải danh sách object: hình dạng
     *                          này đã công bố từ nhánh 3, và chi tiết của từng mục (lý do, cờ
     *                          {@code conflict}, {@code lifecycle_state} của kế hoạch) nằm ở
     *                          {@code GET /api/v1/meal-plans/{id}} — một chỗ duy nhất dựng chúng,
     *                          và nó luôn tính lại theo hồ sơ tại thời điểm đọc.</p>
     */
    public record SaveNutritionProfileResponse(int profileVersion,
                                               List<String> itemsToRevalidate,
                                               NutritionProfileView profile) {
    }

    /**
     * Thân yêu cầu của {@code PUT /api/v1/me/nutrition-profile}.
     *
     * <p><strong>Trường vắng mặt hoặc null = giữ nguyên</strong>, theo đúng nếp của
     * {@code PATCH /api/v1/users/me/preferences} trong repo. Onboarding lưu theo từng bước, nên
     * ngữ nghĩa "thay thế toàn bộ" sẽ làm bước 4 xóa mất những gì bước 2 vừa lưu.</p>
     *
     * <p>Để <strong>xóa</strong> một danh sách, gửi mảng rỗng {@code []} — phân biệt được với
     * null. Đây là cách duy nhất bỏ hết dị ứng đã khai mà không cần một endpoint riêng.</p>
     *
     * <p>Không có {@code user_id} và không có {@code version} của hồ sơ thai kỳ: chủ sở hữu suy từ
     * JWT, còn {@code profile_version} do máy chủ tự tăng chứ không phải client gửi lên.</p>
     */
    public record SaveNutritionProfileRequest(
            AllergyDeclarationStatus allergyStatus,
            @Size(max = 50) List<@Size(max = 64) String> allergenIds,
            DietPattern dietPattern,
            @Size(max = 200) List<@Size(max = 64) String> hardExclusionIngredientIds,
            @Size(max = 200) List<@Size(max = 64) String> dislikedIngredientIds,
            @Size(max = 50) List<@Size(max = 64) String> toleranceTags,
            @Size(max = 500) List<@Size(max = 64) String> favoriteRecipeIds,
            @Size(max = 500) List<@Size(max = 64) String> blockedRecipeIds,
            @Positive Long dailyBudgetVnd,
            @Min(5) @Max(600) Integer maxMinutesPerMeal,
            Set<MealType> mealTypes,
            @Min(1) @Max(3) Short snackCount,
            @Min(10) @Max(70) Integer ageYears,
            @DecimalMin("0.1") @DecimalMax("300.0") BigDecimal heightCm,
            @DecimalMin("0.1") @DecimalMax("400.0") BigDecimal prePregnancyWeightKg,
            @DecimalMin("0.1") @DecimalMax("400.0") BigDecimal currentWeightKg,
            ActivityCategory activityCategory) {
    }
}
