package vn.nutrimom.nutrition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.GenerationStatus;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.PlanLifecycleState;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.TemplateMode;
import vn.nutrimom.nutrition.dto.RecommendationDtos.ReasonView;

/**
 * Hợp đồng JSON của ba endpoint thực đơn. snake_case như phần còn lại của API.
 *
 * <p>Ví dụ JSON trong đặc tả viết camelCase; hình dạng ở đây giữ nguyên <em>cấu trúc</em> đã công
 * bố, chỉ đổi cách serialize theo {@code _CONTRACT.md} — {@code previewId} trên giấy là
 * {@code preview_id} trên dây.</p>
 *
 * <p>Hình dạng khối {@code nutrition} lấy đúng theo khối đã công bố ở nhánh 2: một map theo mã chất,
 * mỗi chất mang {@code known_value} / {@code unit} / {@code coverage} /
 * {@code missing_ingredient_ids}.</p>
 */
public final class MealPlanDtos {

    private MealPlanDtos() {
    }

    // =========================================================================================
    // Yêu cầu
    // =========================================================================================

    /**
     * Yêu cầu tạo bản xem trước.
     *
     * @param requestId      <strong>idempotency key</strong>, nằm trong body chứ không phải header
     *                       {@code X-Request-Id} (_CONTRACT.md mục 6). Gửi lại cùng giá trị trả lại
     *                       đúng bản xem trước cũ
     * @param profileVersion phiên bản hồ sơ mà giao diện đang cầm; lệch với hiện tại thì yêu cầu bị
     *                       từ chối thay vì lập thực đơn theo dữ liệu đã cũ. Bỏ trống = không kiểm
     * @param dayCount       nhánh này <strong>chỉ nhận 1</strong>; ba và bảy ngày thuộc nhánh 9
     * @param snackCount     1–3, mặc định 1 (QĐ-02). Tỉ lệ 15% của bữa phụ chia đều cho từng bữa
     * @param seed           seed để tái lập kết quả thử nghiệm. <strong>Không phải dữ liệu sức
     *                       khỏe</strong>, cũng như {@code requestId}
     */
    public record PreviewRequest(
            @NotBlank(message = "request_id không được rỗng")
            @Size(max = 64) String requestId,
            Integer profileVersion,
            @NotNull(message = "start_date không được rỗng") LocalDate startDate,
            @Min(value = 1, message = "day_count của nhánh thực đơn một ngày chỉ nhận 1")
            @Max(value = 1, message = "day_count của nhánh thực đơn một ngày chỉ nhận 1")
            Integer dayCount,
            @NotEmpty(message = "meal_types không được rỗng")
            @Size(max = 4) Set<MealType> mealTypes,
            @Min(value = 1, message = "snack_count nằm trong 1–3")
            @Max(value = 3, message = "snack_count nằm trong 1–3") Short snackCount,
            @Valid PlanConstraints constraints,
            @Valid @Size(max = 12) List<LockedItemRequest> lockedItems,
            Long seed) {

        public short snackCountOrDefault() {
            return snackCount == null ? (short) 1 : snackCount;
        }

        public long seedOrDefault() {
            return seed == null ? 0L : seed;
        }

        public PlanConstraints constraintsOrEmpty() {
            return constraints == null
                    ? new PlanConstraints(null, null, null, null) : constraints;
        }

        public List<LockedItemRequest> lockedItemsOrEmpty() {
            return lockedItems == null ? List.of() : lockedItems;
        }
    }

    /**
     * Giới hạn người dùng tự đặt cho lượt lập thực đơn này.
     *
     * <p>Hai con số phải <strong>&gt; 0</strong>: cả hai đều nằm dưới mẫu số của một phép chia khi
     * chấm điểm, và 0 ở đó không phải "không giới hạn" mà là một phép chia cho 0.</p>
     *
     * <p>{@code strictBudget} và {@code strictTime} mặc định false — thời gian và chi phí là ưu tiên
     * <strong>mềm</strong>, chỉ thành điều kiện cứng khi người dùng tự bật (BR13).</p>
     */
    public record PlanConstraints(
            @Positive(message = "daily_budget_vnd phải lớn hơn 0") Long dailyBudgetVnd,
            @Positive(message = "max_minutes_per_meal phải lớn hơn 0") Integer maxMinutesPerMeal,
            Boolean strictBudget,
            Boolean strictTime) {

        public boolean strictBudgetEnabled() {
            return Boolean.TRUE.equals(strictBudget);
        }

        public boolean strictTimeEnabled() {
            return Boolean.TRUE.equals(strictTime);
        }
    }

    /**
     * Một món người dùng muốn giữ nguyên ở một vị trí.
     *
     * <p>Món khóa <strong>vẫn phải qua bước lọc</strong> (bất biến 2): khóa một món chứa thành phần
     * dị ứng không đưa được nó vào kế hoạch, kết quả là {@code NO_MATCH} kèm đúng lý do của bước
     * lọc.</p>
     *
     * @param servings số phần, phải lớn hơn 0 — báo lỗi <strong>tại trường</strong> (TC24)
     */
    public record LockedItemRequest(
            @NotNull(message = "meal_type không được rỗng") MealType mealType,
            @Min(value = 1, message = "sequence nằm trong 1–3")
            @Max(value = 3, message = "sequence nằm trong 1–3") Short sequence,
            @NotBlank(message = "recipe_id không được rỗng") @Size(max = 64) String recipeId,
            @NotNull(message = "servings không được rỗng")
            @DecimalMin(value = "0", inclusive = false, message = "servings phải lớn hơn 0")
            BigDecimal servings) {

        public short sequenceOrDefault() {
            return sequence == null ? (short) 1 : sequence;
        }
    }

