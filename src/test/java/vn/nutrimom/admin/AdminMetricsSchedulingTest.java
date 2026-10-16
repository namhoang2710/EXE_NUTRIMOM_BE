package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import vn.nutrimom.admin.service.AdminDashboardMetricsService;
import vn.nutrimom.admin.service.AdminMetricsSnapshotJob;
import vn.nutrimom.config.AdminMetricsSchedulingConfig;
import vn.nutrimom.config.CalendarSchedulingConfig;

class AdminMetricsSchedulingTest {
    @Test void metricsSchedulerIsIndependentOfDisabledCalendarAndCanBeCompletelyDisabled() {
        var service=mock(AdminDashboardMetricsService.class);
        var runner=new ApplicationContextRunner()
                .withUserConfiguration(AdminMetricsSchedulingConfig.class,CalendarSchedulingConfig.class)
                .withBean(AdminDashboardMetricsService.class,()->service)
                .withPropertyValues("app.calendar.reminder-job-enabled=false");
        runner.withPropertyValues("app.admin.metrics-snapshot-enabled=true").run(context->{
            assertNull(context.getStartupFailure());
            assertEquals(2,context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
            context.getBean(AdminMetricsSnapshotJob.class).capture();verify(service).capture();
            assertFalse(context.containsBean("calendarSchedulingConfig"));
        });
        runner.withPropertyValues("app.admin.metrics-snapshot-enabled=false").run(context->{
            assertEquals(0,context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size());
            assertEquals(0,context.getBeansOfType(AdminMetricsSnapshotJob.class).size());
        });
    }
}
