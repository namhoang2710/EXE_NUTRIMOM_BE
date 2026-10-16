package vn.nutrimom.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import vn.nutrimom.admin.service.AdminDashboardMetricsService;
import vn.nutrimom.admin.service.AdminMetricsSnapshotJob;

/** Independent of reminder scheduling, and completely disabled in business tests. */
@Configuration
@ConditionalOnProperty(name="app.admin.metrics-snapshot-enabled",havingValue="true")
@EnableScheduling
public class AdminMetricsSchedulingConfig {
    @Bean AdminMetricsSnapshotJob adminMetricsSnapshotJob(AdminDashboardMetricsService metrics) {
        return new AdminMetricsSnapshotJob(metrics);
    }
}
