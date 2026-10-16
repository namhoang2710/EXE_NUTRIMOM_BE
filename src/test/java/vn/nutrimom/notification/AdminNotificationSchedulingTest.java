package vn.nutrimom.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.nutrimom.config.AdminNotificationSchedulingConfig;
import vn.nutrimom.config.AdminNotificationSchedulingConfig.AdminNotificationJob;
import vn.nutrimom.notification.service.AdminNotificationDispatcher;

class AdminNotificationSchedulingTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(AdminNotificationDispatcher.class, () -> mock(AdminNotificationDispatcher.class))
            .withUserConfiguration(AdminNotificationSchedulingConfig.class);

    @Test void jobIsEnabledByDefault() {
        context.run(application -> assertThat(application).hasSingleBean(AdminNotificationJob.class));
    }

    @Test void jobCanBeDisabledIndependentlyFromOtherSchedulers() {
        context.withPropertyValues("app.notification.admin-job-enabled=false")
                .run(application -> assertThat(application).doesNotHaveBean(AdminNotificationJob.class));
    }
}
