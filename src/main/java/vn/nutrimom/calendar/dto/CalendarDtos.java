package vn.nutrimom.calendar.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import vn.nutrimom.calendar.domain.CalendarSource;
import vn.nutrimom.calendar.domain.OccurrenceStatus;
import vn.nutrimom.calendar.domain.ReminderStatus;
import vn.nutrimom.calendar.domain.ReminderType;
import vn.nutrimom.calendar.domain.RepeatRule;

/** DTO của module lịch và nhắc nhở (spec mục 10). */
public final class CalendarDtos {
    private CalendarDtos() {
    }

    /**
     * Một mốc trên lịch, đã trộn từ cả ba nguồn.
     *
     * <p>Item cố ý <em>phẳng</em>: thay vì mỗi nguồn một hình dạng payload riêng, phần thông tin
     * phụ được render sẵn vào {@code subtitle} (tên chuyên gia / loại hồ sơ / cơ sở y tế) để FE vẽ
     * một ô lịch mà không phải phân nhánh theo {@code source}. Cần chi tiết thì mở
     * {@code deepLink} hoặc gọi endpoint của chính nguồn đó bằng {@code sourceId}.</p>
     *
     * @param date  ngày địa phương theo múi giờ của request — ô lịch nào chứa mốc này. Tính sẵn ở
     *              backend để FE không phải lặp lại phép quy đổi múi giờ và lệch ngày ở mốc biên.
     * @param status trạng thái của nguồn nếu có ({@code ConsultationStatus}, {@link ReminderStatus},
     *              hoặc {@link OccurrenceStatus} với một lần lặp đã được đánh dấu); hồ sơ y tế
     *              không có trạng thái nên trả null.
     * @param recurring {@code true} khi item này là MỘT lần của một chuỗi lặp. Khi đó
     *              {@code starts_at} chính là mốc cần gửi lại ở {@code occurrence_at} nếu người
     *              dùng bấm "đã uống" cho riêng lần này.
     */
    public record CalendarEventItem(
            CalendarSource source,
            String sourceId,
            String title,
            String subtitle,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            LocalDate date,
            String status,
            String deepLink,
            Boolean recurring) {
    }

    /** Một ngày có mốc, để vẽ lưới tháng (spec mục 10 "response gọn"). */
    public record CalendarMonthDay(
            LocalDate date,
            int eventCount,
            List<CalendarSource> types) {
    }

    /**
     * Quy tắc lặp, dựng theo cách người dùng nghĩ về báo thức điện thoại: chọn giờ, chọn thứ, chọn
     * tần suất.
     *
     * @param interval    mỗi N ngày/tuần/tháng; null = 1. {@code rule=DAILY, interval=2} là "cách ngày".
     * @param daysOfWeek  chỉ dùng cho {@link RepeatRule#WEEKLY}; rỗng = lấy thứ của {@code starts_at}.
     * @param timesOfDay  nhiều mốc trong ngày (thuốc sáng/tối là MỘT nhắc nhở, không phải hai);
     *                    rỗng = lấy giờ của {@code starts_at}. Giờ hiểu theo {@code timezone} của
     *                    nhắc nhở, nên đổi giờ mùa không làm mốc trôi.
     * @param until       ngày cuối còn lặp; null = không giới hạn.
     */
    public record RepeatSpec(
            @NotNull(message = "Thiếu kiểu lặp.") RepeatRule rule,
            @Min(value = 1, message = "Khoảng lặp tối thiểu là 1.")
            @Max(value = 365, message = "Khoảng lặp tối đa là 365.") Integer interval,
            Set<DayOfWeek> daysOfWeek,
            List<LocalTime> timesOfDay,
            LocalDate until) {
    }

    /** Đánh dấu MỘT lần lặp cụ thể. {@code status} null = bỏ đánh dấu, trả mốc về chưa làm. */
    public record UpsertOccurrenceRequest(
            @NotNull(message = "Thiếu mốc cần đánh dấu.") OffsetDateTime occurrenceAt,
            OccurrenceStatus status) {
    }

