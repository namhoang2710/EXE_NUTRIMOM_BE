package vn.nutrimom.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.nutrimom.notification.service.AdminNotificationDispatcher;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.notification.admin-job-enabled", havingValue = "true", matchIfMissing = true)
public class AdminNotificationSchedulingConfig {
    @Bean AdminNotificationJob adminNotificationJob(AdminNotificationDispatcher dispatcher) {
        return new AdminNotificationJob(dispatcher);
    }

    public static final class AdminNotificationJob {
        private static final Logger log = LoggerFactory.getLogger(AdminNotificationJob.class);
        private final AdminNotificationDispatcher dispatcher;
        private int failures;
        private long retryAfter;

        AdminNotificationJob(AdminNotificationDispatcher dispatcher) { this.dispatcher = dispatcher; }

        @Scheduled(fixedDelay = 1000, initialDelay = 1000)
        public void deliver() {
            if (retryAfter != 0 && System.nanoTime() - retryAfter < 0) return;
            try {
                dispatcher.dispatchBatch();
                failures = 0;
                retryAfter = 0;
            } catch (RuntimeException failure) {
                failures = Math.min(failures + 1, 4);
                retryAfter = System.nanoTime() + Math.min(60, 5L << failures) * 1_000_000_000L;
                log.warn("Admin notification job deferred error={}", failure.getClass().getSimpleName());
            }
        }
    }
}
