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
class AdminExpertPerformancePostgresTest {
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
    @BeforeEach void setup() {
        assertEquals(URL,source.getUrl());customer=account("USER");expert=expert("OBSTETRICS");
    }
    @AfterEach void cleanup() {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(s->{for(String user:owned) {
            jdbc.update("delete from app.consultation_reviews where user_id=? or expert_user_id=?",user,user);
            jdbc.update("delete from app.consultation_video_sessions where request_id in (select id from app.consultation_requests where user_id=? or expert_user_id=?)",user,user);
            jdbc.update("delete from app.consultation_requests where user_id=? or expert_user_id=?",user,user);
            jdbc.update("delete from app.consultation_slots where expert_user_id=?",user);
            jdbc.update("delete from app.expert_profiles where user_id=?",user);
            jdbc.update("delete from app.user_roles where user_id=?",user);
            jdbc.update("delete from app.users where id=?",user);
        }});
    }
    Performance snapshot(String selected) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        return tx.execute(s->service.snapshot(LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),null,selected));
    }
    Axis axis(Performance p,String key) {return p.axes().stream().filter(a->a.key().equals(key)).findFirst().orElseThrow();}
    @Test void ratesUseAllDueAppointmentsWithoutPerExpertRequests() {
        String a=appointment(expert,"2026-10-10",9,"COMPLETED");video(a,"2026-10-10T02:05:00Z",true);review(a,expert,5);
        String b=appointment(expert,"2026-10-11",9,"COMPLETED");video(b,"2026-10-11T02:06:00Z",true);review(b,expert,2);
        String c=appointment(expert,"2026-10-12",9,"PENDING_CONSULTATION");video(c,null,true);
        appointment(expert,"2026-10-13",9,"PENDING_CONSULTATION");
        String second=expert("HEALTH");String d=appointment(second,"2026-10-14",9,"COMPLETED");video(d,"2026-10-14T01:59:00Z",false);review(d,second,4);
        org.mockito.Mockito.clearInvocations(reads);var p=snapshot(null);
        assertEquals(5,p.summary().eligibleConsultations());assertEquals(3,p.summary().completedConsultations());
        assertEquals(4,p.summary().observedConsultations());assertEquals("3.67",p.summary().averageRating().toPlainString());
        assertEquals("60.00",axis(p,"ATTENDANCE").current().percent().toPlainString());
        assertEquals("60.00",axis(p,"PUNCTUALITY").current().percent().toPlainString());
        assertEquals("66.67",axis(p,"SATISFACTION").current().percent().toPlainString());
        assertEquals("60.00",axis(p,"COMPLETION").current().percent().toPlainString());
        assertEquals("AVAILABLE",axis(p,"ATTENDANCE").current().status());
        assertEquals(0,axis(p,"ATTENDANCE").current().missingEvidence());
        long queries=org.mockito.Mockito.mockingDetails(reads).getInvocations().stream().filter(i->i.getMethod().getName().equals("query")).count();
        assertEquals(3,queries);assertNull(p.summary().departuresCount());assertEquals("NOT_TRACKED",p.summary().departuresStatus());
    }
    @Test void overdueWithoutAttendanceIsZeroAndCompletedLegacyAppointmentsCount() {
        String id=appointment(expert,"2026-10-10",9,"PENDING_CONSULTATION");
        var pending=snapshot(expert);assertEquals("0.00",axis(pending,"ATTENDANCE").current().percent().toPlainString());
        assertEquals("AVAILABLE",axis(pending,"ATTENDANCE").current().status());
        assertEquals("0.00",axis(pending,"PUNCTUALITY").current().percent().toPlainString());
        video(id,null,false);
        jdbc.update("update app.consultation_video_sessions set user_joined_at=opens_at where request_id=?",id);
        assertEquals("0.00",axis(snapshot(expert),"ATTENDANCE").current().percent().toPlainString());
        appointment(expert,"2026-10-11",9,"COMPLETED"); // No video row at all.
        var legacy=snapshot(expert);
        assertEquals("50.00",axis(legacy,"ATTENDANCE").current().percent().toPlainString());
        assertEquals("50.00",axis(legacy,"PUNCTUALITY").current().percent().toPlainString());
    }
    @Test void completionDeadlineIncludesBoundaryButExcludesLateAndUnfinishedAppointments() {
        String exact=appointment(expert,"2026-10-10",9,"COMPLETED");
        String late=appointment(expert,"2026-10-11",9,"COMPLETED");
        jdbc.update("update app.consultation_requests set completed_at='2026-10-11T10:00:01+07:00' where id=?",late);
        String present=appointment(expert,"2026-10-12",9,"PENDING_CONSULTATION");video(present,"2026-10-12T02:00:00Z",true);
        appointment(expert,"2026-10-13",9,"PENDING_CONSULTATION");
        var p=snapshot(expert);
        assertEquals("75.00",axis(p,"ATTENDANCE").current().percent().toPlainString());
        assertEquals("25.00",axis(p,"PUNCTUALITY").current().percent().toPlainString());
        assertEquals("50.00",axis(p,"COMPLETION").current().percent().toPlainString());
        // Starting on time does not excuse running past the consultation deadline.
        video(late,"2026-10-11T02:00:00Z",true);
        assertEquals("25.00",axis(snapshot(expert),"PUNCTUALITY").current().percent().toPlainString());
        assertNotNull(exact);
    }
    @Test void futureCancelledUnassignedAndInvalidWindowsDoNotReduceScores() {
        String good=appointment(expert,"2026-10-10",9,"COMPLETED");video(good,"2026-10-10T02:00:00Z",true);
        appointment(expert,"2026-10-10",9,"CANCELLED");appointment(expert,"2026-10-15",14,"PENDING_CONSULTATION");
        appointment(expert,"2026-10-20",9,"PENDING_CONSULTATION");
        String unscheduled=appointment(expert,"2026-10-10",9,"PENDING_CONSULTATION");
        jdbc.update("update app.consultation_requests set scheduled_slot_id=null,scheduled_date=null,scheduled_start_time=null,scheduled_end_time=null where id=?",unscheduled);
        String unassigned=appointment(expert,"2026-10-10",9,"PENDING_EXPERT");
        jdbc.update("update app.consultation_requests set expert_user_id=null where id=?",unassigned);
        assertEquals(1,snapshot(expert).summary().eligibleConsultations());assertEquals("100.00",axis(snapshot(expert),"COMPLETION").current().percent().toPlainString());
    }
    @Test void periodComparisonFiltersSpecialtyAndPreservesInactiveExpertHistory() {
        String prior=appointment(expert,"2026-09-30",9,"COMPLETED");video(prior,"2026-09-30T02:06:00Z",true);
        jdbc.update("update app.consultation_requests set completed_at='2026-09-30T10:01:00+07:00' where id=?",prior);
        String current=appointment(expert,"2026-10-01",9,"COMPLETED");video(current,"2026-10-01T02:05:00Z",true);
        jdbc.update("update app.expert_profiles set status='INACTIVE' where user_id=?",expert);
        var p=snapshot(expert);assertEquals("100.00",axis(p,"PUNCTUALITY").changePercentagePoints().toPlainString());
        assertEquals(0,p.summary().activeExperts());assertEquals("INACTIVE",p.expert().expertStatus());
        assertNull(p.summary().departuresCount());assertEquals(LocalDate.of(2026,9,16),p.period().previousFromDate());
        var health=service.snapshot(LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),"HEALTH",null);
        assertEquals(0,health.summary().eligibleConsultations());
        assertThrows(BusinessException.class,()->service.snapshot(null,null,"HEALTH",expert));
    }
    @Test void zeroReviewsAndFutureReviewsCannotBecomeFakeSatisfaction() {
        String request=appointment(expert,"2026-10-10",9,"COMPLETED");review(request,expert,5);
        jdbc.update("update app.consultation_reviews set created_at=? where request_id=?",OffsetDateTime.ofInstant(NOW.plusSeconds(1),ZoneOffset.UTC),request);
        var p=snapshot(expert);assertNull(axis(p,"SATISFACTION").current().percent());assertNull(p.summary().averageRating());
        assertEquals(1,axis(p,"SATISFACTION").current().missingEvidence());
    }
    @Test void emptyAndInvalidSelectionsStayExplicitAndBounded() {
        assertTrue(snapshot(expert).axes().stream().allMatch(a->a.current().percent()==null));
        assertThrows(BusinessException.class,()->snapshot(UUID.randomUUID().toString()));
        assertThrows(BusinessException.class,()->service.snapshot(null,null,"HEALTH' OR 1=1--",null));
        assertThrows(BusinessException.class,()->service.snapshot(LocalDate.of(2026,10,16),LocalDate.of(2026,10,16),null,null));
        assertThrows(BusinessException.class,()->service.snapshot(LocalDate.of(2025,1,1),LocalDate.of(2026,10,1),null,null));
    }
    @Test void reviewAndVideoRevisionsAreCommitOnlyAndNoopUpdatesAreIgnored() throws Exception {
        String request=appointment(expert,"2026-10-10",9,"COMPLETED");video(request,null,true);
        long before=Long.parseLong(revisions.dashboardRevision());
        try(var c=source.getConnection()) {
            c.setAutoCommit(false);try(var s=c.prepareStatement("update app.consultation_video_sessions set expert_joined_at=opens_at where request_id=?")) {s.setString(1,request);s.executeUpdate();}
            assertEquals(before,Long.parseLong(revisions.dashboardRevision()));c.rollback();
        }
        assertEquals(before,Long.parseLong(revisions.dashboardRevision()));
        jdbc.update("update app.consultation_video_sessions set expert_joined_at=opens_at where request_id=?",request);
        assertEquals(before+1,Long.parseLong(revisions.dashboardRevision()));
        jdbc.update("update app.consultation_video_sessions set expert_joined_at=expert_joined_at where request_id=?",request);
        assertEquals(before+1,Long.parseLong(revisions.dashboardRevision()));
        review(request,expert,5);assertEquals(before+2,Long.parseLong(revisions.dashboardRevision()));
    }
    @Test void aggregateRemainsBoundedWithTenThousandAppointments() {
        int offset=ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH).getTotalSeconds();
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(s->{
            jdbc.update("""
                    insert into app.consultation_requests(id,user_id,expert_user_id,specialty,assignment_type,status,
                      scheduled_slot_id,scheduled_date,scheduled_start_time,scheduled_end_time,created_at,updated_at,version)
                    select gen_random_uuid()::text,?,?,'OBSTETRICS','DIRECT','PENDING_CONSULTATION',gen_random_uuid()::text,
                      date '2026-10-10',time '09:00'-?*interval '1 second',time '10:00'-?*interval '1 second',
                      '2026-09-01T00:00:00Z','2026-09-01T00:00:00Z',0 from generate_series(1,10000)
                    """,customer,expert,offset,offset);
            jdbc.execute("ANALYZE app.users,app.expert_profiles,app.consultation_requests");
            snapshot(expert);long[] timings=new long[20];
            for(int i=0;i<timings.length;i++) {
                long start=System.nanoTime();var p=snapshot(expert);timings[i]=(System.nanoTime()-start)/1_000_000;
                assertEquals(10000,p.summary().eligibleConsultations());assertEquals("0.00",axis(p,"ATTENDANCE").current().percent().toPlainString());
            }
            Arrays.sort(timings);System.out.println("ADMIN_EXPERT_KPI p95Ms="+timings[18]+" appointments=10000 queries=3");
            assertTrue(timings[18]<1000,"Aggregate p95 exceeded 1s: "+timings[18]);s.setRollbackOnly();
        });
    }
    @Test void snapshotDataAndRevisionStayConsistentAcrossConcurrentReviewCommit() throws Exception {
        String request=appointment(expert,"2026-10-10",9,"COMPLETED");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            String before=tx.execute(s->{
                var first=snapshot(expert);assertNull(axis(first,"SATISFACTION").current().percent());
                try {executor.submit(()->review(request,expert,5)).get(5,java.util.concurrent.TimeUnit.SECONDS);}
                catch(Exception failure) {throw new AssertionError(failure);}
                var same=snapshot(expert);assertEquals(first.realtimeRevision(),same.realtimeRevision());
                assertNull(axis(same,"SATISFACTION").current().percent());return first.realtimeRevision();
            });
            var fresh=snapshot(expert);assertEquals("100.00",axis(fresh,"SATISFACTION").current().percent().toPlainString());
            assertEquals(Long.parseLong(before)+1,Long.parseLong(fresh.realtimeRevision()));
        } finally {executor.shutdownNow();}
    }
    @Test void legacyLiveSlotFallbackAndSnapshotScheduleUseTheSameVietnamClock() {
        String request=appointment(expert,"2026-10-10",9,"COMPLETED");video(request,"2026-10-10T02:05:00Z",true);
        String slot=UUID.randomUUID().toString();int offset=ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH).getTotalSeconds();
        jdbc.update("""
                insert into app.consultation_slots(id,expert_user_id,slot_date,start_time,end_time,status,created_at,updated_at)
                values (?,?,'2026-10-10',?,?,'BOOKED','2026-09-01T00:00:00Z','2026-09-01T00:00:00Z')
                """,slot,expert,LocalTime.of(9,0).minusSeconds(offset),LocalTime.of(10,0).minusSeconds(offset));
        jdbc.update("update app.consultation_requests set slot_id=?,scheduled_slot_id=null,scheduled_date=null,scheduled_start_time=null,scheduled_end_time=null where id=?",slot,request);
        assertEquals("100.00",axis(snapshot(expert),"PUNCTUALITY").current().percent().toPlainString());
    }
    Performance extended(String selected,LocalDate from,LocalDate to,String specialty) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);tx.setTimeout(10);
        return tx.execute(s->service.snapshot(from,to,specialty,selected,true));
    }
    Performance extended(String selected) {return extended(selected,LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),null);}
    @Test void acceptanceDeadlinePausesOvernightAndPreservesExactBoundaries() {
        String[][] cases={
            {"2026-10-14T02:00:00+07:00","2026-10-14T08:30:00+07:00"},
            {"2026-10-14T19:50:00+07:00","2026-10-15T08:20:00+07:00"},
            {"2026-10-14T19:30:00+07:00","2026-10-14T20:00:00+07:00"},
            {"2026-10-14T20:00:00+07:00","2026-10-15T08:30:00+07:00"},
            {"2026-12-31T23:00:00+07:00","2027-01-01T08:30:00+07:00"},
            {"2026-10-14T08:00:00.500+07:00","2026-10-14T08:30:00.500+07:00"}
        };
        for(var c:cases) {
            var actual=jdbc.queryForObject("select app.consultation_acceptance_deadline(?,30)",OffsetDateTime.class,OffsetDateTime.parse(c[0]));
            assertEquals(OffsetDateTime.parse(c[1]).toInstant(),actual.toInstant(),c[0]);
        }
        assertEquals(OffsetDateTime.parse("2026-10-16T19:50:10+07:00").toInstant(),jdbc.queryForObject(
                "select app.consultation_acceptance_deadline(?,1440)",OffsetDateTime.class,OffsetDateTime.parse("2026-10-14T19:50:10+07:00")).toInstant());
    }
    @Test void lateNightPendingIsNotOverdueBeforeServiceWindowAndBecomesEligibleAtDeadline() {
        queued(null,"2026-10-15T02:00:00+07:00",null,"PENDING_EXPERT");
        var early=new AdminExpertPerformanceService(new AdminExpertPerformanceRepository(reads),new AdminOperationsRepository(reads),revisions,
                Clock.fixed(Instant.parse("2026-10-15T01:20:00Z"),ZoneOffset.UTC));
        var pending=early.snapshot(LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),null,null,true);
        assertNull(axis(pending,"TIMELY_ACCEPTANCE").current().percent());
        assertEquals("NO_ELIGIBLE_REQUESTS",axis(pending,"TIMELY_ACCEPTANCE").current().status());
        var due=axis(extended(null),"TIMELY_ACCEPTANCE").current();
        assertEquals(1,due.denominator());assertEquals("0.00",due.percent().toPlainString());
        assertNull(axis(extended(expert),"TIMELY_ACCEPTANCE").current().percent()); // Not assigned to this expert.
    }
    @Test void acceptanceUsesQueueCohortNotAppointmentDateAndDistinguishesMissingLegacyEvidence() {
        queued(expert,"2026-10-14T02:00:00+07:00","2026-10-14T08:30:00+07:00","PENDING_CONSULTATION");
        queued(expert,"2026-10-14T08:00:00+07:00","2026-10-14T08:30:01+07:00","PENDING_CONSULTATION");
        queued(expert,"2026-10-14T19:50:00+07:00","2026-10-15T08:20:00+07:00","PENDING_CONSULTATION");
        queued(null,"2026-10-15T10:00:00+07:00",null,"PENDING_EXPERT");
        queued(null,"2026-10-15T11:50:00+07:00",null,"PENDING_EXPERT"); // Still within deadline.
        queued(expert,"2026-10-14T09:00:00+07:00","2026-10-14T09:01:00+07:00","CANCELLED");
        queued(expert,"2026-09-30T09:00:00+07:00","2026-09-30T09:01:00+07:00","PENDING_CONSULTATION");
        String legacy=appointment(expert,"2026-10-30",9,"PENDING_CONSULTATION");
        jdbc.update("update app.consultation_requests set assignment_type='RANDOM',created_at='2026-10-14T09:00:00+07:00' where id=?",legacy);
        appointment(expert,"2026-10-10",9,"COMPLETED"); // DIRECT auto booking must not inflate acceptance.
        org.mockito.Mockito.clearInvocations(reads);var p=extended(null);
        var rate=axis(p,"TIMELY_ACCEPTANCE");assertEquals(4,rate.current().denominator());assertEquals(2,rate.current().numerator());
        assertEquals("50.00",rate.current().percent().toPlainString());assertEquals("PARTIAL_DATA",rate.current().status());
        assertEquals(1,rate.current().missingEvidence());assertNull(rate.changePercentagePoints());assertEquals("100.00",rate.previous().percent().toPlainString());
        assertEquals(4,org.mockito.Mockito.mockingDetails(reads).getInvocations().stream().filter(i->i.getMethod().getName().equals("query")).count());
        var personal=axis(extended(expert),"TIMELY_ACCEPTANCE").current();assertEquals(3,personal.denominator());assertEquals("66.67",personal.percent().toPlainString());
        var health=extended(null,LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),"HEALTH");
        assertNull(axis(health,"TIMELY_ACCEPTANCE").current().percent());
    }
    @Test void acceptanceSpecialtyUsesCurrentProfileForClaimsAndQueueSpecialtyForUnassignedRequests() {
        queued(expert,"2026-10-14T09:00:00+07:00","2026-10-14T09:01:00+07:00","PENDING_CONSULTATION");
        queued(null,"2026-10-14T09:00:00+07:00",null,"PENDING_EXPERT");
        jdbc.update("update app.expert_profiles set specialty='HEALTH' where user_id=?",expert);
        var health=axis(extended(null,LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),"HEALTH"),"TIMELY_ACCEPTANCE").current();
        assertEquals(1,health.denominator());assertEquals("100.00",health.percent().toPlainString());
        var pool=axis(extended(null,LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),"OBSTETRICS"),"TIMELY_ACCEPTANCE").current();
        assertEquals(1,pool.denominator());assertEquals("0.00",pool.percent().toPlainString());
    }
    @Test void returningCustomersRequireSameExpertEarlierCompletedVisitAndDeduplicatePeople() {
        String other=expert("HEALTH");
        appointment(expert,"2026-09-20",9,"COMPLETED");
        appointment(expert,"2026-10-10",9,"COMPLETED");appointment(expert,"2026-10-11",9,"COMPLETED");
        appointment(other,"2026-10-12",9,"COMPLETED"); // Same person counted once for team, no earlier HEALTH visit.
        for(int i=0;i<4;i++) {
            String id=account("USER");
            String current=appointment(expert,i==0?"2026-10-10":"2026-10-14",i==3?14:9,"COMPLETED");
            jdbc.update("update app.consultation_requests set user_id=? where id=?",id,current);
            if(i==1) {String prior=appointment(other,"2026-09-21",9,"COMPLETED");jdbc.update("update app.consultation_requests set user_id=? where id=?",id,prior);}
            if(i==2) {String prior=appointment(expert,"2026-09-21",9,"CANCELLED");jdbc.update("update app.consultation_requests set user_id=? where id=?",id,prior);}
            // i==3 has a completed_at earlier than its own slot; it must not count itself as a prior visit.
        }
        var team=axis(extended(null),"RETURNING_CUSTOMERS").current();
        assertEquals(5,team.denominator());assertEquals(1,team.numerator());assertEquals("20.00",team.percent().toPlainString());
        var health=axis(extended(null,LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),"HEALTH"),"RETURNING_CUSTOMERS").current();
        assertEquals(1,health.denominator());assertEquals("0.00",health.percent().toPlainString());
        var day=axis(extended(expert,LocalDate.of(2026,10,10),LocalDate.of(2026,10,10),null),"RETURNING_CUSTOMERS").current();
        assertEquals(2,day.denominator());assertEquals("50.00",day.percent().toPlainString());
    }
    @Test void appliedFiltersChangeRealSqlResultsAcrossPeriodsSpecialtiesAndExperts() {
        String other=expert("HEALTH");
        appointment(expert,"2026-10-02",9,"COMPLETED");appointment(expert,"2026-10-03",9,"PENDING_CONSULTATION");
        appointment(other,"2026-10-10",9,"COMPLETED");
        assertEquals(3,extended(null).summary().eligibleConsultations());
        var selected=extended(expert);assertEquals(2,selected.summary().eligibleConsultations());assertEquals("50.00",axis(selected,"COMPLETION").current().percent().toPlainString());
        var health=extended(null,LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),"HEALTH");assertEquals(1,health.summary().eligibleConsultations());
        var day=extended(expert,LocalDate.of(2026,10,2),LocalDate.of(2026,10,2),null);assertEquals(1,day.summary().eligibleConsultations());assertEquals("100.00",axis(day,"COMPLETION").current().percent().toPlainString());
        var empty=extended(null,LocalDate.of(2026,10,11),LocalDate.of(2026,10,15),null);assertEquals(0,empty.summary().eligibleConsultations());
        assertNull(axis(empty,"COMPLETION").current().percent());
    }
    @Test void extendedAggregateRemainsBoundedWithTenThousandAppointments() {
        int offset=ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH).getTotalSeconds();
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(s->{
            jdbc.execute("SET LOCAL statement_timeout='5s'");
            jdbc.update("""
                    insert into app.consultation_requests(id,user_id,expert_user_id,specialty,assignment_type,status,
                      scheduled_slot_id,scheduled_date,scheduled_start_time,scheduled_end_time,completed_at,
                      acceptance_opened_at,accepted_at,created_at,updated_at,version)
                    select gen_random_uuid()::text,?,?,'OBSTETRICS','RANDOM','COMPLETED',gen_random_uuid()::text,
                      date '2026-10-10',time '09:00'-?*interval '1 second',time '10:00'-?*interval '1 second',
                      '2026-10-10T10:00:00+07:00','2026-10-09T19:50:00+07:00','2026-10-10T08:20:00+07:00',
                      '2026-10-09T19:50:00+07:00','2026-10-10T08:20:00+07:00',0 from generate_series(1,10000)
                    """,customer,expert,offset,offset);
            jdbc.execute("ANALYZE app.consultation_requests");extended(expert);long[] timings=new long[20];
            for(int i=0;i<timings.length;i++) {long start=System.nanoTime();var p=extended(expert);timings[i]=(System.nanoTime()-start)/1_000_000;
                assertEquals(6,p.axes().size());assertEquals("100.00",axis(p,"TIMELY_ACCEPTANCE").current().percent().toPlainString());
                assertEquals(1,axis(p,"RETURNING_CUSTOMERS").current().denominator());assertEquals("0.00",axis(p,"RETURNING_CUSTOMERS").current().percent().toPlainString());}
            Arrays.sort(timings);System.out.println("ADMIN_EXPERT_EXTENDED_KPI p95Ms="+timings[18]+" appointments=10000 queries=4");
            assertTrue(timings[18]<1000,"Extended p95 exceeded 1s: "+timings[18]);s.setRollbackOnly();
        });
    }
    String queued(String owner,String opened,String accepted,String status) {
        String id=appointment(expert,"2026-10-30",9,status);
        jdbc.update("update app.consultation_requests set assignment_type='RANDOM',expert_user_id=?,acceptance_opened_at=?,accepted_at=?,created_at=? where id=?",
                owner,OffsetDateTime.parse(opened),accepted==null?null:OffsetDateTime.parse(accepted),OffsetDateTime.parse(opened),id);
        return id;
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
