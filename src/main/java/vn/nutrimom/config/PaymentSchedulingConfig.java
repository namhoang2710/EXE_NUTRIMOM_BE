package vn.nutrimom.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật scheduler cho job đối soát đơn thanh toán ({@code PaymentReconciliationJob}).
 *
 * <p>Điều kiện đặt trên chính class config chứ không chỉ trên bean job, vì cùng một lý do đã ghi ở
 * {@link CalendarSchedulingConfig}: class bị bỏ qua thì {@code @Import(SchedulingConfiguration)}
 * của {@link EnableScheduling} không chạy, {@code ScheduledAnnotationBeanPostProcessor} không được
 * đăng ký và không còn thread nền nào trong context test.</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.payment.reconcile-job-enabled", havingValue = "true")
@EnableScheduling
public class PaymentSchedulingConfig {
}
