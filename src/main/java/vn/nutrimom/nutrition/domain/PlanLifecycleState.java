package vn.nutrimom.nutrition.domain;

/**
 * Trạng thái <strong>lưu trữ</strong> của một kế hoạch đã lưu (cột {@code lifecycle_state}).
 *
 * <p>Tách hẳn khỏi {@link GenerationStatus}: cái kia nói lượt tìm kiếm đã ra kết quả thế nào, cái
 * này nói bản ghi đang ở đâu trong vòng đời. Một kế hoạch {@link GenerationStatus#READY_WITH_NOTES}
 * vẫn {@link #SAVED} bình thường.</p>
 *
 * <p>Bản xem trước <strong>chưa phải</strong> kế hoạch đã lưu, nên nó không mang trạng thái này —
 * nó sống ở {@code app.meal_plan_previews} với {@code expires_at} của riêng nó.</p>
 */
public enum PlanLifecycleState {

    /** Người dùng đã lưu và kế hoạch vẫn khớp hồ sơ hiện tại. */
    SAVED,

    /**
     * Hồ sơ ăn uống hoặc tập quy tắc đã đổi sau khi kế hoạch được lưu.
     *
     * <p>Nhánh 10 đặt trạng thái này bằng cách so {@code profile_version} và {@code rules_version}
     * của kế hoạch với giá trị hiện tại. Nhánh 5 không bao giờ ghi giá trị này — nó chỉ đọc được
     * nghĩa của nó.</p>
     */
    NEEDS_UPDATE
}