    public record CreateReminderRequest(
            @NotNull(message = "Vui lòng chọn loại nhắc nhở.") ReminderType type,
            @NotBlank(message = "Vui lòng nhập tiêu đề nhắc nhở.")
            @Size(max = 255, message = "Tiêu đề tối đa 255 ký tự.") String title,
            @NotNull(message = "Vui lòng chọn thời điểm nhắc nhở.") OffsetDateTime startsAt,
            String timezone,
            @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự.") String note,
            @Size(max = 255, message = "Tên cơ sở y tế tối đa 255 ký tự.") String facilityName,
            @Min(value = 0, message = "Số phút nhắc trước không được âm.")
            @Max(value = 10080, message = "Chỉ nhắc trước tối đa 7 ngày (10080 phút).")
            Integer remindMinutesBefore,
            String pregnancyId,
            @Valid RepeatSpec repeat) {
    }

    /**
     * Thân của nút "thêm vào lịch nhắc nhở" trên một hồ sơ y tế.
     *
     * <p>Tiêu đề, cơ sở y tế và thai kỳ copy thẳng từ hồ sơ nên không có trong body; truyền
     * {@code title} nếu muốn ghi đè tiêu đề mặc định.</p>
     */
    public record CreateReminderFromRecordRequest(
            @NotNull(message = "Vui lòng chọn loại nhắc nhở.") ReminderType type,
            @NotNull(message = "Vui lòng chọn thời điểm nhắc nhở.") OffsetDateTime startsAt,
            @Size(max = 255, message = "Tiêu đề tối đa 255 ký tự.") String title,
            String timezone,
            @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự.") String note,
            @Min(value = 0, message = "Số phút nhắc trước không được âm.")
            @Max(value = 10080, message = "Chỉ nhắc trước tối đa 7 ngày (10080 phút).")
            Integer remindMinutesBefore,
            @Valid RepeatSpec repeat) {
    }

    /**
     * PATCH một nhắc nhở. Field null = không đổi, trừ {@code version} là bắt buộc (optimistic lock,
     * spec mục 22).
     *
     * <p>{@code clearRemindMinutesBefore} tồn tại vì null đã mang nghĩa "không đổi", nên không có
     * cách nào khác để nói "bỏ hẳn việc nhắc".</p>
     */
    public record UpdateReminderRequest(
            @NotNull(message = "Thiếu version của bản ghi.") Long version,
            ReminderType type,
            @Size(max = 255, message = "Tiêu đề tối đa 255 ký tự.") String title,
            OffsetDateTime startsAt,
            String timezone,
            @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự.") String note,
            @Size(max = 255, message = "Tên cơ sở y tế tối đa 255 ký tự.") String facilityName,
            @Min(value = 0, message = "Số phút nhắc trước không được âm.")
            @Max(value = 10080, message = "Chỉ nhắc trước tối đa 7 ngày (10080 phút).")
            Integer remindMinutesBefore,
            Boolean clearRemindMinutesBefore,
            ReminderStatus status,
            @Valid RepeatSpec repeat,
            Boolean clearRepeat) {
    }

    /**
     * @param nextSuggestion mốc kế tiếp gợi ý, chỉ có khi vừa chuyển sang {@link ReminderStatus#DONE}
     *                       và loại nhắc nhở có chu kỳ. KHÔNG được lưu — client muốn thì tự gọi
     *                       POST để tạo mốc mới.
     */
    public record ReminderResponse(
            String id,
            ReminderType type,
            String title,
            OffsetDateTime startsAt,
            String timezone,
            LocalDate date,
            String note,
            String facilityName,
            Integer remindMinutesBefore,
            OffsetDateTime remindAt,
            ReminderStatus status,
            OffsetDateTime notifiedAt,
            String pregnancyId,
            String sourceRecordId,
            RepeatSpec repeat,
            OffsetDateTime nextOccurrence,
            OffsetDateTime nextSuggestion,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
