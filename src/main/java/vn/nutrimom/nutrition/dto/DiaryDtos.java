package vn.nutrimom.nutrition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.MealFeedbackSignal;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.dto.MealPlanDtos.NutrientView;
import vn.nutrimom.nutrition.dto.RecommendationDtos.ReasonView;

/**
 * Hợp đồng JSON của nhật ký và phản hồi sau bữa (nhánh 8). snake_case như phần còn lại của API.
 *
 * <p><strong>Hai tổng, không bao giờ một.</strong> {@link DiaryView} trả {@code planned_nutrition}
 * và {@code logged_nutrition} như hai khối tách rời, mỗi chất kèm {@code coverage} riêng. Cố ý
 * không có trường nào là tổng của hai khối đó: dự kiến và đã ghi là hai tập dữ liệu độc lập (bất
 * biến 3), và cộng chúng lại sẽ đếm hai lần đúng những bữa người dùng đã ăn theo kế hoạch.</p>
 *
 * <p>Khối dinh dưỡng dùng lại {@link NutrientView} của nhánh 5 — cùng một hình dạng đã công bố, nên
 * giao diện đọc tổng kế hoạch và tổng nhật ký bằng đúng một đoạn mã, và {@code known_value} null
 * vẫn xuất hiện ở cả hai (bất biến 1).</p>
 */
public final class DiaryDtos {

    private DiaryDtos() {
    }

    // =========================================================================================
    // Yêu cầu
    // =========================================================================================

    /**
     * Ghi một món đã ăn (UC05).
     *
     * <p><strong>BR16: không trường nào ở đây có giá trị mặc định phía máy chủ.</strong> Giao diện
     * mở hộp xác nhận ngắn với ngày, bữa và số phần điền sẵn từ kế hoạch, nhưng thứ tới được đây
     * phải là thứ người dùng đã bấm xác nhận. Vì vậy {@code local_date}, {@code meal_type} và số
     * phần đều bắt buộc — máy chủ không tự đoán hôm nay là ngày nào, và nhất là không bao giờ tự
     * ghi tất cả các bữa vào cuối ngày.</p>
     *
     * <p>Hai dạng ghi, chọn đúng một:</p>
     * <ul>
     *   <li><strong>Món trong danh mục</strong> — {@code recipe_id} hoặc {@code source_plan_item_id}
     *       (hoặc cả hai), kèm {@code servings}. "Ăn một nửa" chính là gửi {@code 0.5} ở đây, và
     *       mục kế hoạch vẫn giữ nguyên số phần của nó (TC15).</li>
     *   <li><strong>Món ngoài bộ dữ liệu</strong> — {@code custom_text} kèm {@code amount_text} tùy
     *       chọn. Hệ thống lưu tên và lượng rồi dừng: <strong>không suy vi chất từ tên món</strong>.
     *       Gửi kèm {@code servings} ở dạng này bị từ chối, vì số phần của một món không có công
     *       thức thì không nhân với cái gì được.</li>
     * </ul>
     *
     * @param recipeId           món trong danh mục. Khác món của {@code source_plan_item_id} là hợp
     *                           lệ và có chủ đích: đó là lối "nguyên liệu đổi đáng kể → chọn biến
     *                           thể có sẵn", lúc đó số liệu lấy theo biến thể thật sự đã ăn
     * @param sourcePlanItemId   mục kế hoạch đã gợi ra lần ghi này. <strong>Tùy chọn</strong> —
     *                           nhật ký ghi được món ngoài thực đơn
     * @param estimateIncomplete người dùng tự đánh dấu "nguyên liệu đã đổi đáng kể, đây chỉ là ước
     *                           tính". Tổng của ngày mang PARTIAL và nói ra điều đó
     * @param requestId          idempotency key trong body (_CONTRACT.md mục 6). Gửi lại cùng giá
     *                           trị trả lại đúng bản ghi cũ, không tạo bữa thứ hai (TC16)
     */
    public record CreateDiaryEntryRequest(
            @NotBlank(message = "request_id không được rỗng")
            @Size(max = 64, message = "request_id tối đa 64 ký tự") String requestId,
            @NotNull(message = "local_date không được rỗng") LocalDate localDate,
            @NotNull(message = "meal_type không được rỗng") MealType mealType,
            @Size(max = 64, message = "recipe_id tối đa 64 ký tự") String recipeId,
            @Size(max = 36, message = "source_plan_item_id tối đa 36 ký tự") String sourcePlanItemId,
            @DecimalMin(value = "0", inclusive = false, message = "servings phải lớn hơn 0")
            BigDecimal servings,
            @Size(max = 255, message = "custom_text tối đa 255 ký tự") String customText,
            @Size(max = 64, message = "amount_text tối đa 64 ký tự") String amountText,
            Boolean estimateIncomplete) {

        public boolean estimateIncompleteEnabled() {
            return Boolean.TRUE.equals(estimateIncomplete);
        }

        /** Dòng trỏ tới danh mục: có món hoặc có mục kế hoạch để lấy món ra. */
        public boolean referencesCatalog() {
            return (recipeId != null && !recipeId.isBlank())
                    || (sourcePlanItemId != null && !sourcePlanItemId.isBlank());
        }

        public boolean hasCustomText() {
            return customText != null && !customText.isBlank();
        }
    }