    /**
     * Yêu cầu lưu một bản xem trước thành kế hoạch.
     *
     * <p>Không mang theo nội dung thực đơn: nội dung đã nằm trong bản xem trước ở database, và nhận
     * lại nội dung từ client là mở đúng một đường để lưu một thực đơn chưa từng qua bước lọc.</p>
     */
    public record SavePlanRequest(
            @NotBlank(message = "preview_id không được rỗng")
            @Size(max = 36) String previewId) {
    }

    // =========================================================================================
    // Phản hồi
    // =========================================================================================

    /**
     * Bản xem trước.
     *
     * @param status           {@link GenerationStatus} — kết quả <em>sinh</em>, không phải trạng
     *                         thái lưu và cũng không phải độ đầy đủ dữ liệu
     * @param nutritionCoverage thuộc tính của <strong>dữ liệu</strong>: một thực đơn
     *                         {@code READY} vẫn có thể {@code PARTIAL}
     * @param expiresAt        hết hạn sau 30 phút; lưu sau mốc này bị từ chối
     * @param notes            lý do ở mức cả kế hoạch, mỗi cái kèm câu giải thích lấy từ dữ liệu thật
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewResponse(String previewId, GenerationStatus status, int profileVersion,
                                  String rulesVersion, long seed,
                                  CoverageStatus nutritionCoverage, OffsetDateTime expiresAt,
                                  List<ReasonView> notes, List<DayView> days) {
    }

    /** Kế hoạch đã lưu, cùng hình dạng ngày và bữa với bản xem trước. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanView(String planId, GenerationStatus generationStatus,
                           PlanLifecycleState lifecycleState, int planVersion, int profileVersion,
                           String rulesVersion, LocalDate startDate, LocalDate endDate,
                           CoverageStatus nutritionCoverage, List<ReasonView> notes,
                           List<DayView> days) {
    }

    /**
     * Một ngày của kế hoạch.
     *
     * @param nutrition        tổng <strong>dự kiến</strong> của ngày, theo mã chất. Luôn do
     *                         {@code NutritionCalculator} tính — không có nơi thứ hai cộng tổng
     * @param estimatedCostVnd null = <strong>chưa quy đổi được giá</strong> cho ít nhất một món,
     *                         không phải 0 đồng
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DayView(LocalDate localDate, List<SlotView> slots,
                          Map<String, NutrientView> nutrition, List<String> methodNotes,
                          BigDecimal estimatedCostVnd, List<ReasonView> notes) {
    }

    /**
     * Một bữa.
     *
     * @param requiredRoles các vai trò mà cấu trúc đã chọn đòi hỏi — BR06: một món hoàn chỉnh
     *                      <em>hoặc</em> một tập vai trò, không trộn
     * @param filled        false khi bữa không hoàn thành được; {@code reasonCodes} lúc đó nêu rõ
     *                      vai trò nào và bộ lọc nào gây giới hạn (TC18)
     * @param reasonCodes   lý do ở mức bữa, mỗi cái gồm mã để máy đọc và câu để người đọc
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SlotView(MealType mealType, short sequence, String templateCode, TemplateMode mode,
                           List<RecipeRole> requiredRoles, boolean filled, List<ItemView> items,
                           List<ReasonView> reasonCodes) {
    }

    /**
     * Một món trong bữa.
     *
     * <p>Thời gian nấu hiển thị ở <strong>mức món</strong>, nhưng giới hạn
     * {@code max_minutes_per_meal} được kiểm ở mức <strong>toàn bữa</strong> (tổng các món) — MVP
     * ước tính bảo thủ, chưa giả định các bước nấu chạy song song.</p>
     *
     * <p><strong>Cố ý không có điểm số.</strong> Điểm thuật toán giữ ở backend, đúng như ở nhánh 4:
     * đưa ra giao diện thì sớm muộn nó bị đọc thành "điểm sức khỏe" của món.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemView(String recipeId, String name, RecipeRole role, int recipeVersion,
                           BigDecimal servings, Integer minutes, boolean locked,
                           BigDecimal estimatedCostVnd, List<ReasonView> reasonCodes) {
    }

    /**
     * Một chất trong tổng của ngày.
     *
     * <p>{@code JsonInclude.ALWAYS} là cố ý, ngược với mặc định {@code non_null} của toàn API:
     * {@code known_value} phải <strong>xuất hiện và bằng null</strong> khi chưa có dữ liệu. Bỏ
     * trường đi thì giao diện không có gì để hiển thị "chưa đủ dữ liệu cho chất này", và
     * <strong>null không bao giờ được hiểu thành 0</strong> (bất biến 1).</p>
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NutrientView(BigDecimal knownValue, String unit, CoverageStatus coverage,
                               List<String> missingIngredientIds) {
    }
}
