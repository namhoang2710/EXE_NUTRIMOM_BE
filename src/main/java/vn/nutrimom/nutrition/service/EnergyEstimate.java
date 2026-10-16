package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.List;
import vn.nutrimom.nutrition.domain.ActivityCategory;
import vn.nutrimom.nutrition.domain.TrimesterGroup;

/**
 * Kết quả ước tính năng lượng.
 *
 * <p>{@code kcalPerDay} null nghĩa là <strong>chưa ước tính được</strong>, và {@link #unmet()} nói
 * còn thiếu hoặc sai ở đâu. Lúc đó lớp trên <strong>không</strong> hiển thị phần trăm hoàn thành
 * năng lượng, không mượn một mục tiêu mặc định, và chấm điểm ở nhánh 4 phải bỏ hẳn nhánh năng
 * lượng thay vì coi như 0 (BR20).</p>
 *
 * <p>Con số này là <strong>ước tính có sai số</strong> của một mô hình quần thể, không phải chỉ
 * định khẩu phần cho một người cụ thể. Công thức đã gồm yếu tố thai kỳ nên không cộng thêm 340
 * hay 450 kcal. MVP chỉ ghi lượng ăn từ thực phẩm, chưa nhập thuốc và thực phẩm bổ sung, nên
 * không kết luận gì về tổng nạp vào.</p>
 *
 * @param kcalPerDay         kcal mỗi ngày, hoặc null khi chưa tính được
 * @param trimesterGroup     bộ hệ số đã dùng; null khi chưa tính được
 * @param prePregnancyBmi    BMI trước thai kỳ đã dùng để chọn {@code D}; null ở tam cá nguyệt 1
 * @param bmiAdjustmentKcal  thành phần {@code D} của mô hình; null ở tam cá nguyệt 1
 * @param rulesVersion       phiên bản tập quy tắc đã sinh ra con số này
 * @param unmet              những điều kiện chưa thỏa; rỗng khi đã tính được
 */
public record EnergyEstimate(BigDecimal kcalPerDay, TrimesterGroup trimesterGroup,
                             ActivityCategory activity, BigDecimal prePregnancyBmi,
                             BigDecimal bmiAdjustmentKcal, String rulesVersion,
                             List<Unmet> unmet) {

    public EnergyEstimate {
        unmet = unmet == null ? List.of() : List.copyOf(unmet);
    }

    /** Một điều kiện chưa thỏa, gắn vào đúng tên trường để giao diện chỉ được chỗ cần sửa. */
    public record Unmet(String field, UnmetKind kind) {
    }

    public enum UnmetKind {
        /** Người dùng chưa khai. */
        MISSING,
        /** Có khai nhưng giá trị không hợp lệ (âm, bằng 0, hoặc ngoài khoảng hợp lý của con người). */
        OUT_OF_RANGE,
        /** Hợp lệ nhưng nằm ngoài phạm vi mô hình — không ngoại suy. */
        NOT_SUPPORTED
    }

    public boolean available() {
        return kcalPerDay != null;
    }

    static EnergyEstimate unavailable(String rulesVersion, List<Unmet> unmet) {
        return new EnergyEstimate(null, null, null, null, null, rulesVersion, unmet);
    }
}
