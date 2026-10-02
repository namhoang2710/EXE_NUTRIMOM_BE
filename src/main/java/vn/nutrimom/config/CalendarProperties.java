package vn.nutrimom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình module lịch và nhắc nhở dưới prefix {@code app.calendar}.
 *
 * <pre>
 * app:
 *   calendar:
 *     reminder-job-enabled: true     # NUTRIMOM_CALENDAR_JOB_ENABLED
 *     reminder-job-interval: PT5M    # NUTRIMOM_CALENDAR_JOB_INTERVAL (ISO-8601 duration)
 *     max-range-days: 366
 *     max-remind-minutes-before: 10080
 *     default-timezone: Asia/Ho_Chi_Minh
 * </pre>
 *
 * <p>{@code reminderJobEnabled=false} tắt hẳn scheduler (xem {@link CalendarSchedulingConfig}) —
 * profile test đặt false để không có thread nền chạy song song với integration test.</p>
 */
@ConfigurationProperties(prefix = "app.calendar")
public class CalendarProperties {

    private boolean reminderJobEnabled = true;

    /** Khoảng cách giữa hai lượt quét, dạng ISO-8601. Đọc lại ở {@code @Scheduled} qua placeholder. */
    private String reminderJobInterval = "PT5M";

    /** Trần độ rộng cửa sổ truy vấn lịch, chặn một request kéo cả lịch sử tài khoản. */
    private int maxRangeDays = 366;

    /** Phải khớp {@code ck_calendar_reminders_remind_offset} của V29 (7 ngày). */
    private int maxRemindMinutesBefore = 10080;

    /** Dùng khi cả request lẫn tuỳ chọn người dùng đều không nói múi giờ. */
    private String defaultTimezone = "Asia/Ho_Chi_Minh";

    /** Cửa sổ nhìn tới trước cho hai block lịch của dashboard mẹ bầu (spec mục 05). */
    private int dashboardLookaheadDays = 90;

    private int dashboardReminderLimit = 5;

    /**
     * Quá hạn bao lâu thì thôi không bắn thông báo nữa (chỉ đánh dấu đã xử lý).
     *
     * <p>Không có mốc này thì một nhắc nhở bị bỏ quên lúc hệ thống ngừng chạy sẽ bắn thông báo
     * "sắp tới giờ" cho một buổi hẹn của mấy tuần trước ngay khi job sống lại.</p>
     */
    private int staleAfterHours = 24;

    /** Số mốc giờ tối đa trong một ngày của một nhắc nhở lặp (vd thuốc sáng/trưa/tối). */
    private int maxTimesPerDay = 6;

    /**
     * Trần số mốc trả về cho một lần gọi {@code /calendar/events}.
     *
     * <p>Một nhắc nhở hằng ngày khai triển ra đúng bằng số ngày của cửa sổ, nên vài chuỗi lặp cộng
     * một cửa sổ rộng là đủ dựng ra hàng nghìn mốc. Thà báo lỗi bảo thu hẹp khoảng còn hơn âm thầm
     * cắt bớt — lịch thiếu mốc nguy hiểm hơn lịch báo lỗi.</p>
     */
    private int maxEventsPerResponse = 2000;

    public boolean isReminderJobEnabled() { return reminderJobEnabled; }
    public void setReminderJobEnabled(boolean value) { reminderJobEnabled = value; }
    public String getReminderJobInterval() { return reminderJobInterval; }
    public void setReminderJobInterval(String value) { reminderJobInterval = value; }
    public int getMaxRangeDays() { return maxRangeDays; }
    public void setMaxRangeDays(int value) { maxRangeDays = value; }
    public int getMaxRemindMinutesBefore() { return maxRemindMinutesBefore; }
    public void setMaxRemindMinutesBefore(int value) { maxRemindMinutesBefore = value; }
    public String getDefaultTimezone() { return defaultTimezone; }
    public void setDefaultTimezone(String value) { defaultTimezone = value; }
    public int getDashboardLookaheadDays() { return dashboardLookaheadDays; }
    public void setDashboardLookaheadDays(int value) { dashboardLookaheadDays = value; }
    public int getDashboardReminderLimit() { return dashboardReminderLimit; }
    public void setDashboardReminderLimit(int value) { dashboardReminderLimit = value; }
    public int getStaleAfterHours() { return staleAfterHours; }
    public void setStaleAfterHours(int value) { staleAfterHours = value; }
    public int getMaxTimesPerDay() { return maxTimesPerDay; }
    public void setMaxTimesPerDay(int value) { maxTimesPerDay = value; }
    public int getMaxEventsPerResponse() { return maxEventsPerResponse; }
    public void setMaxEventsPerResponse(int value) { maxEventsPerResponse = value; }
}
