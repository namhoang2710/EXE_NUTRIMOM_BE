package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;

/** Destructive fixtures are restricted to this disposable localhost database, never the configured datasource. */
@EnabledIfSystemProperty(named = "admin.migration.history.test", matches = "true")
class AdminMigrationHistoryPostgresTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1:55439/admin_migration_history";
    private static final String USER = "00000000-0000-4000-8000-000000000071";
    private static final String REQUEST = "00000000-0000-4000-8000-000000000072";

    private Flyway flyway(String target) {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_migration_history", URL);
        return Flyway.configure().dataSource(URL, "adminqa", "").schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").target(target).cleanDisabled(false).load();
    }
    @BeforeEach void reset() { flyway("latest").clean(); }
    @AfterEach void cleanup() { flyway("latest").clean(); }
    private Connection connection() throws SQLException { return DriverManager.getConnection(URL, "adminqa", ""); }
    private void execute(String sql) throws SQLException {
        try (var connection = connection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private String scalar(String sql) throws SQLException {
        try (var connection = connection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
    private void appliedChecksums() throws SQLException {
        try (var connection = connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("select version,checksum from app.flyway_schema_history where version in ('42','46','51','53')")) {
            Map<String, Integer> actual = new LinkedHashMap<>();
            while (rows.next()) actual.put(rows.getString(1), rows.getInt(2));
            assertEquals(Map.of("42", -366517247, "46", 1540402008, "51", 1932212754, "53", 66632425), actual);
        }
    }
    private void contract() throws Exception {
        assertEquals("ix_users_admin_created", scalar("select relname from pg_class where oid=to_regclass('app.ix_users_admin_created')"));
        assertNull(scalar("select to_regclass('app.ix_admin_expert_performance_schedule')"));
        assertNotNull(scalar("select to_regclass('app.ix_consultation_monitor_due')"));
        assertEquals("3", scalar("select count(*) from pg_trigger where not tgisinternal and ((tgrelid='app.payment_orders'::regclass and tgname='trg_payment_subscription_revenue') or (tgrelid='app.consultation_monitor_events'::regclass and tgname in ('trg_admin_realtime_collect','trg_admin_realtime_flush')))"));
        String function = scalar("select pg_get_functiondef('app.recognize_subscription_upgrade()'::regprocedure)");
        assertTrue(function.contains("mod(pg_current_xact_id()::text::numeric,4294967296)"));
        assertTrue(function.contains("s.start_date<NEW.end_date"));
        assertTrue(function.contains("ON CONFLICT (recognition_key)"));
        assertTrue(flyway("latest").validateWithResult().validationSuccessful);
        appliedChecksums();
    }
    private void seed() throws Exception {
        execute("insert into app.users(id,phone,display_name,status,created_at,updated_at,version) values ('" + USER + "','migration-qa','Migration QA','ACTIVE',now(),now(),0)");
        execute("insert into app.user_roles(user_id,role) values ('" + USER + "','USER')");
        execute("insert into app.consultation_requests(id,user_id,specialty,assignment_type,status,created_at,updated_at,version) values ('" + REQUEST + "','" + USER + "','HEALTH','RANDOM','PENDING_EXPERT',now(),now(),0)");
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,source,paid_at,created_at,updated_at,version) values (gen_random_uuid()::text,7100001,'" + USER + "','PLAN_399K',399000,'VND','PAID','REAL',now(),now(),now(),0)");
                statement.execute("insert into app.user_subscriptions(id,user_id,plan_tier,status,start_date,end_date,created_at,updated_at,version) values (gen_random_uuid()::text,'" + USER + "','PLAN_399K','ACTIVE',now(),now()+interval '180 days',now(),now(),0)");
                statement.execute("insert into app.notifications(id,user_id,type,title,body,read_at,created_at,updated_at,version) values (gen_random_uuid()::text,'" + USER + "','SYSTEM','Existing','Existing',now(),now(),now(),0)");
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }
    private Map<String, String> snapshot() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String table : new String[]{"users", "user_subscriptions", "payment_orders", "notifications", "admin_notification_outbox", "consultation_requests", "expert_consultation_checkins", "consultation_monitor_state", "expert_checkin_policy", "admin_realtime_revisions"}) {
            values.put(table, scalar("select coalesce(jsonb_agg(payload order by payload::text),'[]'::jsonb)::text from (select to_jsonb(t) payload from app." + table + " t) data"));
        }
        return values;
    }
    @Test void freshInstallValidatesAndSecondMigrateDoesNothing() throws Exception {
        var current = flyway("latest"); current.migrate(); contract();
        assertEquals("60", current.info().current().getVersion().getVersion());
        assertEquals(0, current.migrate().migrationsExecuted);
    }
    @Test void upgradesAppliedHistoryWithoutBackfillOrEditingReceiptsBalancesOrReadStates() throws Exception {
        flyway("59").migrate(); appliedChecksums(); seed();
        assertEquals("1", scalar("select count(*) from app.payment_orders"));
        Map<String, String> before = snapshot();
        var current = flyway("latest"); assertEquals(1, current.migrate().migrationsExecuted);
        assertEquals(before, snapshot()); contract(); assertEquals(0, current.migrate().migrationsExecuted);
    }
    @Test void alreadyPresentObjectsAreNotDuplicated() throws Exception {
        flyway("59").migrate(); seed();
        execute(new ClassPathResource("db/migration/V60__reconcile_admin_migration_history.sql").getContentAsString(StandardCharsets.UTF_8));
        Map<String, String> before = snapshot();
        assertEquals(1, flyway("latest").migrate().migrationsExecuted);
        assertEquals(before, snapshot()); contract();
    }
    @Test void invalidLegacyWindowFailsAtomicallyInsteadOfSilentlyRewritingAppointments() throws Exception {
        flyway("59").migrate(); seed();
        execute("update app.consultation_requests set scheduled_starts_at=now(),scheduled_ends_at=null where id='" + REQUEST + "'");
        Map<String, String> before = snapshot();
        assertThrows(FlywayException.class, () -> flyway("latest").migrate());
        assertEquals(before, snapshot()); appliedChecksums();
        assertEquals("0", scalar("select count(*) from app.flyway_schema_history where version='60'"));
        assertNotNull(scalar("select to_regclass('app.ix_admin_expert_performance_schedule')"));
        assertNull(scalar("select to_regclass('app.ix_users_admin_created')"));
    }
    @Test void canonicalWindowRejectsHalfPopulatedPairAndMonitoringEventUpdatesRevision() throws Exception {
        flyway("latest").migrate(); seed();
        SQLException invalid = assertThrows(SQLException.class, () -> execute("update app.consultation_requests set scheduled_starts_at=now(),scheduled_ends_at=null where id='" + REQUEST + "'"));
        assertEquals("23514", invalid.getSQLState());
        long before = Long.parseLong(scalar("select revision from app.admin_realtime_revisions where scope_key='DASHBOARD'"));
        execute("insert into app.consultation_monitor_events(request_id,event_kind,created_at) values ('" + REQUEST + "','ATTENDANCE_DUE',now())");
        assertEquals(before + 1, Long.parseLong(scalar("select revision from app.admin_realtime_revisions where scope_key='DASHBOARD'")));
    }
    @Test void subscriptionFirstWriterReconcilesOnlyItsSameTransactionRecognition() throws Exception {
        flyway("latest").migrate(); seed();
        String customer = "00000000-0000-4000-8000-000000000073";
        String order = "00000000-0000-4000-8000-000000000074";
        execute("insert into app.users(id,phone,display_name,status,created_at,updated_at,version) values ('" + customer + "','writer-qa','Writer QA','ACTIVE',now(),now(),0)");
        execute("insert into app.user_roles(user_id,role) values ('" + customer + "','USER')");
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,source,created_at,updated_at,version) values ('" + order + "',7100002,'" + customer + "','PLAN_399K',399000,'VND','PENDING','REAL',now(),now(),0)");
                statement.execute("insert into app.user_subscriptions(id,user_id,plan_tier,status,start_date,end_date,created_at,updated_at,version) values (gen_random_uuid()::text,'" + customer + "','PLAN_399K','ACTIVE',now(),now()+interval '180 days',now(),now(),0)");
                statement.execute("update app.payment_orders set status='PAID',paid_at=now() where id='" + order + "'");
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
        assertEquals("1", scalar("select count(*) from app.payment_orders where user_id='" + customer + "'"));
        assertEquals("399000", scalar("select sum(amount) from app.payment_orders where user_id='" + customer + "' and status='PAID'"));
        assertEquals(order, scalar("select id from app.payment_orders where user_id='" + customer + "'"));
        String paidAt = scalar("select paid_at::text from app.payment_orders where id='" + order + "'");
        execute("update app.payment_orders set status='PAID' where id='" + order + "'");
        assertEquals(paidAt, scalar("select paid_at::text from app.payment_orders where id='" + order + "'"));
    }
    @Test void futureSubscriptionOverlappingAnEarlierStartDoesNotRecognizeTwice() throws Exception {
        flyway("latest").migrate(); seed();
        String customer = "00000000-0000-4000-8000-000000000075";
        execute("insert into app.users(id,phone,display_name,status,created_at,updated_at,version) values ('" + customer + "','overlap-qa','Overlap QA','ACTIVE',now(),now(),0)");
        execute("insert into app.user_roles(user_id,role) values ('" + customer + "','USER')");
        execute("insert into app.user_subscriptions(id,user_id,plan_tier,status,start_date,end_date,created_at,updated_at,version) values (gen_random_uuid()::text,'" + customer + "','PLAN_399K','ACTIVE',now()+interval '1 day',now()+interval '180 days',now(),now(),0)");
        execute("insert into app.user_subscriptions(id,user_id,plan_tier,status,start_date,end_date,created_at,updated_at,version) values (gen_random_uuid()::text,'" + customer + "','PLAN_399K','ACTIVE',now(),now()+interval '180 days',now(),now(),0)");
        assertEquals("1", scalar("select count(*) from app.payment_orders where user_id='" + customer + "'"));
        assertEquals("399000", scalar("select sum(amount) from app.payment_orders where user_id='" + customer + "' and status='PAID'"));
    }
}
