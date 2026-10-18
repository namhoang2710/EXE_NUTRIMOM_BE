package vn.nutrimom.nutrition.domain;

import java.time.Duration;
import java.util.Optional;

/**
 * Cảm nhận của người dùng sau một bữa: vừa là <strong>giá trị cột</strong> {@code type} của
 * {@code app.meal_feedbacks}, vừa là <strong>tín hiệu chấm điểm</strong> P ở nhánh 4.
 *
 * <p>Một enum chứ không phải hai. Tách thành một enum "loại lưu trong database" và một enum "tín
 * hiệu chấm điểm" sẽ cho ra hai danh sách cùng nghĩa khác tên, và mức cộng trừ sẽ sớm được chép
 * sang chỗ thứ hai rồi lệch nhau ở lần chỉnh đầu tiên. Vì enum này mang cả hai vai, nó nằm ở
 * {@code domain} cạnh {@link RecipePreferenceType} — giống {@link RecommendationReason} vốn cũng
 * mang sẵn mẫu câu hiển thị.</p>
 *
 * <h2>Hạn sử dụng (QĐ-03)</h2>
 *
 * <p>Chỉ cái <strong>tạm thời</strong> mới hết hạn: {@link #HARD_NOW} là một trạng thái của thai kỳ
 * nên nó tự hết sau bảy ngày, còn "ngon" và "không thích" là ý kiến, chúng sống tới khi người dùng
 * đổi ý. Lựa chọn chủ động vĩnh viễn ({@code FAVORITE}, {@code BLOCKED}) thì không nằm ở đây mà ở
 * {@code app.user_recipe_preferences} của nhánh 3.</p>
 *
 * <p><strong>Không dòng nào ở đây được biến thành dị ứng.</strong> Dị ứng do người dùng khai trong
 * hồ sơ và là điều kiện an toàn ở bước lọc; một lần bấm "không thích" chỉ hạ điểm khẩu vị.</p>
 *
 * <p>Lớp chấm điểm nhận vào đây <strong>phản hồi còn hiệu lực đã được lọc sẵn</strong>: nó không
 * biết ngày tháng và không nên biết. Chỉ áp <strong>một</strong> phản hồi gần nhất cho mỗi món;
 * các sở thích khác — yêu thích, nguyên liệu không thích — vẫn cộng trừ riêng.</p>
 */
public enum MealFeedbackSignal {

    /** Ngon, ăn được. */
    TASTY(10, null),

    /** Không thích. */
    DISLIKE(-25, null),

    /** Khó ăn lúc này — trạng thái <em>tạm thời</em> của thai kỳ, không phải không thích vĩnh viễn. */
    HARD_NOW(-20, Duration.ofDays(7));

    private final int delta;
    private final Duration validity;

    MealFeedbackSignal(int delta, Duration validity) {
        this.delta = delta;
        this.validity = validity;
    }

    /** Mức cộng trừ vào điểm P. */
    public int delta() {
        return delta;
    }

    /**
     * Phản hồi sống được bao lâu; rỗng nghĩa là <strong>không hết hạn</strong>.
     *
     * <p>Rỗng ở đây không phải "chưa biết": nó là câu trả lời dứt khoát rằng loại này không có hạn,
     * và {@code ck_meal_feedbacks_expiry} trong V64 ép đúng điều đó ở mức database.</p>
     */
    public Optional<Duration> validity() {
        return Optional.ofNullable(validity);
    }
}
