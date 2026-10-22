package vn.nutrimom.payment;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.nutrimom.payment.service.PaymentReconciliationJob;

/**
 * Cổng scheduler và việc đọc cấu hình của job đối soát thanh toán.
 *
 * <p>Profile test tắt job, nên cả phần còn lại của bộ test không bao giờ chạm tới
 * {@code @EnableScheduling} lẫn placeholder {@code fixedDelayString}. Hai thứ đó chỉ nổ lúc khởi
 * động context, mà khởi động context là production. Class này bật cờ lên đúng một lần để lỗi cấu
 * hình bị bắt ở CI thay vì lúc deploy.</p>
 */
@SpringBootTest(properties = "app.payment.reconcile-job-enabled=true")
@ActiveProfiles("test")
class PaymentReconcileJobSchedulingTest {

    @Autowired ApplicationContext context;
    @Autowired ScheduledTaskHolder scheduledTasks;

    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;

    @Test
    void enablingTheFlagRegistersTheScanWithTheConfiguredIsoInterval() {
        assertThat(context.getBeansOfType(PaymentReconciliationJob.class)).hasSize(1);

        // fixedDelayString nhận ISO-8601; sai định dạng thì context fail chứ không tới đây.
        assertThat(scheduledTasks.getScheduledTasks())
                .extracting(ScheduledTask::getTask)
                .filteredOn(FixedDelayTask.class::isInstance)
                .extracting(task -> ((FixedDelayTask) task).getIntervalDuration())
                .contains(Duration.ofMinutes(5));
    }
}