    /**
     * Sửa một dòng đã ghi.
     *
     * <p>Trường nào bỏ trống thì <strong>giữ nguyên</strong>. Không có idempotency key: sửa là thao
     * tác trên một bản ghi đã có địa chỉ, gửi lại cùng nội dung cho ra cùng kết quả.</p>
     *
     * <p>Đổi {@code recipe_id} là <em>chụp lại snapshot theo công thức mới</em>, đúng tình huống
     * "ghi nhầm món". Nó <strong>không</strong> viết lại mục kế hoạch nào — sửa nhật ký và sửa kế
     * hoạch là hai việc ở hai bảng (bất biến 3).</p>
     */
    public record PatchDiaryEntryRequest(
            LocalDate localDate,
            MealType mealType,
            @Size(max = 64, message = "recipe_id tối đa 64 ký tự") String recipeId,
            @DecimalMin(value = "0", inclusive = false, message = "servings phải lớn hơn 0")
            BigDecimal servings,
            @Size(max = 255, message = "custom_text tối đa 255 ký tự") String customText,
            @Size(max = 64, message = "amount_text tối đa 64 ký tự") String amountText,
            Boolean estimateIncomplete) {

        public boolean changesNothing() {
            return localDate == null && mealType == null && recipeId == null && servings == null
                    && customText == null && amountText == null && estimateIncomplete == null;
        }
    }

    /**
     * Một lần chạm sau bữa: hợp khẩu vị, không thích, hoặc khó ăn lúc này.
     *
     * <p>{@code type} là <strong>cảm nhận</strong>, không phải khai báo an toàn. Không giá trị nào
     * ở đây biến thành dị ứng, và "khó ăn lúc này" tự hết sau bảy ngày (QĐ-03). Muốn chặn hẳn một
     * món thì đó là {@code BLOCKED} ở hồ sơ ăn uống của nhánh 3 — một lựa chọn chủ động, không hạn.</p>
     */
    public record MealFeedbackRequest(
            @NotBlank(message = "recipe_id không được rỗng")
            @Size(max = 64, message = "recipe_id tối đa 64 ký tự") String recipeId,
            @NotNull(message = "type không được rỗng") MealFeedbackSignal type) {
    }

    // =========================================================================================
    // Phản hồi
    // =========================================================================================

