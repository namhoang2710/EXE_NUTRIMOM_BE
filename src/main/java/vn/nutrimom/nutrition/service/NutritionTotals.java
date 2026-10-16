package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.nutrimom.nutrition.domain.CoverageStatus;

/**
 * Kết quả của {@link NutritionCalculator} — tổng dinh dưỡng kèm <strong>trạng thái dữ liệu</strong>.
 *
 * <p>Không có chỗ nào trong hệ thống trả về một con số dinh dưỡng trần: mỗi chất luôn đi cùng
 * {@link NutrientTotal#coverageStatus()} và danh sách nguyên liệu còn thiếu dữ liệu, để giao diện
 * nói được "chưa đủ dữ liệu cho chất này" thay vì tô đầy thanh tiến trình (BR08).</p>
 *
 * <p>Các con số ở đây <strong>chưa làm tròn</strong>. Làm tròn chỉ xảy ra ở lớp hiển thị; mọi phép
 * so sánh, chấm điểm và kiểm thử đều chạy trên giá trị gốc.</p>
 */
public record NutritionTotals(String rulesVersion, Map<String, NutrientTotal> byNutrient,
                              List<String> methodNotes) {

    public NutritionTotals {
        byNutrient = byNutrient == null ? Map.of() : Map.copyOf(byNutrient);
        methodNotes = methodNotes == null ? List.of() : List.copyOf(methodNotes);
    }

    /**
     * Tổng của một chất.
     *
     * @param knownValue           tổng của <strong>phần đã biết</strong>; null khi không nguyên
     *                             liệu nào có số liệu cho chất này — null không bao giờ được hiển
     *                             thị hay xử lý như 0
     * @param missingIngredientIds nguyên liệu chưa có số liệu cho chất này
     * @param coverageStatus       {@link CoverageStatus#PARTIAL} ngay khi có một nguyên liệu thiếu
     */
    public record NutrientTotal(String nutrientCode, String unit, BigDecimal knownValue,
                                List<String> missingIngredientIds, CoverageStatus coverageStatus) {

        public NutrientTotal {
            missingIngredientIds = missingIngredientIds == null
                    ? List.of() : List.copyOf(missingIngredientIds);
        }
    }

    public Optional<NutrientTotal> nutrient(String nutrientCode) {
        return Optional.ofNullable(byNutrient.get(nutrientCode));
    }

    /**
     * Độ đầy đủ của cả khẩu phần: COMPLETE chỉ khi <strong>mọi</strong> chất đều đủ dữ liệu.
     *
     * <p>Đây là thuộc tính của dữ liệu, không phải trạng thái của kế hoạch — một thực đơn
     * {@code READY} vẫn có thể PARTIAL.</p>
     */
    public CoverageStatus coverage() {
        boolean complete = byNutrient.values().stream()
                .allMatch(total -> total.coverageStatus() == CoverageStatus.COMPLETE);
        return CoverageStatus.of(complete && !byNutrient.isEmpty());
    }
}
