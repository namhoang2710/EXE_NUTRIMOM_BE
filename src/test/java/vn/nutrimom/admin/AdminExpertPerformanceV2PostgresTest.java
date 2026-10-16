package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.admin.dto.AdminExpertPerformanceDtos.*;
import vn.nutrimom.admin.realtime.AdminRealtimeRepository;
import vn.nutrimom.admin.repository.*;
import vn.nutrimom.admin.service.AdminExpertPerformanceService;
import vn.nutrimom.common.exception.BusinessException;

@EnabledIfSystemProperty(named="admin.audit.postgres.test",matches="true")
class AdminExpertPerformanceV2PostgresTest {
    static final String URL="jdbc:postgresql://127.0.0.1:55439/admin_qa";
    static final Instant NOW=Instant.parse("2026-10-15T05:00:00Z");
    final DriverManagerDataSource source=new DriverManagerDataSource(URL,"adminqa","");
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final NamedParameterJdbcTemplate reads=org.mockito.Mockito.spy(new NamedParameterJdbcTemplate(source));
    final AdminRealtimeRepository revisions=new AdminRealtimeRepository(reads);
    final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    final AdminExpertPerformanceService service=new AdminExpertPerformanceService(
            new AdminExpertPerformanceRepository(reads),new AdminOperationsRepository(reads),revisions,clock);
    final List<String> owned=new ArrayList<>();
    String expert,customer;
    final DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
    org.springframework.transaction.TransactionStatus transaction;
    String baseline;
    @BeforeEach void setup() {
        assertEquals(URL,source.getUrl());
        transaction=manager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        customer=account("USER");expert=expert("OBSTETRICS");baseline=appointment(expert,"2026-09-01",9,"COMPLETED");
        jdbc.update("update app.consultation_monitor_state set started_at='2026-09-01T00:00:00Z',tracking_started_at='2026-09-01T00:00:00Z',checked_at=?,healthy=true where id=1",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC));
        jdbc.update("delete from app.consultation_monitor_gaps");
        jdbc.update("update app.expert_checkin_policy set started_at='2026-09-01T00:00:00Z' where id=1");
    }
    @AfterEach void cleanup() {manager.rollback(transaction);}
    Performance snapshot() {return service.snapshot(LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),null,expert,true,2);}
    Axis axis(Performance p,String key) {return p.axes().stream().filter(a->a.key().equals(key)).findFirst().orElseThrow();}
    void canonical(String id,String date) {
        jdbc.update("update app.consultation_requests set scheduled_starts_at=?::timestamptz,scheduled_ends_at=?::timestamptz where id=?",
                date+"T09:00:00+07:00",date+"T10:00:00+07:00",id);
    }
    @Test void punctualityUsesFirstVerifiedArrivalAndNeverCompletionTime() {
        String[] times={"08:55:00","09:00:00","09:03:00","09:03:01","09:15:00"};
        for(int i=0;i<times.length;i++) {
            String date="2026-10-"+(10+i);String id=appointment(expert,date,9,"COMPLETED");canonical(id,date);
            video(id,OffsetDateTime.parse(date+"T"+times[i]+"+07:00").toInstant().toString(),true);
        }
        String absent=appointment(expert,"2026-10-09",9,"COMPLETED");canonical(absent,"2026-10-09");
        var p=snapshot();assertEquals(2,p.calculationVersion());assertEquals(6,p.axes().size());
        assertEquals(5,axis(p,"ATTENDANCE").current().numerator());assertEquals(6,axis(p,"ATTENDANCE").current().denominator());
        assertEquals(3,axis(p,"PUNCTUALITY").current().numerator());assertEquals("50.00",axis(p,"PUNCTUALITY").current().percent().toPlainString());
        assertEquals(1,p.summary().absentConsultations());assertEquals(2,p.summary().lateConsultations());
        assertEquals(6,p.summary().completedConsultations());assertEquals(3,p.rules().onTimeGraceMinutes());
    }
    @Test void legacyCompletionDoesNotInventAttendanceAndMissingCoverageSuppressesDelta() {
        appointment(expert,"2026-10-10",9,"COMPLETED");
        jdbc.update("update app.consultation_monitor_state set tracking_started_at=? where id=1",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC));
        var p=snapshot();assertNull(axis(p,"ATTENDANCE").current().percent());
        assertEquals("NO_ATTENDANCE_EVIDENCE",axis(p,"ATTENDANCE").current().status());
        assertEquals(1,p.summary().unknownAttendanceConsultations());assertEquals(0,p.summary().absentConsultations());
        assertEquals("100.00",axis(p,"COMPLETION").current().percent().toPlainString());
        assertNull(axis(p,"ATTENDANCE").changePercentagePoints());
    }
    @Test void onlyCoverageGapsOverlappingAppointmentRemoveNegativeEvidence() {
        appointment(expert,"2026-10-10",9,"PENDING_CONSULTATION");
        jdbc.update("insert into app.consultation_monitor_gaps(starts_at,ends_at) values('2026-10-10T09:10:00+07:00','2026-10-10T09:20:00+07:00')");
        assertEquals(1,snapshot().summary().unknownAttendanceConsultations());
        jdbc.update("delete from app.consultation_monitor_gaps");
        jdbc.update("insert into app.consultation_monitor_gaps(starts_at,ends_at) values('2026-10-12T09:10:00+07:00','2026-10-12T09:20:00+07:00')");
        jdbc.update("update app.consultation_monitor_state set healthy=false where id=1");
        assertEquals(1,snapshot().summary().absentConsultations());
    }
    @Test void ratingsOneToThreeAreInDenominatorAndAverage() {
        for(int i=1;i<=5;i++) {String id=appointment(expert,"2026-10-"+(9+i),9,"COMPLETED");review(id,expert,i);}
        var p=snapshot();assertEquals(5,axis(p,"SATISFACTION").current().denominator());
        assertEquals(2,axis(p,"SATISFACTION").current().numerator());assertEquals("40.00",axis(p,"SATISFACTION").current().percent().toPlainString());
        assertEquals("3.00",p.summary().averageRating().toPlainString());
    }
    @Test void firstScheduledDataBoundsExplicitFilterAndClampsDefault() {
        jdbc.update("delete from app.consultation_requests where id=?",baseline);
        appointment(expert,"2026-10-10",9,"COMPLETED");
        assertThrows(BusinessException.class,()->snapshot());
        var p=service.snapshot(null,null,null,expert,true,2);
        assertEquals(LocalDate.of(2026,10,10),p.period().fromDate());
        assertEquals(LocalDate.of(2026,10,10),p.dataCoverage().earliestScheduledDate());
        assertFalse(p.dataCoverage().previousPeriodComplete());assertNull(axis(p,"COMPLETION").changePercentagePoints());
        assertThrows(BusinessException.class,()->service.snapshot(null,null,null,null,true,3));
    }
    @Test void fixedMorningDeadlineMatchesJavaAtAllBoundaryTimes() {
        String[] times={"00:00:00","02:00:00","07:59:59","08:00:00","18:59:59","19:00:00","19:50:00","23:59:59"};
        for(String time:times) {
            var opened=OffsetDateTime.parse("2026-12-31T"+time+"+07:00");
            var sql=jdbc.queryForObject("select app.consultation_acceptance_deadline_v2(?)",OffsetDateTime.class,opened);
            assertEquals(vn.nutrimom.consultation.domain.ConsultationTiming.acceptanceDeadline(opened).toInstant(),sql.toInstant());
        }
        assertEquals(OffsetDateTime.parse("2027-01-01T08:30:00+07:00").toInstant(),
                vn.nutrimom.consultation.domain.ConsultationTiming.acceptanceDeadline(OffsetDateTime.parse("2026-12-31T19:50:00+07:00")).toInstant());
    }
    @Test void acceptanceUsesStoredDeadlineAndSeparatesClaimedExpertFromSharedQueue() {
        for(int i=0;i<3;i++) {
            String id=appointment(expert,"2026-10-20",9,i==2?"PENDING_EXPERT":"PENDING_CONSULTATION");
            jdbc.update("""
                    update app.consultation_requests set assignment_type='RANDOM',expert_user_id=?,
                      acceptance_opened_at='2026-10-13T19:50:00+07:00',acceptance_deadline_at='2026-10-14T08:30:00+07:00',
                      acceptance_policy_version=2,accepted_at=?::timestamptz,preferred_date='2026-10-20' where id=?
                    """,i==2?null:expert,i==0?"2026-10-14T08:30:00+07:00":i==1?"2026-10-14T08:30:01+07:00":null,id);
        }
        var individual=axis(snapshot(),"TIMELY_ACCEPTANCE").current();assertEquals(2,individual.denominator());assertEquals(1,individual.numerator());
        var team=service.snapshot(LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),null,null,true,2);
        assertEquals(3,axis(team,"TIMELY_ACCEPTANCE").current().denominator());
    }
    @Test void expertReportUsesSameMetricsAndBulkQueriesAndGuardsLimit() {
        String id=appointment(expert,"2026-10-10",9,"COMPLETED");video(id,"2026-10-10T02:15:00Z",true);review(id,expert,3);
        org.mockito.Mockito.clearInvocations(reads);
        var r=service.report(LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),"OBSTETRICS",expert,1,20,false);
        assertEquals(1,r.items().size());
        var row=(AdminExpertPerformanceService.ExpertReportRow)r.items().get(0);
        assertEquals(1,row.summary().lateConsultations());assertEquals(6,row.axes().size());assertEquals("3.00",row.summary().averageRating().toPlainString());
        assertEquals(0,row.axes().get(1).current().numerator());
        long queries=org.mockito.Mockito.mockingDetails(reads).getInvocations().stream().filter(i->i.getMethod().getName().equals("query")).count();
        assertEquals(8,queries);
        assertThrows(BusinessException.class,()->service.report(null,null,null,null,1,101,false));
    }
    @Test void v2AggregationAndReportRemainFastForTenThousandAppointments() {
        int offset=ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH).getTotalSeconds();
        jdbc.update("""
                insert into app.consultation_requests(id,user_id,expert_user_id,specialty,assignment_type,status,
                  scheduled_slot_id,scheduled_date,scheduled_start_time,scheduled_end_time,completed_at,created_at,updated_at,version)
                select gen_random_uuid()::text,?,?,'OBSTETRICS','DIRECT','COMPLETED',gen_random_uuid()::text,date '2026-10-10',
                  time '09:00'-?*interval '1 second',time '10:00'-?*interval '1 second','2026-10-10T03:00:00Z',
                  '2026-09-01T00:00:00Z','2026-09-01T00:00:00Z',0 from generate_series(1,10000)
                """,customer,expert,offset,offset);
        jdbc.execute("ANALYZE app.consultation_requests");jdbc.execute("SET LOCAL statement_timeout='5s'");snapshot();
        long[] timings=new long[20];
        for(int i=0;i<20;i++) {long start=System.nanoTime();var p=snapshot();timings[i]=(System.nanoTime()-start)/1000000;
            assertEquals(10000,p.summary().eligibleConsultations());}
        Arrays.sort(timings);System.out.println("ADMIN_EXPERT_V2_KPI p95Ms="+timings[18]+" appointments=10000");assertTrue(timings[18]<1000);
    }
    vn.nutrimom.consultation.service.ConsultationMonitoringService monitor(Clock monitorClock,
            vn.nutrimom.notification.service.NotificationService notices) {
        return new vn.nutrimom.consultation.service.ConsultationMonitoringService(reads,manager,monitorClock,
                new vn.nutrimom.consultation.video.VideoProperties(),org.mockito.Mockito.mock(vn.nutrimom.consultation.video.LiveKitGateway.class),
                notices,new vn.nutrimom.notification.service.AdminNotificationEvents(reads));
    }
    @Test void overdueJobIsIdempotentAndIgnoresCancelledAndAlreadyAcceptedRequests() {
        var notices=org.mockito.Mockito.mock(vn.nutrimom.notification.service.NotificationService.class);
        String overdue=appointment(expert,"2026-10-20",9,"PENDING_EXPERT");
        String cancelled=appointment(expert,"2026-10-20",9,"CANCELLED");
        String accepted=appointment(expert,"2026-10-20",9,"PENDING_CONSULTATION");
        for(String id:List.of(overdue,cancelled,accepted)) jdbc.update("""
                update app.consultation_requests set assignment_type='RANDOM',expert_user_id=null,
                  acceptance_opened_at=?::timestamptz,acceptance_deadline_at=?::timestamptz,acceptance_policy_version=2 where id=?
                """,OffsetDateTime.ofInstant(NOW.minusSeconds(3600),ZoneOffset.UTC),OffsetDateTime.ofInstant(NOW.minusSeconds(1),ZoneOffset.UTC),id);
        var monitor=monitor(clock,notices);monitor.sweep();monitor.sweep();
        assertEquals(1,jdbc.queryForObject("select count(*) from app.consultation_monitor_events where request_id=? and event_kind='ACCEPTANCE_OVERDUE'",Integer.class,overdue));
        assertEquals(1,jdbc.queryForObject("select count(*) from app.admin_notification_outbox where source_id=? and event_name='ADMIN_ACCEPTANCE_OVERDUE'",Integer.class,overdue));
        for(String id:List.of(cancelled,accepted)) assertEquals(0,jdbc.queryForObject("select count(*) from app.admin_notification_outbox where source_id=?",Integer.class,id));
        org.mockito.Mockito.verifyNoInteractions(notices);
    }
    @Test void exactDeadlineIsNotOverdueAndOldHistoryDoesNotGenerateAlerts() {
        String exact=appointment(expert,"2026-10-20",9,"PENDING_EXPERT");
        String old=appointment(expert,"2026-10-20",9,"PENDING_EXPERT");
        for(String id:List.of(exact,old)) jdbc.update("""
                update app.consultation_requests set assignment_type='RANDOM',expert_user_id=null,
                  acceptance_opened_at='2026-08-01T00:00:00Z',acceptance_deadline_at=? where id=?
                """,id.equals(exact)?OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC):OffsetDateTime.parse("2026-08-02T00:00:00Z"),id);
        monitor(clock,org.mockito.Mockito.mock(vn.nutrimom.notification.service.NotificationService.class)).sweep();
        assertEquals(0,jdbc.queryForObject("select count(*) from app.consultation_monitor_events where request_id in (?,?)",Integer.class,exact,old));
    }
    @Test void arrivalWarningsAreProvisionalAndNotRepeatedByScheduler() {
        var notices=org.mockito.Mockito.mock(vn.nutrimom.notification.service.NotificationService.class);
        String id=appointment(expert,"2026-10-15",11,"PENDING_CONSULTATION");
        jdbc.update("update app.consultation_requests set scheduled_starts_at=?,scheduled_ends_at=? where id=?",
                OffsetDateTime.ofInstant(NOW.minusSeconds(181),ZoneOffset.UTC),OffsetDateTime.ofInstant(NOW.plusSeconds(1620),ZoneOffset.UTC),id);
        var monitor=monitor(clock,notices);monitor.sweep();monitor.sweep();
        org.mockito.Mockito.verify(notices,org.mockito.Mockito.times(1)).publishExpertTimingOnce(org.mockito.ArgumentMatchers.eq(expert),org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq("Chưa ghi nhận bạn vào phòng tư vấn"),org.mockito.ArgumentMatchers.contains("3 phút"));
        assertEquals(2,jdbc.queryForObject("select count(*) from app.consultation_monitor_events where request_id=?",Integer.class,id));
    }
    @Test void providerOutagesAndRestartGapsArePersistedWithoutErasingPastCoverage() {
        var notices=org.mockito.Mockito.mock(vn.nutrimom.notification.service.NotificationService.class);
        monitor(Clock.fixed(NOW.plusSeconds(10),ZoneOffset.UTC),notices).recordHealth(false);
        monitor(Clock.fixed(NOW.plusSeconds(25),ZoneOffset.UTC),notices).recordHealth(true);
        monitor(Clock.fixed(NOW.plusSeconds(40),ZoneOffset.UTC),notices).recordHealth(true);
        monitor(Clock.fixed(NOW.plusSeconds(120),ZoneOffset.UTC),notices).recordHealth(true);
        assertEquals(2,jdbc.queryForObject("select count(*) from app.consultation_monitor_gaps",Integer.class));
        assertEquals(OffsetDateTime.ofInstant(NOW.plusSeconds(120),ZoneOffset.UTC),jdbc.queryForObject("select checked_at from app.consultation_monitor_state where id=1",OffsetDateTime.class));
    }
    @Test void reportCsvUsesSafeEscapingAndSharedSnapshotAndSixMetrics() {
        String id=appointment(expert,"2026-10-10",9,"COMPLETED");video(id,"2026-10-10T02:15:00Z",true);review(id,expert,3);
        jdbc.update("update app.expert_profiles set full_name='=SUM(1,2)' where user_id=?",expert);
        var reports=new vn.nutrimom.admin.service.AdminReportService(new AdminOperationsRepository(reads),clock,service);
        String csv=new String(reports.export("EXPERT_PERFORMANCE",LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),null,null,expert),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"));assertTrue(csv.contains("'=SUM(1,2)"));assertTrue(csv.contains("Tiếp nhận đúng hạn"));
        assertTrue(csv.contains("Khách quay lại"));assertTrue(csv.contains("Asia/Ho_Chi_Minh"));
        assertThrows(BusinessException.class,()->reports.export("EXPERT_PERFORMANCE",null,null,"REAL",null,null));
    }
    @Test void scheduledEndInvalidatesKpiBeforeAttendanceEvidenceSettlesAndDoesNotRescanHistory() {
        String id=appointment(expert,"2026-10-15",10,"COMPLETED");
        jdbc.update("update app.consultation_requests set scheduled_starts_at=?,scheduled_ends_at=? where id=?",
                OffsetDateTime.ofInstant(NOW.minusSeconds(2400),ZoneOffset.UTC),OffsetDateTime.ofInstant(NOW.minusSeconds(400),ZoneOffset.UTC),id);
        var monitor=monitor(clock,org.mockito.Mockito.mock(vn.nutrimom.notification.service.NotificationService.class));
        monitor.sweep();monitor.sweep();monitor.sweep();
        assertEquals(3,jdbc.queryForObject("select count(*) from app.consultation_monitor_events where request_id=?",Integer.class,id));
        assertNotNull(jdbc.queryForObject("select attendance_finalized_at from app.consultation_requests where id=?",OffsetDateTime.class,id));
    }
    @Test void futureLegacySchedulesArePreparedForAlertsWithoutInventingJoins() {
        String id=appointment(expert,"2026-10-16",9,"PENDING_CONSULTATION");
        var monitor=monitor(clock,org.mockito.Mockito.mock(vn.nutrimom.notification.service.NotificationService.class));monitor.sweep();
        assertEquals(OffsetDateTime.parse("2026-10-16T09:00:00+07:00").toInstant(),jdbc.queryForObject(
                "select scheduled_starts_at from app.consultation_requests where id=?",OffsetDateTime.class,id).toInstant());
        assertEquals(0,jdbc.queryForObject("select count(*) from app.consultation_video_sessions where request_id=?",Integer.class,id));
    }
    String account(String role) {
        String id=UUID.randomUUID().toString();owned.add(id);
        jdbc.update("insert into app.users(id,email,display_name,status,created_at,updated_at,version,onboarding_status) values (?,?,'KPI QA','ACTIVE','2026-09-01T00:00:00Z','2026-09-01T00:00:00Z',0,'COMPLETED')",id,id+"@qa.test");
        jdbc.update("insert into app.user_roles(user_id,role) values (?,?)",id,role);return id;
    }
    String expert(String specialty) {
        String id=account("EXPERT");jdbc.update("insert into app.expert_profiles(user_id,full_name,specialty,status,created_at,updated_at) values (?,'KPI Expert',?,'ACTIVE','2026-09-01T00:00:00Z','2026-09-01T00:00:00Z')",id,specialty);return id;
    }
    String appointment(String owner,String date,int hour,String status) {
        String id=UUID.randomUUID().toString();int offset=ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH).getTotalSeconds();
        jdbc.update("""
                insert into app.consultation_requests(id,user_id,expert_user_id,specialty,assignment_type,status,
                  scheduled_slot_id,scheduled_date,scheduled_start_time,scheduled_end_time,completed_at,created_at,updated_at,version)
                values (?,?,?,'OBSTETRICS','DIRECT',?,?,?,?,?,?,'2026-09-01T00:00:00Z','2026-09-01T00:00:00Z',0)
                """,id,customer,owner,status,UUID.randomUUID().toString(),LocalDate.parse(date),
                LocalTime.of(hour,0).minusSeconds(offset),LocalTime.of(hour+1,0).minusSeconds(offset),
                status.equals("COMPLETED")?OffsetDateTime.parse(date+"T10:00:00+07:00"):null);return id;
    }
    void video(String request,String joined,boolean userJoined) {
        String date=jdbc.queryForObject("select scheduled_date::text from app.consultation_requests where id=?",String.class,request);
        var start=OffsetDateTime.parse(date+"T09:00:00+07:00");
        jdbc.update("insert into app.consultation_video_sessions(request_id,room_name,opens_at,closes_at,user_joined_at,expert_joined_at) values (?,?,?,?,?,?)",
                request,"qa-"+request,start.minusMinutes(5),start.plusMinutes(65),userJoined?start:null,joined==null?null:OffsetDateTime.parse(joined));
    }
    void review(String request,String owner,int rating) {
        jdbc.update("insert into app.consultation_reviews(id,request_id,user_id,expert_user_id,rating,created_at) values (?,?,?,?,?,?)",
                UUID.randomUUID().toString(),request,customer,owner,rating,OffsetDateTime.ofInstant(NOW.minusSeconds(1),ZoneOffset.UTC));
    }
}
