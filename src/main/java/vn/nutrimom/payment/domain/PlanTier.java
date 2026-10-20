package vn.nutrimom.payment.domain;

import java.time.Period;

/**
 * Gói hội viên: giá và thời hạn do server sở hữu (spec mục 17 "Không lấy giá từ Flutter").
 *
 * <p><strong>Vì sao thời hạn nằm ở đây chứ không ở {@code PlanCatalog}:</strong> module
 * {@code entitlement} đã phụ thuộc {@code payment} (catalog import chính enum này). Cho
 * {@link vn.nutrimom.payment.service.PaymentService} đọc ngược catalog sẽ tạo phụ thuộc vòng. Đặt
 * {@code term} cạnh {@code price} giữ cả hai bên đọc được cùng một nguồn mà không thêm cạnh phụ
 * thuộc nào — y như cách {@code price} đang được dùng.</p>
 *
 * <p>Thứ tự khai báo là <strong>thứ hạng gói</strong> (FREE &lt; PLAN_99K &lt; PLAN_399K), nên
 * {@code compareTo} dùng được để so cao thấp. {@code PaymentService} dựa vào đó để không hạ cấp
 * người đang giữ gói cao hơn.</p>
 */
public enum PlanTier {
    /**
     * Không phải "0 ngày" mà là "không có subscription".
     * {@code PaymentService.createSubscriptionPayment} chặn FREE trước khi tới đường cấp hạn, nên
     * {@code now.plus(FREE.getTerm())} không bao giờ chạy — đừng coi {@link Period#ZERO} ở đây là
     * một thời hạn hợp lệ.
     */
    FREE(0, Period.ZERO, "Gói Miễn phí"),
    PLAN_99K(99000, Period.ofDays(30), "Gói Cơ bản 99K"),
    PLAN_399K(399000, Period.ofMonths(10), "Gói Toàn diện 399K");

    private final int price;
    private final Period term;
    private final String displayName;

    PlanTier(int price, Period term, String displayName) {
        this.price = price;
        this.term = term;
        this.displayName = displayName;
    }

    public int getPrice() {
        return price;
    }

    /**
     * Thời hạn server <strong>thực sự</strong> cấp khi thanh toán xong, không phải lời quảng cáo
     * trên trang giá. PLAN_399K là 10 tháng <em>lịch</em>: ngày hết hạn rơi đúng ngày đó 10 tháng
     * sau, nên số ngày thực tế đổi theo thời điểm mua (303–305) — đừng quy nó về một hằng số ngày.
     */
    public Period getTerm() {
        return term;
    }

    public String getDisplayName() {
        return displayName;
    }
}
