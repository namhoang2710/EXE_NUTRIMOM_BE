package vn.nutrimom.nutrition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PlanConstraints;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PlanView;
import vn.nutrimom.nutrition.dto.RecommendationDtos.ReasonView;

/**
 * Hợp đồng JSON của bốn endpoint sửa thực đơn (nhánh 6). snake_case như phần còn lại của API.
 *
 * <p>Ba endpoint ghi đều đòi <strong>hai</strong> con số phiên bản. {@code expected_plan_version} là
 * optimistic lock giữa hai thiết bị; {@code expected_profile_version} bắt buộc vì một thao tác sửa
 * được quyết định dựa trên hồ sơ mà giao diện đang hiển thị — hồ sơ đã đổi thì danh sách lựa chọn
 * người dùng vừa nhìn thấy đã sai, kể cả khi kế hoạch chưa ai đụng tới.</p>
 */
public final class MealPlanEditDtos {

    private MealPlanEditDtos() {
    }

    // =========================================================================================
    // Yêu cầu
    // =========================================================================================

    /**
     * Đổi món, đổi khẩu phần, hoặc khóa / bỏ khóa một mục.
     *
     * <p>Ba việc, một endpoint, vì cả ba đều là "sửa đúng một mục" và đều phải đi qua cùng một
     * trình tự: kiểm chủ sở hữu và phiên bản → lọc món mới → áp lên bản tạm → tính lại và kiểm toàn
     * kế hoạch → ghi trong một transaction. Tách thành ba endpoint sẽ có ba bản sao của trình tự
     * đó, và bản nào quên một bước thì bước đó biến mất trong im lặng.</p>
     *
     * <p>Trường nào bỏ trống thì <strong>giữ nguyên</strong> giá trị cũ; phải có ít nhất một thay
     * đổi, vì một yêu cầu rỗng mà vẫn tăng {@code plan_version} sẽ làm thiết bị kia nhận 409 cho
     * một thao tác chưa từng xảy ra.</p>
     *
     * @param recipeVersion phiên bản công thức giao diện đang hiển thị; lệch → {@code
     *                      VERSION_CONFLICT}, vì món người dùng bấm không còn là món họ đã đọc
     * @param servings      số phần. Phải &gt; 0 và nằm trong miền khẩu phần của cấu trúc bữa (BR07)
     * @param grams         thay cho {@code servings} khi người dùng nhập theo gram; quy đổi bằng
     *                      {@code serving_gram} của chính món đó, và món chưa cân được thì không
     *                      nhận đường này thay vì đoán một khối lượng
     */
    public record PatchItemRequest(
            @NotNull(message = "expected_plan_version không được rỗng")
            @Min(value = 1, message = "expected_plan_version phải từ 1") Integer expectedPlanVersion,
            @NotNull(message = "expected_profile_version không được rỗng")
            @Min(value = 0, message = "expected_profile_version không âm")
            Integer expectedProfileVersion,
            @Size(max = 64) String recipeId,
            Integer recipeVersion,
            @DecimalMin(value = "0", inclusive = false, message = "servings phải lớn hơn 0")
            BigDecimal servings,
            @DecimalMin(value = "0", inclusive = false, message = "grams phải lớn hơn 0")
            BigDecimal grams,
            Boolean locked) {

        public boolean changesNothing() {
            return recipeId == null && servings == null && grams == null && locked == null;
        }

        public boolean hasBothServingForms() {
            return servings != null && grams != null;
        }
    }

    /**
     * Tạo lại các mục <strong>chưa khóa</strong> của một kế hoạch.
     *
     * <p>Không nhận danh sách bữa: tạo lại giữ nguyên cấu trúc ngày đã lưu. Muốn đổi tập bữa thì
     * đó là một thực đơn khác, và đường đi của nó là {@code POST /meal-plans/preview}.</p>
     *
     * @param constraints ngân sách và thời gian cho riêng lượt này; bỏ trống thì lấy từ hồ sơ ăn
     *                    uống. {@code strict_*} vẫn mặc định tắt (BR13)
     */
    public record RegenerateRequest(
            @NotNull(message = "expected_plan_version không được rỗng")
            @Min(value = 1, message = "expected_plan_version phải từ 1") Integer expectedPlanVersion,
            @NotNull(message = "expected_profile_version không được rỗng")
            @Min(value = 0, message = "expected_profile_version không âm")
            Integer expectedProfileVersion,
            @Valid PlanConstraints constraints,
            Long seed) {

        public long seedOrDefault() {
            return seed == null ? 0L : seed;
        }
    }

