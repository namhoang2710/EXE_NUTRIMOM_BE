package vn.nutrimom.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.nutrimom.consultation.service.ConsultationMonitoringService;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="app.consultation.monitor-enabled",havingValue="true",matchIfMissing=true)
public class ConsultationMonitoringConfiguration {
    private static final Logger log=LoggerFactory.getLogger(ConsultationMonitoringConfiguration.class);
    private final ConsultationMonitoringService monitor;
    public ConsultationMonitoringConfiguration(ConsultationMonitoringService monitor) {this.monitor=monitor;}
    @Scheduled(fixedDelayString="${app.consultation.monitor-interval-ms:15000}",initialDelay=15000)
    public void monitor() {
        try {monitor.sweep();} catch(RuntimeException failure) {
            log.warn("Consultation monitoring deferred error={}",failure.getClass().getSimpleName());
        }
    }
}
