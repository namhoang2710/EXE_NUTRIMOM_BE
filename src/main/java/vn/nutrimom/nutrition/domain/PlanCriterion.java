package vn.nutrimom.nutrition.domain;

import java.math.BigDecimal;

/**
 * Năm thành phần của hàm mục tiêu <strong>cấp ngày</strong>, kèm trọng số.
 *
 * <pre>
 * J = 0,40·Nutrition + 0,25·Preference + 0,15·Variety + 0,10·Cost + 0,10·Time
 * </pre>
 *
 * <p>Khác {@link RankingCriterion} ở hai điểm, và đây không phải trùng lặp:</p>
 * <ul>
 *   <li>{@link RankingCriterion} là <strong>điểm của một món</strong> trong một bữa, càng
 *       <em>cao</em> càng hợp. {@link PlanCriterion} là <strong>phạt của cả một ngày</strong>, càng
 *       <em>thấp</em> càng tốt — dinh dưỡng chỉ đánh giá được ở cấp ngày, vì một bữa thiếu sắt mà
 *       bữa khác bù lại thì cả ngày vẫn ổn.</li>
 *   <li>Trọng số khác nhau: ở cấp ngày dinh dưỡng nặng hơn (0,40 so với 0,35) còn khẩu vị nhẹ hơn
 *       (0,25 so với 0,30).</li>
 * </ul>
 *
 * <p>Mỗi thành phần chặn vào <strong>0–1</strong> trước khi cộng. Thành phần không có dữ liệu thì
 * <strong>bỏ cả trọng số</strong> rồi chuẩn hóa trên phần còn lại — không bao giờ coi "chưa biết"
 * là "đạt", và cũng không coi là "kém".</p>
 *
 * <p>Đây là <strong>cấu hình thử nghiệm sản phẩm, không phải công thức y khoa</strong>.</p>
 */
public enum PlanCriterion {

    /** Năng lượng và vi chất của cả ngày so với mức tham chiếu. */
    NUTRITION("N", "0.40"),

    /** Khẩu vị — trung bình điểm P của các món trong ngày. */
    PREFERENCE("P", "0.25"),

    /** Đa dạng — trung bình điểm V, cập nhật lại sau <strong>từng</strong> lựa chọn. */
    VARIETY("V", "0.15"),

    /** Chi phí ước tính của ngày so với ngân sách ngày. */
    COST("B", "0.10"),

    /** Thời gian nấu của <strong>toàn bữa</strong> so với giới hạn mỗi bữa. */
    TIME("T", "0.10");

    private final String symbol;
    private final BigDecimal weight;

    PlanCriterion(String symbol, String weight) {
        this.symbol = symbol;
        this.weight = new BigDecimal(weight);
    }

    public String symbol() {
        return symbol;
    }

    public BigDecimal weight() {
        return weight;
    }
}