    /** Khôi phục kế hoạch về ngay trước thao tác sửa gần nhất. */
    public record UndoRequest(
            @NotNull(message = "expected_plan_version không được rỗng")
            @Min(value = 1, message = "expected_plan_version phải từ 1") Integer expectedPlanVersion,
            @NotNull(message = "expected_profile_version không được rỗng")
            @Min(value = 0, message = "expected_profile_version không âm")
            Integer expectedProfileVersion) {
    }

    // =========================================================================================
    // Phản hồi
    // =========================================================================================

    /**
     * Kết quả của một thao tác sửa: <strong>cả kế hoạch</strong>, không phải riêng mục vừa đổi.
     *
     * <p>BR12 — đổi một món tính lại tổng của ngày. Trả về riêng mục vừa sửa sẽ buộc giao diện tự
     * cộng lại tổng, tức là tạo ra nguồn sự thật thứ hai cho con số dinh dưỡng (bất biến 4). Nên
     * một yêu cầu, một transaction, một bức tranh đầy đủ đã tính lại.</p>
     *
     * @param changed             false khi kế hoạch <strong>không đổi</strong> — tạo lại bất thành
     *                            vì các mục khóa chặn hết lối, chẳng hạn. Lúc đó {@code plan} là
     *                            bản cũ nguyên vẹn và {@code notes} nói vì sao
     * @param shoppingListVersion phiên bản danh sách mua sắm sau thao tác này. Danh sách mua sắm là
     *                            hàm thuần của các mục kế hoạch, nên nó đổi đúng khi và chỉ khi
     *                            {@code plan_version} đổi — nhánh 7 dựng danh sách trên chính con
     *                            số này thay vì đếm một phiên bản thứ hai có thể lệch
     * @param undoAvailable       còn một bước lùi hay không; giao diện bật nút Hoàn tác theo cờ này
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanEditView(PlanView plan, boolean changed, int shoppingListVersion,
                               boolean undoAvailable, List<ReasonView> notes) {
    }

    /**
     * Các lựa chọn thay thế cho một mục — <strong>cùng bữa, cùng vai trò</strong>.
     *
     * <p>Danh sách này chỉ gồm món đã qua bước lọc theo hồ sơ <em>hiện tại</em>: kết quả lọc lúc
     * lập thực đơn không được tin lại, vì hồ sơ có thể đã đổi từ đó (bất biến 2 và 5). Rỗng là một
     * kết quả nghiệp vụ hợp lệ, trả HTTP 200 kèm lý do nêu rõ bộ lọc nào đã chặn.</p>
     *
     * @param servingOptions tập khẩu phần của cấu trúc bữa, để giao diện dựng đúng bộ chọn
     *                       0,5 · 1 · 1,5 · 2 thay vì tự bịa
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlternativesView(String itemId, MealType mealType, short sequence,
                                   RecipeRole role, String currentRecipeId,
                                   BigDecimal currentServings, List<BigDecimal> servingOptions,
                                   List<AlternativeView> alternatives, List<ReasonView> notes) {
    }

    /**
     * Một lựa chọn thay thế, kèm <strong>tác động</strong> của việc chọn nó.
     *
     * <p>Chênh lệch tính ở <em>đúng số phần hiện tại</em> của mục: "món này đắt hơn 8.000đ" chỉ có
     * nghĩa khi hai bên cùng khẩu phần.</p>
     *
     * @param estimatedCostVnd chi phí của món này ở số phần hiện tại; null = <strong>chưa quy đổi
     *                         được giá</strong>, không phải 0 đồng
     * @param deltaCostVnd     chênh lệch so với món đang nằm trong bữa; null khi <strong>một trong
     *                         hai bên</strong> chưa có giá — một phép trừ với dữ liệu thiếu không
     *                         phải là "không chênh lệch"
     * @param deltaMinutes     chênh lệch thời gian nấu, phút
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlternativeView(String recipeId, String name, int recipeVersion,
                                  Integer minutes, BigDecimal servingGram,
                                  BigDecimal estimatedCostVnd, BigDecimal deltaCostVnd,
                                  Integer deltaMinutes) {
    }
}
