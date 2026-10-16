package vn.nutrimom.nutrition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.EligibilityStatus;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.RecommendationStatus;

/** Hợp đồng JSON của {@code POST /api/v1/recommendations}. snake_case như phần còn lại của API. */
public final class RecommendationDtos {

    private RecommendationDtos() {
    }

    /**
     * Yêu cầu gợi ý cho một bữa, hoặc cho phần còn lại của ngày.
     *
     * <p>Ba trường lịch sử ({@code recentRecipeIds}, {@code yesterdayMainIngredientTags},
     * {@code weekRecipeCounts}) do <strong>người gọi</strong> cung cấp, không phải service tự đi
     * tra. Lý do: kế hoạch và nhật ký thuộc nhánh 5 và 8, chưa tồn tại — và ngay cả khi có,
     * {@code PlanGenerator} ở nhánh 5 cần chấm điểm trên <em>kế hoạch đang dựng dở</em> chứ không
     * phải trên thứ đã lưu trong database. Để người gọi truyền vào là cách duy nhất phục vụ được
     * cả hai tình huống bằng một đường.</p>
     *
     * <p>{@code strictTime} và {@code strictBudget} mặc định false: thời gian và chi phí là ưu
     * tiên <strong>mềm</strong>, chỉ thành bộ lọc cứng khi người dùng tự bật (BR13).</p>
     *
     * @param seed      seed của nút "Tạo lại"; bỏ trống = thứ tự chuẩn. Cùng seed cho đúng danh
     *                  sách cũ
     * @param requestId mã tương quan để tra ngược trong nhật ký gợi ý. <strong>Không phải</strong>
     *                  idempotency key — gợi ý là thao tác đọc
     */
    public record RecommendationRequest(
            @NotEmpty(message = "meal_types không được rỗng")
            @Size(max = 4) Set<MealType> mealTypes,
            RecipeRole role,
            @Min(1) @Max(20) Integer limit,
            Long seed,
            Boolean strictTime,
            Boolean strictBudget,
            @Size(max = 50) Set<@Size(max = 64) String> recentRecipeIds,
            @Size(max = 50) Set<@Size(max = 64) String> yesterdayMainIngredientTags,
            @Size(max = 100) Map<@Size(max = 64) String, @Min(0) @Max(50) Integer> weekRecipeCounts,
            @Size(max = 64) String requestId) {

        public boolean strictTimeEnabled() {
            return Boolean.TRUE.equals(strictTime);
        }

        public boolean strictBudgetEnabled() {
            return Boolean.TRUE.equals(strictBudget);
        }
    }

    /** Một lý do đã hiển thị được: mã để máy đọc, câu để người đọc. */
    public record ReasonView(String code, String message) {
    }

    /**
     * Toàn bộ phản hồi.
     *
     * @param allergyChecked false khi hồ sơ còn ở trạng thái "chưa rõ dị ứng" — giao diện
     *                       <strong>không được gắn nhãn</strong> "đã kiểm tra theo dị ứng của bạn"
     *                       (BR01)
     * @param notes          lý do nói về cả lượt gợi ý, không gắn vào món nào
     */
    public record RecommendationResponse(int profileVersion, String rulesVersion, long seed,
                                         boolean allergyChecked, List<ReasonView> notes,
                                         List<MealRecommendation> meals) {
    }

    /**
     * Gợi ý cho một bữa.
     *
     * @param criteriaUsed     ký hiệu các tiêu chí đã tham gia xếp hạng, vd {@code ["N","P","V","C"]}.
     *                         Có mặt để giải thích vì sao thứ tự đổi khi hồ sơ đổi — rỗng nghĩa là
     *                         danh sách chưa được cá nhân hóa
     * @param nutritionStatus  độ đầy đủ của <strong>dữ liệu</strong> vi chất trên cả bữa
     * @param excluded         món đã bị loại kèm lý do. Có mặt để trả lời được "sao không thấy món
     *                         X", và để người dùng hiểu món vắng vì dữ liệu thiếu hay vì không hợp
     * @param logId            mã bản ghi trong nhật ký gợi ý, để đối chiếu khi đi tìm lỗi
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MealRecommendation(MealType mealType, RecommendationStatus status,
                                     List<ReasonView> notes, List<String> criteriaUsed,
                                     CoverageStatus nutritionStatus,
                                     List<RecommendedRecipe> items,
                                     List<ExcludedRecipe> excluded,
                                     String logId) {
    }

    /**
     * Một món được gợi ý.
     *
     * <p><strong>Cố ý không có điểm số.</strong> Điểm thuật toán giữ ở backend: đưa ra giao diện
     * thì sớm muộn nó bị đọc thành "điểm sức khỏe" của món, mà nó không phải thứ đó. Cái hiển thị
     * được là <em>thứ tự</em> và {@code reasons}. Điểm từng thành phần nằm trong
     * {@code app.recommendation_log_entries} cho việc đối chiếu.</p>
     *
     * @param reasons tối đa hai lý do, lấy từ dữ liệu thật (BR15). Luôn có ít nhất một
     */
    public record RecommendedRecipe(String recipeId, String name, RecipeRole role, int minutes,
                                    CoverageStatus nutritionStatus, List<ReasonView> reasons) {
    }

    /** Một món bị loại, kèm lý do đối chiếu được với dữ liệu. */
    public record ExcludedRecipe(String recipeId, String name, EligibilityStatus status,
                                 List<ReasonView> reasons) {
    }
}
