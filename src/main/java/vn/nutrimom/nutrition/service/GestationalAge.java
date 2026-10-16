package vn.nutrimom.nutrition.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Tuổi thai tại một ngày <em>lịch của người dùng</em>, cùng cách hiển thị của nó.
 *
 * <p>Công thức, đúng theo mục 4 của {@code branch-03-profile.md}:</p>
 * <pre>
 * days = gestational_age_days_at_anchor + số ngày lịch đã trôi qua (trong múi giờ người dùng)
 * hiển thị = floor(days / 7) tuần và days mod 7 ngày
 * </pre>
 *
 * <p><strong>Đếm theo ngày lịch, không theo giờ.</strong> Mốc neo là một {@link LocalDate} và
 * "hôm nay" cũng là {@link LocalDate} trong múi giờ của người dùng, nên không có chuyện 23 giờ
 * chênh lệch làm tuần thai nhảy sớm hay muộn một ngày — đúng thứ TC04 kiểm.</p>
 *
 * <p>Record này <strong>không</strong> chạm database và không biết múi giờ: nơi gọi quy đổi sang
 * ngày lịch trước rồi mới đưa vào. Nhờ vậy ranh giới giai đoạn kiểm được bằng test thuần.</p>
 *
 * @param days         tổng số ngày thai tại {@code localDate}; không bao giờ âm
 * @param weeks        {@code days / 7} — số tuần tròn, đúng con số hiển thị cho người dùng
 * @param dayOfWeek    {@code days % 7} — số ngày lẻ, 0 đến 6
 * @param trimester    1, 2 hoặc 3; xem {@link #trimesterOf(int)} về ranh giới
 * @param localDate    ngày lịch đã dùng để tính, trong múi giờ của người dùng
 * @param dueDate      ngày dự sinh đang lưu trong hồ sơ, hoặc null nếu hồ sơ không có
 * @param daysUntilDue số ngày còn lại tới ngày dự sinh; âm nghĩa là đã qua ngày dự sinh
 */
public record GestationalAge(int days, int weeks, int dayOfWeek, int trimester,
                             LocalDate localDate, LocalDate dueDate, Long daysUntilDue) {

    /**
     * Thai kỳ đủ tháng theo quy ước sản khoa, dùng để quy đổi qua lại với ngày dự sinh.
     *
     * <p>Mượn thẳng hằng của module thai kỳ thay vì viết lại số 280: hai module cùng quy đổi ngày
     * dự sinh sang tuổi thai, lệch nhau một mốc là hai màn hình nói hai tuần thai khác nhau cho
     * cùng một hồ sơ.</p>
     */
    public static final int OBSTETRIC_DAYS =
            vn.nutrimom.pregnancy.service.PregnancyCalculationService.OBSTETRIC_DAYS;

    public static GestationalAge of(int days, LocalDate localDate, LocalDate dueDate) {
        int safeDays = Math.max(0, days);
        return new GestationalAge(safeDays, safeDays / 7, safeDays % 7, trimesterOf(safeDays),
                localDate, dueDate,
                dueDate == null ? null : ChronoUnit.DAYS.between(localDate, dueDate));
    }

    /**
     * Số ngày thai tại {@code localDate}, suy từ một mốc neo đã biết.
     *
     * @param anchorDays số ngày thai <strong>tại</strong> {@code anchorDate}
     */
    public static int daysFromAnchor(int anchorDays, LocalDate anchorDate, LocalDate localDate) {
        return (int) (anchorDays + ChronoUnit.DAYS.between(anchorDate, localDate));
    }

    /**
     * Giai đoạn thai kỳ theo <strong>tuần tròn</strong>.
     *
     * <p>Ranh giới nằm giữa hai tuần chứ không giữa hai ngày: 13 tuần 6 ngày (97 ngày) vẫn là giai
     * đoạn 1, 14 tuần 0 ngày (98 ngày) đã là giai đoạn 2 — TC04. Viết theo ngày
     * ({@code days < 98}) cho kết quả giống hệt nhưng che mất chỗ dễ lệch, nên để nguyên phép chia
     * lấy tuần rồi so tuần.</p>
     *
     * <p>Cùng quy ước với {@code PregnancyCalculationService} của module thai kỳ, cố ý: hai màn
     * hình cùng nói "tuần 14" thì phải cùng đổi giai đoạn tại một thời điểm. Đây là giai đoạn
     * <em>hiển thị</em>, khác với {@link vn.nutrimom.nutrition.domain.TrimesterGroup} — nhóm hệ số
     * của công thức EER, chỉ có hai giá trị và lấy ranh giới từ {@code nutritionRules.json}.</p>
     */
    public static int trimesterOf(int days) {
        int weeks = Math.max(0, days) / 7;
        if (weeks <= 13) return 1;
        if (weeks <= 27) return 2;
        return 3;
    }

    /**
     * Đã qua ngày dự sinh hay chưa.
     *
     * <p>Qua ngày dự sinh <strong>không</strong> đồng nghĩa đã sinh — hồ sơ vẫn hợp lệ, chỉ là
     * thông tin có thể không còn phù hợp. Xem {@code NutritionProfileService} về việc tạm dừng tự
     * tạo kế hoạch mới khi cờ này bật.</p>
     */
    public boolean dueDatePassed() {
        return daysUntilDue != null && daysUntilDue < 0;
    }
}
