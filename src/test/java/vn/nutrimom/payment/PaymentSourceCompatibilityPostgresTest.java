package vn.nutrimom.payment;

import static org.junit.jupiter.api.Assertions.*;
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

/** Exercises the PostgreSQL migration trigger against legacy SQL writers, rollback-only. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfSystemProperty(named="payment.compatibility.test",matches="true")
class PaymentSourceCompatibilityPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;

    @Test void oldWriterIsClassifiedByLinkWhileSyntheticPaidOrdersStayUnknown() {
        var provider=insert(null,"0123456789abcdef0123456789abcdef");
        var mock=insert(null,"MOCK-legacy-order");
        var unproven=insert(null,null);
        var malformed=insert(null,"test-link-placeholder");
        assertEquals("REAL",source(provider));assertEquals("DEMO",source(mock));
        assertEquals("LEGACY_UNKNOWN",source(unproven));assertEquals("LEGACY_UNKNOWN",source(malformed));
        assertEquals(399000L,jdbc.queryForObject("select sum(amount) from app.payment_orders where id in (?,?,?,?) and source='REAL' and status='PAID' and paid_at is not null",Long.class,provider,mock,unproven,malformed));
    }
    @Test void lateLinkAndUpdatesPreserveExplicitSourceAndNeverChangeSubscriptionOrDates() {
        var pending=insert(null,null);
        var explicitDemo=insert("DEMO","0123456789abcdef0123456789abcdef");
        var explicitReal=insert("REAL","MOCK-not-a-reclassification");
        var before=jdbc.queryForMap("select created_at,paid_at,version from app.payment_orders where id=?",pending);
        Long subscriptions=jdbc.queryForObject("select count(*) from app.user_subscriptions",Long.class);
        jdbc.update("update app.payment_orders set payos_payment_link_id='0123456789abcdef0123456789abcdef' where id=?",pending);
        assertEquals("REAL",source(pending));assertEquals("DEMO",source(explicitDemo));assertEquals("REAL",source(explicitReal));
        assertEquals(before,jdbc.queryForMap("select created_at,paid_at,version from app.payment_orders where id=?",pending));
        assertEquals(subscriptions,jdbc.queryForObject("select count(*) from app.user_subscriptions",Long.class));
    }
    @Test void migrationCatchUpRepairsOnlyLinkedRowsAndIsIdempotent() throws Exception {
        String provider,mock,unknown;
        jdbc.execute("alter table app.payment_orders disable trigger trg_payment_source_legacy_writer");
        try {
            provider=insert(null,"0123456789abcdef0123456789abcdef");mock=insert(null,"MOCK-existing");unknown=insert(null,null);
            assertEquals("LEGACY_UNKNOWN",source(provider));
        } finally {
            // PG forbids ALTER TABLE while V50's commit-time triggers are pending in this fixture transaction.
            jdbc.execute("set constraints all immediate");
            jdbc.execute("alter table app.payment_orders enable trigger trg_payment_source_legacy_writer");
            jdbc.execute("set constraints all deferred");
        }
        var dates=jdbc.queryForMap("select created_at,paid_at from app.payment_orders where id=?",provider);
        var resource=new org.springframework.core.io.ClassPathResource("db/migration/V45__payment_source_legacy_writer_compatibility.sql");
        String migration=resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String catchUp=migration.substring(migration.lastIndexOf("UPDATE app.payment_orders"));
        assertEquals(2,jdbc.update(catchUp));assertEquals(0,jdbc.update(catchUp));
        assertEquals("REAL",source(provider));assertEquals("DEMO",source(mock));assertEquals("LEGACY_UNKNOWN",source(unknown));
        assertEquals(1L,jdbc.queryForObject("select version from app.payment_orders where id=?",Long.class,provider));
        assertEquals(dates,jdbc.queryForMap("select created_at,paid_at from app.payment_orders where id=?",provider));
    }
    private String source(String id) { return jdbc.queryForObject("select source from app.payment_orders where id=?",String.class,id); }
    private String insert(String source,String link) {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_qa",env.getProperty("spring.datasource.url"));
        String user=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        jdbc.update("insert into app.users(id,phone,display_name,status,created_at,updated_at,version,onboarding_status) values(?,?,?,'ACTIVE',current_timestamp,current_timestamp,0,'COMPLETED')",user,"compat-"+user.substring(0,8),"Compatibility QA");
        if(source==null)jdbc.update("insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,payos_payment_link_id,created_at,updated_at,paid_at,version) values(?,?,?,'PLAN_399K',399000,'VND','PAID',?,current_timestamp,current_timestamp,current_timestamp,0)",id,Math.abs(UUID.randomUUID().getMostSignificantBits()),user,link);
        else jdbc.update("insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,payos_payment_link_id,source,created_at,updated_at,paid_at,version) values(?,?,?,'PLAN_399K',399000,'VND','PAID',?,?,current_timestamp,current_timestamp,current_timestamp,0)",id,Math.abs(UUID.randomUUID().getMostSignificantBits()),user,link,source);
        return id;
    }
}
