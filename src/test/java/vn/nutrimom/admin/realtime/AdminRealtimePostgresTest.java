package vn.nutrimom.admin.realtime;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.nutrimom.notification.service.AdminNotificationDispatcher;

/** Committed PostgreSQL triggers and two-connection races; never points at the application database. */
@EnabledIfSystemProperty(named="admin.audit.postgres.test",matches="true")
class AdminRealtimePostgresTest {
    static final String URL="jdbc:postgresql://127.0.0.1:55439/admin_qa";
    final Set<String> owned=new HashSet<>();
    final DriverManagerDataSource source=new DriverManagerDataSource(URL,"adminqa","");
    final AdminRealtimeRepository repository=new AdminRealtimeRepository(new NamedParameterJdbcTemplate(source));
    String customer,admin,other;
    @BeforeEach void setup() throws Exception {
        try(var c=source.getConnection()) {assertEquals("admin_qa",c.getCatalog());assertTrue(c.getMetaData().getURL().contains("127.0.0.1:55439/admin_qa"));}
        dispatch(); // Drain older fixture jobs before registering this test's new recipients.
        customer=user("USER");admin=user("ADMIN");other=user("ADMIN");
    }
    @AfterEach void cleanup() throws Exception {
        try(var c=source.getConnection()) {
            c.setAutoCommit(false);
            for(String user:owned) {
                execute(c,"delete from app.admin_notification_outbox where deep_link=? or source_id in (select id from app.payment_orders where user_id=?)","nutrimom://admin/users/"+user,user);
                execute(c,"delete from app.notifications where user_id=?",user);
                execute(c,"delete from app.user_subscriptions where user_id=?",user);
                execute(c,"delete from app.payment_orders where user_id=?",user);
                execute(c,"delete from app.user_roles where user_id=?",user);
                execute(c,"delete from app.users where id=?",user);
            }
            c.commit();
            for(String user:owned)execute(c,"delete from app.admin_realtime_revisions where scope_key=?","NOTIFICATIONS:"+user);
        }
    }
    @Test void changesBecomeVisibleOnlyAfterCommitAndManyChangesCoalesceOnce() throws Exception {
        long before=revision();
        try(var c=source.getConnection()) {
            c.setAutoCommit(false);
            execute(c,"update app.users set display_name='One' where id=?",customer);
            execute(c,"update app.users set display_name='Two' where id=?",customer);
            assertEquals(before,revision());c.commit();
        }
        assertEquals(before+1,revision());
        try(var c=source.getConnection()) {
            c.setAutoCommit(false);execute(c,"update app.users set display_name='Rollback' where id=?",customer);c.rollback();
        }
        assertEquals(before+1,revision());
    }
    @Test void pgGrantCreatesOneRevenueAndOneNotificationForEachAdminAndDurationDoesNotAddMoney() throws Exception {
        String sub=grant(customer,"PLAN_399K");
        assertEquals(399000,amount(customer));assertEquals(1,manualEvents());
        dispatch();
        var a=repository.snapshots(Set.of(admin,other));assertEquals(1,a.get(admin).unreadCount());assertEquals(1,a.get(other).unreadCount());
        assertEquals("1",a.get(admin).notificationsRevision());
        try(var c=source.getConnection()) {execute(c,"update app.user_subscriptions set end_date=end_date+interval '10 days' where id=?",sub);}
        assertEquals(399000,amount(customer));assertEquals(2,manualEvents());dispatch();
        assertEquals(2,repository.snapshots(Set.of(admin)).get(admin).unreadCount());
        try(var c=source.getConnection()) {execute(c,"update app.user_subscriptions set end_date=end_date where id=?",sub);}
        assertEquals(2,manualEvents());
    }
    @Test void netZeroAndRollbackSubscriptionChangesDoNotCreateEvents() throws Exception {
        String sub=grant(customer,"PLAN_99K");long count=manualEvents(),before=revision();
        try(var c=source.getConnection()) {
            c.setAutoCommit(false);
            execute(c,"update app.user_subscriptions set end_date=end_date+interval '1 day' where id=?",sub);
            execute(c,"update app.user_subscriptions set end_date=end_date-interval '1 day' where id=?",sub);c.commit();
        }
        assertEquals(count,manualEvents());assertEquals(99000,amount(customer));
        long committed=revision();assertEquals(before+1,committed);
        try(var c=source.getConnection()) {c.setAutoCommit(false);execute(c,"update app.user_subscriptions set plan_tier='PLAN_399K' where id=?",sub);c.rollback();}
        assertEquals(count,manualEvents());assertEquals(99000,amount(customer));assertEquals(committed,revision());
    }
    @Test void apiPurchaseEventSuppressesGenericEventInEitherFlushOrder() throws Exception {
        for(boolean subscriptionFirst:List.of(false,true)) {
            String user=user("USER"),order=UUID.randomUUID().toString();
            try(var c=source.getConnection()) {
                c.setAutoCommit(false);if(subscriptionFirst)subscription(c,user,"PLAN_399K");
                execute(c,"""
                        insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,source,paid_at,created_at,updated_at,version)
                        values (?,? ,?,'PLAN_399K',399000,'VND','PAID','REAL',now(),now(),now(),0)
                        """,order,Math.abs(new Random().nextLong(1,Long.MAX_VALUE)),user);
                if(!subscriptionFirst)subscription(c,user,"PLAN_399K");
                execute(c,"""
                        insert into app.admin_notification_outbox(id,event_key,event_name,source_id,body,deep_link,created_at,next_attempt_at,attempts)
                        values (?,?,'ADMIN_PAYMENT_PAID',?,'QA','nutrimom://admin/payment-orders/123',now(),now(),0)
                        """,UUID.randomUUID().toString(),"ADMIN_PAYMENT_PAID:"+order,order);
                c.commit();
            }
            assertEquals(399000,amount(user));
            assertEquals(0,scalar("select count(*) from app.admin_notification_outbox where deep_link=?","nutrimom://admin/users/"+user));
        }
    }
    @Test void readStateAndRevisionsAreScopedAndIdempotent() throws Exception {
        grant(customer,"PLAN_399K");dispatch();
        String otherRevision=repository.notificationRevision(other),adminRevision=repository.notificationRevision(admin);
        try(var c=source.getConnection()) {
            execute(c,"update app.notifications set read_at=now() where user_id=? and audience='ADMIN' and read_at is null",admin);
        }
        var state=repository.snapshots(Set.of(admin,other,customer));
        assertFalse(state.containsKey(customer));assertEquals(0,state.get(admin).unreadCount());assertEquals(1,state.get(other).unreadCount());
        assertEquals(Long.parseLong(adminRevision)+1,Long.parseLong(state.get(admin).notificationsRevision()));
        assertEquals(otherRevision,state.get(other).notificationsRevision());
        String revision=repository.notificationRevision(admin);
        try(var c=source.getConnection()) {execute(c,"update app.notifications set read_at=now() where user_id=? and read_at is null",admin);}
        assertEquals(revision,repository.notificationRevision(admin));
    }
    @Test void concurrentBusinessWritesDoNotAcquireGlobalRevisionLockBeforeCommitOrLoseChanges() throws Exception {
        long before=revision();var bothWritten=new CountDownLatch(2);var secondCommitted=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->{
                try(var c=source.getConnection()) {
                    c.setAutoCommit(false);execute(c,"update app.users set display_name='A' where id=?",customer);
                    bothWritten.countDown();assertTrue(bothWritten.await(5,TimeUnit.SECONDS));
                    assertTrue(secondCommitted.await(5,TimeUnit.SECONDS));c.commit();
                }return true;
            });
            var b=executor.submit(()->{
                try(var c=source.getConnection()) {
                    c.setAutoCommit(false);execute(c,"update app.users set display_name='B' where id=?",admin);
                    bothWritten.countDown();assertTrue(bothWritten.await(5,TimeUnit.SECONDS));c.commit();secondCommitted.countDown();
                }return true;
            });
            assertTrue(a.get(12,TimeUnit.SECONDS));assertTrue(b.get(12,TimeUnit.SECONDS));assertEquals(before+2,revision());
        } finally {executor.shutdownNow();}
    }
    @Test void freeAndStaffGrantsDoNotGenerateCustomerPurchaseEvents() throws Exception {
        grant(admin,"PLAN_399K");grant(customer,"FREE");assertEquals(0,manualEvents());assertEquals(0,amount(admin));
    }
    long revision() {return Long.parseLong(repository.dashboardRevision());}
    long manualEvents() throws Exception {return scalar("select count(*) from app.admin_notification_outbox where event_name='ADMIN_SUBSCRIPTION_CHANGED' and deep_link=?","nutrimom://admin/users/"+customer);}
    long amount(String user) throws Exception {return scalar("select coalesce(sum(amount),0) from app.payment_orders where user_id=? and status='PAID'",user);}
    void dispatch() {new AdminNotificationDispatcher(new NamedParameterJdbcTemplate(source),new DataSourceTransactionManager(source)).dispatchBatch();}
    String user(String role) throws Exception {
        String id=UUID.randomUUID().toString();owned.add(id);
        try(var c=source.getConnection()) {
            c.setAutoCommit(false);
            execute(c,"insert into app.users(id,email,display_name,status,created_at,updated_at,version,onboarding_status) values (?,?,'SSE QA','ACTIVE',now(),now(),0,'COMPLETED')",id,id+"@qa.test");
            execute(c,"insert into app.user_roles(user_id,role) values (?,?)",id,role);c.commit();
        }return id;
    }
    String grant(String user,String plan) throws Exception {
        try(var c=source.getConnection()) {c.setAutoCommit(false);String id=subscription(c,user,plan);c.commit();return id;}
    }
    String subscription(Connection c,String user,String plan) throws Exception {
        String id=UUID.randomUUID().toString();
        execute(c,"insert into app.user_subscriptions(id,user_id,plan_tier,start_date,end_date,status,version,created_at,updated_at) values (?,?,?,now()-interval '1 hour',now()+interval '180 days','ACTIVE',0,now(),now())",id,user,plan);
        return id;
    }
    long scalar(String sql,Object...params) throws Exception {
        try(var c=source.getConnection();var s=c.prepareStatement(sql)) {bind(s,params);try(var r=s.executeQuery()) {r.next();return r.getLong(1);}}
    }
    static void execute(Connection c,String sql,Object...params) throws SQLException {
        try(var statement=c.prepareStatement(sql)) {bind(statement,params);statement.executeUpdate();}
    }
    static void bind(PreparedStatement s,Object...params) throws SQLException {for(int i=0;i<params.length;i++)s.setObject(i+1,params[i]);}
}
