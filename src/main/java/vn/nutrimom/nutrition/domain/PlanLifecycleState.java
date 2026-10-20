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
     * Kế hoạch còn mục <strong>chưa ăn</strong> không hợp điều kiện bắt buộc của hồ sơ hiện tại.
     *
     * <p>Nhánh 10 đặt trạng thái này, nhưng <strong>không</strong> bằng cách so
     * {@code profile_version}: so số phiên bản sẽ bắt cả một lần sửa ngân sách hay một món vừa
     * đánh dấu yêu thích phải "cập nhật thực đơn", trong khi tài liệu nhánh nói rõ những thay đổi
     * đó chỉ ảnh hưởng <em>lần gợi ý tiếp theo</em>. Điều kiện thật là: còn ít nhất một mục ở hôm
     * nay chưa ghi hoặc ở ngày sau mà {@link vn.nutrimom.nutrition.service.EligibilityFilter} chặn
     * theo dị ứng, chế độ ăn hoặc nguyên liệu phải tránh.</p>
     *
     * <p><strong>Không chặn xem kế hoạch.</strong> Nó chỉ đánh dấu các mục cần đổi; đổi hết thì
     * quay về {@link #SAVED}. Hệ thống không tự đổi món và không tự mở khóa mục đã khóa (BR10).</p>
     *
     * <p>Cột {@code lifecycle_state} là <strong>bản sao để truy vấn</strong>: đường lưu hồ sơ và
     * đường sửa kế hoạch ghi lại nó, còn giá trị đi ra tới giao diện luôn được tính lại lúc đọc,
     * vì hồ sơ có thể vừa đổi ở một thiết bị khác mà không có gì chạy qua kế hoạch này.</p>
     */
    NEEDS_UPDATE
}
