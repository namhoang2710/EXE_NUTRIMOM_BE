package vn.nutrimom.calendar.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.calendar.domain.CalendarReminderEntity;
import vn.nutrimom.calendar.repository.CalendarReminderRepository;
import vn.nutrimom.config.CalendarProperties;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.NotificationService;

/**
 * Gửi thông báo cho đúng một lần lặp tới hạn của một nhắc nhở.
 *
 * <p>Tách khỏi {@link CalendarReminderJob} thành bean riêng là bắt buộc, không phải để cho gọn:
 * gọi một method {@code @Transactional} từ trong cùng bean thì không đi qua proxy, cả lượt quét sẽ
 * dùng chung một transaction và một dòng lỗi kéo đổ mọi dòng đã xử lý trước đó.</p>
 *
 * <p>Idempotent nhờ {@link CalendarReminderRepository#claim}: khoá là <em>mốc lặp</em>, nên hai
 * instance cùng quét thì chỉ một bên gửi, mà chuỗi lặp vẫn bắn tiếp được ở lần sau. Publish nằm sau
 * claim trong cùng transaction nên nếu publish hỏng, claim cũng rollback và lượt quét sau thử lại.</p>
 */
@Service
public class CalendarReminderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CalendarReminderDispatcher.class);
    private static final String SOURCE_TYPE = "CALENDAR_REMINDER";
    private static final DateTimeFormatter AT_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy");

    /**
     * Trần số lần bỏ qua khi đuổi kịp một chuỗi đã trễ lâu.
     *
     * <p>Hệ thống ngừng chạy một tháng thì một nhắc nhở hằng ngày có 30 mốc quá hạn. Không được gửi
     * 30 thông báo, cũng không được bỏ dòng đó lại mãi — phải nhảy qua hết mốc cũ rồi gửi mốc hợp
     * lệ gần nhất, trong đúng một lượt.
     */
    private static final int MAX_CATCH_UP_STEPS = 500;

    private final CalendarReminderRepository reminders;
    private final NotificationService notifications;
    private final CalendarProperties properties;
    private final Clock clock;

    public CalendarReminderDispatcher(CalendarReminderRepository reminders,
                                      NotificationService notifications,
                                      CalendarProperties properties,
                                      Clock clock) {
        this.reminders = reminders;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(String reminderId) {
        CalendarReminderEntity reminder = reminders.findById(reminderId).orElse(null);
        if (reminder == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        ReminderSchedule schedule = ReminderSchedule.of(reminder);

        // Bỏ qua hết các mốc đã trôi qua quá lâu (vd hệ thống vừa ngừng chạy mấy ngày): vẫn phải
        // dời mốc đã-bắn tới đó để lượt sau không quét lại, nhưng không gửi "sắp tới giờ" cho một
        // buổi hẹn của tuần trước.
        OffsetDateTime watermark = reminder.getLastFiredOccurrence();
        OffsetDateTime target = null;
        for (int step = 0; step < MAX_CATCH_UP_STEPS; step++) {
            Optional<OffsetDateTime> next = schedule.nextOccurrenceAfter(watermark);
            if (next.isEmpty()) {
                break;
            }
            watermark = next.get();
            if (!isDue(reminder, watermark, now)) {
                // Mốc kế tiếp chưa tới hạn nhắc — chưa có gì để gửi lượt này.
                watermark = reminder.getLastFiredOccurrence();
                break;
            }
            if (!isStale(watermark, now)) {
                target = watermark;
                break;
            }
            log.info("Skipping stale occurrence {} of reminder {}", watermark, reminderId);
        }
        if (watermark == null || watermark.equals(reminder.getLastFiredOccurrence())) {
            return;
        }

        OffsetDateTime nextRemindAt = schedule.nextOccurrenceAfter(watermark)
                .map(occurrence -> remindAtFor(reminder, occurrence))
                .orElse(null);
        if (reminders.claim(reminderId, watermark, nextRemindAt, now) != 1) {
            // Instance khác đã giành mốc này trước.
            return;
        }
        if (target == null) {
            return;
        }
        notifications.publish(reminder.getOwnerUserId(), NotificationType.REMINDER,
                "Sắp tới lịch: " + reminder.getTitle(),
                body(reminder, target),
                "nutrimom://calendar/reminders/" + reminderId,
                SOURCE_TYPE, reminderId);
    }

    /** Mốc nhắc của một lần lặp = thời điểm lặp trừ đi số phút nhắc trước. */
    private static OffsetDateTime remindAtFor(CalendarReminderEntity reminder,
                                              OffsetDateTime occurrence) {
        Integer before = reminder.getRemindMinutesBefore();
        return before == null ? null : occurrence.minusMinutes(before);
    }

    private static boolean isDue(CalendarReminderEntity reminder, OffsetDateTime occurrence,
                                 OffsetDateTime now) {
        OffsetDateTime remindAt = remindAtFor(reminder, occurrence);
        return remindAt != null && !remindAt.isAfter(now);
    }

    private boolean isStale(OffsetDateTime occurrence, OffsetDateTime now) {
        return occurrence.plusHours(properties.getStaleAfterHours()).isBefore(now);
    }

    /**
     * Thân thông báo cố ý chỉ nhắc giờ và nơi khám, không kèm {@code note}: ghi chú là nội dung
     * người dùng tự viết về sức khoẻ của mình và thông báo có thể hiện trên màn hình khoá.
     */
    private static String body(CalendarReminderEntity reminder, OffsetDateTime occurrence) {
        String at = AT_FORMAT.format(occurrence.atZoneSameInstant(zoneOf(reminder)));
        return reminder.getFacilityName() == null || reminder.getFacilityName().isBlank()
                ? "Lịch của bạn bắt đầu lúc " + at + "."
                : "Lịch của bạn bắt đầu lúc " + at + " tại " + reminder.getFacilityName() + ".";
    }

    /**
     * Múi giờ đã được kiểm lúc ghi, nhưng một dòng cũ hỏng không được phép làm job rollback rồi thử
     * lại vô tận — rơi về giờ VN và vẫn gửi được thông báo.
     */
    private static ZoneId zoneOf(CalendarReminderEntity reminder) {
        try {
            return ZoneId.of(reminder.getTimezone());
        } catch (DateTimeException ex) {
            log.warn("Reminder {} has an unusable timezone, falling back", reminder.getId());
            return CalendarZone.VIETNAM_ZONE;
        }
    }
}