    /**
     * Nhật ký của một ngày.
     *
     * @param plannedNutrition tổng <strong>dự kiến</strong>, tổng hợp từ các mục kế hoạch của ngày.
     *                         Rỗng khi ngày đó không có kế hoạch nào — đó không phải lỗi, nhật ký
     *                         sống độc lập với kế hoạch
     * @param loggedNutrition  tổng của <strong>những món đã ghi</strong>. Không bao giờ cộng với
     *                         khối trên (bất biến 3), và không bao giờ được gắn nhãn "tổng lượng ăn
     *                         cả ngày" — xem {@code DIARY_LOGGED_TOTAL_ONLY} trong {@code notes}
     * @param loggedCoverage   PARTIAL ngay khi có một món ngoài bộ dữ liệu hoặc một dòng được đánh
     *                         dấu ước tính chưa đầy đủ, kể cả khi mọi chất đều có số
     * @param remaining        phần còn lại của ngày, cho khu vực gợi ý sau khi đã ăn (UC08)
     * @param methodNotes      ghi chú phương pháp của bộ tính (hệ số giữ lại, giả định ước tính)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiaryView(LocalDate localDate,
                            List<DiaryEntryView> entries,
                            Map<String, NutrientView> plannedNutrition,
                            CoverageStatus plannedCoverage,
                            Map<String, NutrientView> loggedNutrition,
                            CoverageStatus loggedCoverage,
                            RemainingView remaining,
                            List<String> methodNotes,
                            List<ReasonView> notes) {
    }

    /**
     * Một dòng nhật ký.
     *
     * @param recipeId      null = món ngoài bộ dữ liệu; lúc đó {@code custom_text} mang tên người
     *                      dùng gõ và dòng này không có số liệu dinh dưỡng nào
     * @param recipeVersion phiên bản công thức <strong>lúc ghi</strong>. Seed đổi phiên bản về sau
     *                      thì con số này và cả số liệu của dòng vẫn giữ nguyên (TC21)
     * @param servings      số phần thực tế đã xác nhận; null ở món ngoài bộ dữ liệu
     * @param amountText    lượng đã ăn bằng chữ ở món ngoài bộ dữ liệu ("một bát")
     * @param nutrition     tổng của riêng dòng này; null ở món ngoài bộ dữ liệu — null nghĩa là
     *                      <strong>không có dữ liệu</strong>, không phải không chứa chất nào
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiaryEntryView(String entryId, LocalDate localDate, MealType mealType,
                                 String recipeId, String name, Integer recipeVersion,
                                 BigDecimal servings, String customText, String amountText,
                                 boolean estimateIncomplete, String sourcePlanItemId,
                                 Map<String, NutrientView> nutrition, CoverageStatus coverage,
                                 OffsetDateTime createdAt, List<ReasonView> notes) {
    }

    /**
     * Phần còn lại của ngày — dữ liệu cho khu vực gợi ý sau khi đã ăn (UC08).
     *
     * <p>Chỉ mang con số cho những chất <strong>có đủ dữ liệu</strong>: một chất mà tổng đã ghi còn
     * PARTIAL thì "phần còn lại" của nó sẽ lớn hơn thực tế, và một con số lớn hơn thực tế ở đây mời
     * người ta ăn bù thứ họ đã ăn rồi. Chất như vậy vẫn xuất hiện với {@code known_value} null và
     * {@code coverage} PARTIAL, để giao diện nói "chưa đủ dữ liệu" thay vì im lặng bỏ qua.</p>
     *
     * <p>Khối này <strong>không</strong> là một đơn thuốc và không bao giờ sinh ra đề xuất tăng liều
     * bổ sung: mức tham chiếu là mức cả ngày của một nhóm tuổi, không phải mức phân bổ cho một
     * người cụ thể. {@code notes} luôn nói lại điều đó.</p>
     *
     * @param energyTargetKcal mục tiêu năng lượng cả ngày; null khi hồ sơ chưa đủ thông tin để ước
     *                         tính — lúc đó không mượn một con số 2.000 kcal nào
     * @param byNutrient       phần còn lại theo mã chất, cùng hình dạng với hai khối tổng ở trên
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RemainingView(BigDecimal energyTargetKcal,
                                Map<String, NutrientView> byNutrient,
                                List<ReasonView> notes) {
    }

    /**
     * Một lần phản hồi đã ghi.
     *
     * @param expiresAt null = không hết hạn. Chỉ {@code HARD_NOW} có hạn, và hạn đó là bảy ngày
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MealFeedbackView(String feedbackId, String recipeId, MealFeedbackSignal type,
                                   OffsetDateTime createdAt, OffsetDateTime expiresAt,
                                   List<ReasonView> notes) {
    }
}
