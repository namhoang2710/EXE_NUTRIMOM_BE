package vn.nutrimom.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import vn.nutrimom.calendar.service.CalendarReminderJob;

/**
 * Cổng scheduler và việc đọc cấu hình của job nhắc lịch.
 *
 * <p>Profile test tắt job, nên cả phần còn lại của bộ test không bao giờ chạm tới
 * {@code @EnableScheduling} lẫn placeholder {@code fixedDelayString}. Hai thứ đó chỉ nổ lúc khởi
 * động context — mà khởi động context là production. Class này bật cờ lên đúng một lần để lỗi cấu
 * hình bị bắt ở CI thay vì lúc deploy.</p>
 *
 * <p>Trường hợp tắt cờ không cần test riêng: 200+ test còn lại đều chạy với nó tắt và không có
 * thread nền nào quét DB.</p>
 */
@SpringBootTest(properties = "app.calendar.reminder-job-enabled=true")
@ActiveProfiles("test")
class CalendarReminderJobSchedulingTest {

    @Autowired ApplicationContext context;
    @Autowired ScheduledTaskHolder scheduledTasks;

    @Test
    void enablingTheFlagRegistersTheScanWithTheConfiguredIsoInterval() {
        assertThat(context.getBeansOfType(CalendarReminderJob.class)).hasSize(1);

        // fixedDelayString nhận ISO-8601; sai định dạng thì context fail chứ không tới đây.
        assertThat(scheduledTasks.getScheduledTasks())
                .extracting(ScheduledTask::getTask)
                .filteredOn(FixedDelayTask.class::isInstance)
                .extracting(task -> ((FixedDelayTask) task).getIntervalDuration())
                .contains(Duration.ofMinutes(5));
    }
}
