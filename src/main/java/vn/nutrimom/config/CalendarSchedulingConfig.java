package vn.nutrimom.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật scheduler cho job nhắc lịch ({@code CalendarReminderJob}). Đây là {@code @EnableScheduling}
 * đầu tiên của backend.
 *
 * <p>Điều kiện đặt trên chính class config chứ không chỉ trên bean job: khi class bị bỏ qua,
 * {@code @Import(SchedulingConfiguration)} của {@link EnableScheduling} không chạy nên
 * {@code ScheduledAnnotationBeanPostProcessor} không được đăng ký, và
 * {@code TaskSchedulingAutoConfiguration} (vốn {@code @ConditionalOnBean} đúng bean đó) cũng không
 * dựng thread pool. Tắt cờ là tắt sạch, không còn thread nền nào trong context test.</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.calendar.reminder-job-enabled", havingValue = "true")
@EnableScheduling
public class CalendarSchedulingConfig {
}
