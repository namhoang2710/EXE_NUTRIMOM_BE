package vn.nutrimom.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * V67 trên PostgreSQL thật. H2 không có partial index, không có planner và không có trigger của
 * V45, nên không bài nào trong bộ H2 nói được gì về bốn điều dưới đây.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfSystemProperty(named = "payment.compatibility.test", matches = "true")
class PaymentLifecycleMigrationPostgresTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;

    @Test
    void runsAgainstTheIsolatedQaDatabase() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_qa", env.getProperty("spring.datasource.url"));
    }

    @Test
    void migrationAddsTheLifecycleColumnsAndThePartialIndex() {
        assertEquals(2L, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema='app' and table_name='payment_orders'
                  and column_name in ('expires_at','reconcile_attempt_at')
                """, Long.class));

        String definition = jdbc.queryForObject(
                "select indexdef from pg_indexes where schemaname='app' and indexname='ix_payment_orders_pending_expiry'",
                String.class);
        assertTrue(definition.contains("WHERE"), "index phải là partial, nếu không nó to bằng cả bảng");
        assertTrue(definition.contains("'PENDING'") && definition.contains("'REAL'"), definition);
    }

    /**
     * Câu quét của job phải dùng được index. Tắt seq-scan để planner buộc phải nói ra nó có đường
     * index nào không: bảng QA nhỏ nên mặc định nó luôn chọn seq-scan và phép kiểm sẽ vô nghĩa.
     */
    @Test
    void theOverdueScanCanUseThePartialIndex() {
        jdbc.execute("set local enable_seqscan = off");
        List<String> plan = jdbc.queryForList("""
                explain select p.order_code from app.payment_orders p
                where p.status='PENDING' and p.source='REAL'
                  and ((p.expires_at is not null and p.expires_at < now())
                       or (p.expires_at is null and p.created_at < now()))
                  and (p.reconcile_attempt_at is null or p.reconcile_attempt_at < now())
                order by p.created_at limit 50
                """, String.class);
        assertTrue(String.join("\n", plan).contains("ix_payment_orders_pending_expiry"),
                String.join("\n", plan));
    }

    @Test
    void theStatusConstraintAlreadyAcceptsExpired() {
        String id = insert("REAL", null, "EXPIRED");
        assertEquals("EXPIRED", jdbc.queryForObject(
                "select status from app.payment_orders where id=?", String.class, id));
    }

    /**
     * Câu claim của job chỉ liệt kê một cột, và đó là điều kiện sống còn: trigger V45 nổ theo
     * <em>danh sách cột</em> của UPDATE chứ không theo giá trị thay đổi. Bài này chứng minh cả hai
     * chiều - claim không đánh thức trigger, còn một UPDATE kiểu Hibernate (liệt kê mọi cột) thì có.
     */
    @Test
    void theClaimUpdateDoesNotWakeTheLegacyWriterTrigger() {
        String id;
        jdbc.execute("alter table app.payment_orders disable trigger trg_payment_source_legacy_writer");
        try {
            id = insert("LEGACY_UNKNOWN", "MOCK-" + UUID.randomUUID(), "PENDING");
            assertEquals("LEGACY_UNKNOWN", source(id));
        } finally {
            // PG forbids ALTER TABLE while V50's commit-time triggers are pending in this fixture transaction.
            jdbc.execute("set constraints all immediate");
            jdbc.execute("alter table app.payment_orders enable trigger trg_payment_source_legacy_writer");
            jdbc.execute("set constraints all deferred");
        }

        jdbc.update("update app.payment_orders set reconcile_attempt_at = now() where id = ?", id);
        assertEquals("LEGACY_UNKNOWN", source(id));

        jdbc.update("update app.payment_orders set source = source, payos_payment_link_id = payos_payment_link_id where id = ?", id);
        assertEquals("DEMO", source(id));
    }

    private String source(String id) {
        return jdbc.queryForObject("select source from app.payment_orders where id=?", String.class, id);
    }

    private String insert(String source, String linkId, String status) {
        String id = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into app.users(id, phone, display_name, status, created_at, updated_at)
                values (?, ?, 'Migration Fixture', 'ACTIVE', now(), now())
                """, userId, "09" + System.nanoTime() % 100000000L);
        jdbc.update("""
                insert into app.payment_orders(id, order_code, user_id, plan_tier, amount, currency,
                        status, source, payos_payment_link_id, version, created_at, updated_at)
                values (?, ?, ?, 'PLAN_99K', 99000, 'VND', ?, ?, ?, 0, now(), now())
                """, id, System.nanoTime() % 9000000000L, userId, status, source, linkId);
        return id;
    }
}
