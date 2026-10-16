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

/** Real PostgreSQL triggers, isolated QA database, all fixtures rolled back. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfSystemProperty(named="payment.compatibility.test",matches="true")
class SubscriptionRevenuePostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;

    @Test void sqlFreeToPaidRecognizesNominalPriceOnceAndPreserves180Days() {
        String user=user(),sub=sub(user,"FREE",180);
        jdbc.update("update app.user_subscriptions set plan_tier='PLAN_399K' where id=?",sub);
        assertEquals(399000L,amount(user));assertEquals(1L,count(user));
        assertTrue(jdbc.queryForObject("select bool_and(order_code<=9007199254740991) from app.payment_orders where user_id=?",Boolean.class,user));
        assertEquals(180L,jdbc.queryForObject("select extract(day from end_date-start_date)::bigint from app.user_subscriptions where id=?",Long.class,sub));
        jdbc.update("update app.user_subscriptions set plan_tier='PLAN_399K',end_date=end_date+interval '10 days' where id=?",sub);
        assertEquals(399000L,amount(user));assertEquals(1L,count(user));
        assertEquals(190L,jdbc.queryForObject("select extract(day from end_date-start_date)::bigint from app.user_subscriptions where id=?",Long.class,sub));
        assertTrue(jdbc.queryForObject("select bool_and(payos_payment_link_id is null and checkout_url is null and qr_code is null) from app.payment_orders where user_id=?",Boolean.class,user));
    }
    @Test void paidOrderInSameTransactionAndDuplicateSubscriptionDoNotDoubleCount() {
        String user=user();paid(user);
        sub(user,"PLAN_399K",30);
        assertEquals(399000L,amount(user));assertEquals(1L,count(user));
        sub(user,"PLAN_399K",180);
        assertEquals(1L,count(user));
    }
    @Test void new99kTierThenFreeThen399kAreTwoRecognitionsNotDurationMultiples() {
        String user=user(),sub=sub(user,"PLAN_99K",30);
        assertEquals(99000L,amount(user));
        jdbc.update("update app.user_subscriptions set plan_tier='FREE' where id=?",sub);
        jdbc.update("update app.user_subscriptions set plan_tier='PLAN_399K' where id=?",sub);
        assertEquals(498000L,amount(user));assertEquals(2L,count(user));
    }
    @Test void legacyWriterFlushingSubscriptionBeforePaidOrderDoesNotDoubleCount() {
        String user=user();sub(user,"PLAN_399K",30);assertEquals(1L,count(user));
        paid(user);assertEquals(399000L,amount(user));assertEquals(1L,count(user));
        assertEquals(0L,jdbc.queryForObject("select count(*) from app.payment_orders where user_id=? and recognition_key is not null",Long.class,user));
    }
    @Test void backfillDoesNotDuplicateExistingPaidOrdersAndIsIdempotent() throws Exception {
        String covered=user(),uncovered=user();paid(covered);
        jdbc.execute("alter table app.user_subscriptions disable trigger trg_subscription_revenue");
        try {sub(covered,"PLAN_399K",180);sub(uncovered,"PLAN_99K",180);}
        finally {
            // Flush deferred revision/notification triggers before fixture-only DDL, then restore commit-time mode.
            jdbc.execute("set constraints all immediate");
            jdbc.execute("alter table app.user_subscriptions enable trigger trg_subscription_revenue");
            jdbc.execute("set constraints all deferred");
        }
        String migration=new org.springframework.core.io.ClassPathResource("db/migration/V46__recognize_subscription_upgrades.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String backfill=migration.substring(migration.lastIndexOf("INSERT INTO app.payment_orders"));
        assertEquals(1,jdbc.update(backfill));assertEquals(0,jdbc.update(backfill));
        assertEquals(399000L,amount(covered));assertEquals(99000L,amount(uncovered));
    }
    @Test void staffAndExpiredOrFreeTiersDoNotCreateRevenue() {
        String staff=user();jdbc.update("insert into app.user_roles(user_id,role) values(?,'ADMIN')",staff);
        sub(staff,"PLAN_399K",30);assertEquals(0L,count(staff));
        String free=user();sub(free,"FREE",180);assertEquals(0L,count(free));
        String expired=user(),id=UUID.randomUUID().toString();
        jdbc.update("insert into app.user_subscriptions(id,user_id,plan_tier,status,start_date,end_date,created_at,updated_at,version) values(?,?,'PLAN_399K','ACTIVE',current_timestamp-interval '31 days',current_timestamp-interval '1 day',current_timestamp,current_timestamp,0)",id,expired);
        assertEquals(0L,count(expired));
    }
    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentOverlappingInsertsRecognizeOneUpgrade() throws Exception {
        String user=user();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(()->{
                try(var connection=jdbc.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    try(var statement=connection.prepareStatement("insert into app.user_subscriptions(id,user_id,plan_tier,status,start_date,end_date,created_at,updated_at,version) values(?,?,'PLAN_399K','ACTIVE',current_timestamp,current_timestamp+interval '180 days',current_timestamp,current_timestamp,0)")) {
                        statement.setString(1,UUID.randomUUID().toString());statement.setString(2,user);
                        ready.countDown();if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Timed out");
                        statement.executeUpdate();connection.commit();
                    }
                } catch(Exception error) {throw new IllegalStateException(error);}
            }));
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            for(var task:tasks)task.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(399000L,amount(user));assertEquals(1L,count(user));
        } finally {
            start.countDown();pool.shutdownNow();pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS);
            jdbc.update("delete from app.payment_orders where user_id=?",user);
            jdbc.update("delete from app.user_subscriptions where user_id=?",user);
            jdbc.update("delete from app.user_roles where user_id=?",user);
            jdbc.update("delete from app.users where id=?",user);
        }
    }
    private String user() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_qa",env.getProperty("spring.datasource.url"));
        String id=UUID.randomUUID().toString();
        jdbc.update("insert into app.users(id,phone,display_name,status,created_at,updated_at,version,onboarding_status) values(?,?,'Revenue QA','ACTIVE',current_timestamp,current_timestamp,0,'COMPLETED')",id,"rev-"+id.substring(0,8));
        jdbc.update("insert into app.user_roles(user_id,role) values(?,'USER')",id);return id;
    }
    private String sub(String user,String tier,int days) {
        String id=UUID.randomUUID().toString();
        jdbc.update("insert into app.user_subscriptions(id,user_id,plan_tier,status,start_date,end_date,created_at,updated_at,version) values(?,?,?,'ACTIVE',current_timestamp,current_timestamp+?*interval '1 day',current_timestamp,current_timestamp,0)",id,user,tier,days);
        return id;
    }
    private void paid(String user) {
        jdbc.update("insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,source,paid_at,created_at,updated_at,version) values(?,?,?,'PLAN_399K',399000,'VND','PAID','REAL',current_timestamp,current_timestamp,current_timestamp,0)",UUID.randomUUID().toString(),Math.abs(UUID.randomUUID().getMostSignificantBits()),user);
    }
    private long amount(String user) {return jdbc.queryForObject("select coalesce(sum(amount),0) from app.payment_orders where user_id=? and status='PAID'",Long.class,user);}
    private long count(String user) {return jdbc.queryForObject("select count(*) from app.payment_orders where user_id=?",Long.class,user);}
}
