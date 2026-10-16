package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.*;
import vn.nutrimom.consultation.service.ConsultationMonitoringService;
import vn.nutrimom.consultation.video.*;
import vn.nutrimom.notification.service.*;

@EnabledIfSystemProperty(named="admin.audit.postgres.test",matches="true")
class ConsultationMonitoringConcurrencyPostgresTest {
    final DriverManagerDataSource source=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55439/admin_qa","adminqa","");
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final NamedParameterJdbcTemplate named=new NamedParameterJdbcTemplate(source);
    final Clock clock=Clock.fixed(Instant.now().plusSeconds(86400),ZoneOffset.UTC);
    final ConsultationMonitoringService monitor=new ConsultationMonitoringService(named,new DataSourceTransactionManager(source),clock,
            new VideoProperties(),org.mockito.Mockito.mock(LiveKitGateway.class),org.mockito.Mockito.mock(NotificationService.class),new AdminNotificationEvents(named));
    String user,id;
    @BeforeEach void setup() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_qa",source.getUrl());
        user=UUID.randomUUID().toString();id=UUID.randomUUID().toString();
        jdbc.update("insert into app.users(id,email,display_name,status,created_at,updated_at) values (?,?,'Concurrency QA','ACTIVE',now(),now())",user,user+"@qa.test");
        jdbc.update("""
                insert into app.consultation_requests(id,user_id,specialty,assignment_type,status,
                    acceptance_opened_at,acceptance_deadline_at,acceptance_policy_version,created_at,updated_at,version)
                values (?,?,'HEALTH','RANDOM','PENDING_EXPERT',?,?,2,now(),now(),0)
                """,id,user,OffsetDateTime.now(clock).minusMinutes(31),OffsetDateTime.now(clock).minusSeconds(1));
    }
    @AfterEach void cleanup() {
        jdbc.update("delete from app.admin_notification_outbox where source_id=?",id);
        jdbc.update("delete from app.consultation_requests where id=?",id);
        jdbc.update("delete from app.users where id=?",user);
    }
    @Test void twoWorkersEmitOnlyOneDurableOverdueEvent() throws Exception {
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Void> work=()->{barrier.await(5,TimeUnit.SECONDS);monitor.checkDeadlines();return null;};
            var a=pool.submit(work);var b=pool.submit(work);a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            monitor.checkDeadlines();
            assertEquals(1,jdbc.queryForObject("select count(*) from app.admin_notification_outbox where event_name='ADMIN_ACCEPTANCE_OVERDUE' and source_id=?",Integer.class,id));
            assertEquals(1,jdbc.queryForObject("select count(*) from app.consultation_monitor_events where request_id=?",Integer.class,id));
        } finally {pool.shutdownNow();}
    }
    @Test void workerSkipsBookingLockedByAcceptOrCancellationAndRetriesNextSweep() throws Exception {
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try(var query=connection.prepareStatement("select id from app.consultation_requests where id=? for update")) {
                query.setString(1,id);try(var row=query.executeQuery()) {assertTrue(row.next());}
            }
            monitor.checkDeadlines();
            assertEquals(0,jdbc.queryForObject("select count(*) from app.consultation_monitor_events where request_id=?",Integer.class,id));
            connection.commit();
        }
        monitor.checkDeadlines();
        assertEquals(1,jdbc.queryForObject("select count(*) from app.consultation_monitor_events where request_id=?",Integer.class,id));
    }
}
