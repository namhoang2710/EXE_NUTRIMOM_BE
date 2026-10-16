package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.admin.repository.AdminOperationsRepository;
import vn.nutrimom.admin.service.AdminOperationsService;

/** Concurrent commit must not split the total, daily chart and by-plan within one revenue response. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "admin.audit.postgres.test", matches = "true")
class AdminRevenueSnapshotPostgresTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-15T05:00:00Z");
    @Autowired AdminOperationsService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired Environment environment;
    @MockitoSpyBean AdminOperationsRepository repository;
    @MockitoBean Clock clock;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    private String user;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_qa", environment.getProperty("spring.datasource.url"));
        when(clock.instant()).thenReturn(AT.toInstant());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(call -> Clock.fixed(AT.toInstant(), call.getArgument(0)));
        tx = new TransactionTemplate(manager);
        user = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into app.users(id,phone,display_name,status,created_at,updated_at,version,onboarding_status)
                    values (?,?,'Revenue QA','ACTIVE',?,?,0,'COMPLETED')
                    """, user, "audit-" + user.substring(0, 8), AT.minusDays(1), AT.minusDays(1));
            jdbc.update("insert into app.user_roles(user_id,role) values (?,'USER')", user);
            insertPayment(399000);
        });
    }

    @AfterEach void cleanup() {
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from app.payment_orders where user_id=?", user);
            jdbc.update("delete from app.users where id=?", user);
        });
    }

    @Test void periodResponseKeepsOneSnapshotWhilePaymentCommits() throws Exception { assertSnapshot("PERIOD"); }
    @Test void lifetimeResponseKeepsOneSnapshotWhilePaymentCommits() throws Exception { assertSnapshot("ALL_TIME"); }

    private void assertSnapshot(String range) throws Exception {
        var firstTotalRead = new CountDownLatch(1);
        var writerCommitted = new CountDownLatch(1);
        var pause = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (pause.compareAndSet(true, false)) {
                firstTotalRead.countDown();
                assertTrue(writerCommitted.await(10, TimeUnit.SECONDS), "Concurrent writer did not commit");
            }
            return result;
        }).when(repository).paymentTotals(any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> {
                try {
                    assertTrue(firstTotalRead.await(10, TimeUnit.SECONDS));
                    tx.executeWithoutResult(status -> insertPayment(99000));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                } finally { writerCommitted.countDown(); }
            });
            var snapshot = service.revenue(null, null, "ALL", range);
            writer.get(10, TimeUnit.SECONDS);
            assertEquals(399000, snapshot.summary().collectedAmount());
            assertEquals(1, snapshot.summary().paidOrders());
            assertEquals(snapshot.summary().collectedAmount(), snapshot.byPlan().stream().mapToLong(row -> row.amount()).sum());
            assertEquals(snapshot.summary().paidOrders(), snapshot.byPlan().stream().mapToLong(row -> row.paidOrders()).sum());
            if (range.equals("PERIOD")) assertEquals(snapshot.summary().collectedAmount(),
                    snapshot.dailySeries().stream().mapToLong(row -> row.amount()).sum());
            assertEquals(498000, service.revenue(null, null, "ALL", range).summary().collectedAmount());
        } finally { writerCommitted.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    private void insertPayment(int amount) {
        var id = UUID.randomUUID().toString();
        long code = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        jdbc.update("""
                insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,source,
                    paid_at,created_at,updated_at,version)
                values (?,?,?,? ,?,'VND','PAID','REAL',?,?,?,0)
                """, id, code, user, amount == 399000 ? "PLAN_399K" : "PLAN_99K", amount,
                AT.minusSeconds(1), AT.minusSeconds(1), AT.minusSeconds(1));
    }
}
