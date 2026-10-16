package vn.nutrimom.config;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.nutrimom.admin.realtime.*;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(AdminRealtimeProperties.class)
@ConditionalOnProperty(name="app.admin.realtime.enabled",havingValue="true")
public class AdminRealtimeConfiguration {
    @Bean(destroyMethod="shutdownNow") ExecutorService adminRealtimeSender(AdminRealtimeProperties properties) {
        return new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(properties.getMaxConnections()),
                task->{var thread=new Thread(task,"admin-sse-send");thread.setDaemon(true);return thread;},
                new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean AdminRealtimeHub adminRealtimeHub(AdminRealtimeRepository repository,AdminRealtimeProperties properties,
            Clock clock,@Qualifier("adminRealtimeSender") ExecutorService sender,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        var hub=new AdminRealtimeHub(repository,properties,clock,sender);
        var registry=registries.getIfAvailable();
        if(registry!=null)io.micrometer.core.instrument.Gauge.builder("nutrimom.admin.realtime.connections",hub,AdminRealtimeHub::connectionCount).register(registry);
        return hub;
    }
    @Bean(destroyMethod="shutdownNow") ScheduledExecutorService adminRealtimePoller(AdminRealtimeHub hub) {
        // Dedicated clock: outbox batches or unrelated scheduled jobs cannot starve SSE heartbeats.
        var scheduler=Executors.newSingleThreadScheduledExecutor(task->{
            var thread=new Thread(task,"admin-sse-poll");thread.setDaemon(true);return thread;
        });
        scheduler.scheduleWithFixedDelay(hub::tick,1000,1000,TimeUnit.MILLISECONDS);
        return scheduler;
    }
}
