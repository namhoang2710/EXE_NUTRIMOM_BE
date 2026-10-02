package vn.nutrimom.calendar.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.nutrimom.calendar.repository.CalendarReminderRepository;

/**
 * Quét định kỳ các nhắc nhở tới hạn và giao cho {@link CalendarReminderDispatcher} gửi.
 *
 * <p>Bean này chỉ tìm việc, không ghi gì: mỗi dòng được xử lý trong một transaction riêng nên một
 * dòng hỏng không kéo theo những dòng còn lại — xem javadoc của dispatcher về lý do phải tách bean.</p>
 *
 * <p>Tắt bằng {@code NUTRIMOM_CALENDAR_JOB_ENABLED=false}; profile test tắt sẵn để không có thread
 * nền chạy song song với integration test.</p>
 */
@Component
@ConditionalOnProperty(name = "app.calendar.reminder-job-enabled", havingValue = "true")
public class CalendarReminderJob {

    private static final Logger log = LoggerFactory.getLogger(CalendarReminderJob.class);

    private final CalendarReminderRepository reminders;
    private final CalendarReminderDispatcher dispatcher;
    private final Clock clock;

    public CalendarReminderJob(CalendarReminderRepository reminders,
                               CalendarReminderDispatcher dispatcher,
                               Clock clock) {
        this.reminders = reminders;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    /**
     * {@code fixedDelay} (không phải {@code fixedRate}): một lượt quét chậm không được xếp hàng chồng
     * lên lượt sau. {@code initialDelay} để lượt đầu không chạy giữa lúc context còn đang khởi động.
     */
    @Scheduled(
            initialDelayString = "${app.calendar.reminder-job-interval:PT5M}",
            fixedDelayString = "${app.calendar.reminder-job-interval:PT5M}")
    public void scan() {
        List<String> due = reminders.findDueIds(OffsetDateTime.now(clock));
        if (due.isEmpty()) {
            return;
        }
        log.debug("Calendar reminder scan found {} due reminder(s)", due.size());
        for (String id : due) {
            try {
                dispatcher.dispatch(id);
            } catch (RuntimeException ex) {
                // Một nhắc nhở hỏng không được dừng cả hàng đợi; lượt quét sau sẽ thử lại vì
                // transaction của dòng đó đã rollback nên notified_at vẫn null.
                log.warn("Failed to dispatch calendar reminder {}", id, ex);
            }
        }
    }
}
