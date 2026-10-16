package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.admin.dto.AdminOperationsDtos.CustomerSnapshot;
import vn.nutrimom.admin.repository.AdminDashboardMetricsRepository;
import vn.nutrimom.admin.service.AdminDashboardMetricsService;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class AdminMetricConcurrencyIntegrationTest {
    @Autowired AdminDashboardMetricsRepository snapshots;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminDashboardMetricsService service;
    @MockitoBean Clock clock;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    @Test void concurrentCaptureTransactionsUseTheSameAtomicUpsertWithoutSerializationFailures() throws Exception {
        var hour=OffsetDateTime.parse("2026-05-01T00:00:00Z");
        var sequence=new AtomicInteger();
        when(clock.instant()).thenAnswer(call->hour.plusSeconds(sequence.incrementAndGet()).toInstant());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        var pool=Executors.newFixedThreadPool(4);var ready=new CountDownLatch(4);var start=new CountDownLatch(1);
        try {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++)tasks.add(pool.submit(()->{
                ready.countDown();try {start.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new RuntimeException(failure);}
                service.capture();
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            for(var task:tasks)task.get(20,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("select count(*) from app.admin_customer_metrics where snapshot_hour=?",Integer.class,hour));
            var captured=jdbc.queryForObject("select captured_at from app.admin_customer_metrics where snapshot_hour=?",
                    (r,n)->r.getObject(1,OffsetDateTime.class),hour);
            assertEquals(hour.plusSeconds(4).toInstant(),captured.toInstant());
        } finally {
            start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));
            new TransactionTemplate(transactions).executeWithoutResult(status->jdbc.update("delete from app.admin_customer_metrics where snapshot_hour=?",hour));
        }
    }
    @Test void concurrentSnapshotsKeepOneLatestObservationForAnHour() throws Exception {
        var hour=OffsetDateTime.parse("2026-04-01T00:00:00Z");
        var tx=new TransactionTemplate(transactions);
        var pool=Executors.newFixedThreadPool(4);
        var ready=new CountDownLatch(4);var start=new CountDownLatch(1);
        try {
            var pending=new ArrayList<Future<?>>();
            for(int i=1;i<=4;i++) {
                final int offset=i;
                pending.add(pool.submit(()->{
                    ready.countDown();try {start.await();}catch(InterruptedException failure) {
                        Thread.currentThread().interrupt();throw new RuntimeException(failure);
                    }
                    tx.executeWithoutResult(status->snapshots.save(new CustomerSnapshot(hour.plusMinutes(offset),10,
                            10-offset,0,offset)));
                }));
            }
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            for(var task:pending)task.get(20,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("select count(*) from app.admin_customer_metrics where snapshot_hour=?",Integer.class,hour));
            assertEquals(4,jdbc.queryForObject("select plan_399k_users from app.admin_customer_metrics where snapshot_hour=?",Integer.class,hour));
            var latest=jdbc.queryForObject("select captured_at from app.admin_customer_metrics where snapshot_hour=?",
                    (r,n)->r.getObject(1,OffsetDateTime.class),hour);
            assertEquals(hour.plusMinutes(4).toInstant(),latest.toInstant());
        } finally {
            start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));
            tx.executeWithoutResult(status->jdbc.update("delete from app.admin_customer_metrics where snapshot_hour=?",hour));
        }
    }
}
