package vn.nutrimom.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

public class AdminMetricsSnapshotJob {
    private static final Logger log=LoggerFactory.getLogger(AdminMetricsSnapshotJob.class);
    private final AdminDashboardMetricsService metrics;
    public AdminMetricsSnapshotJob(AdminDashboardMetricsService metrics) {this.metrics=metrics;}
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron="0 0 * * * *",zone="Asia/Ho_Chi_Minh")
    @Scheduled(cron="0 59 23 * * *",zone="Asia/Ho_Chi_Minh")
    public void capture() {
        try {metrics.capture();}
        catch(RuntimeException failure) {
            log.warn("Admin metrics observation failed failureType={}",failure.getClass().getSimpleName());
        }
    }
}
