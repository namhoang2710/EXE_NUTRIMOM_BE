package vn.nutrimom.nutrition.domain;

/**
 * Độ đầy đủ dữ liệu của một tổng dinh dưỡng.
 *
 * <p>Đi kèm <strong>mọi</strong> con số mà {@code NutritionCalculator} trả ra. {@link #PARTIAL}
 * nghĩa là tổng chỉ gồm phần đã biết: có nguyên liệu chưa có số liệu cho chất đó, và phần thiếu
 * <strong>không</strong> được coi là 0 (BR08). Giao diện ghi "chưa đủ dữ liệu cho chất này", không
 * tô đầy thanh tiến trình và không kết luận khẩu phần đã đủ chất.</p>
 *
 * <p><strong>Không</strong> dùng thay cho {@code generation_status} của kế hoạch ở nhánh 5: một
 * thực đơn {@code READY} vẫn có thể mang {@code PARTIAL}, và ngược lại.</p>
 */
public enum CoverageStatus {
    COMPLETE,
    PARTIAL;

    /** COMPLETE chỉ khi mọi phần đều COMPLETE — một phần thiếu là cả tổng thiếu. */
    public static CoverageStatus of(boolean complete) {
        return complete ? COMPLETE : PARTIAL;
    }
}
