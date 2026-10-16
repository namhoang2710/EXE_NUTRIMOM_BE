package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import vn.nutrimom.admin.dto.AdminExpertPerformanceDtos.Axis;
import vn.nutrimom.admin.repository.*;
import vn.nutrimom.admin.service.*;

@EnabledIfSystemProperty(named="admin.audit.postgres.test",matches="true")
class AdminExpertPerformanceV3PostgresTest {
    final AdminExpertPerformanceV2PostgresTest f=new AdminExpertPerformanceV2PostgresTest();
    final ExpertScheduleKpiService service=new ExpertScheduleKpiService(new ExpertScheduleKpiRepository(f.reads),
            new AdminExpertPerformanceRepository(f.reads),new AdminOperationsRepository(f.reads),f.revisions,f.clock);
    @BeforeAll static void migrateQaOnly() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_qa",AdminExpertPerformanceV2PostgresTest.URL);
        org.flywaydb.core.Flyway.configure().dataSource(AdminExpertPerformanceV2PostgresTest.URL,"adminqa","")
                .schemas("app").defaultSchema("app").locations("classpath:db/migration").load().migrate();
    }
    @BeforeEach void setup() {f.setup();f.jdbc.update("update app.expert_checkin_policy set started_at='2026-10-01T00:00:00+07:00' where id=1");}
    @AfterEach void cleanup() {f.cleanup();}
    ExpertScheduleKpiService.Snapshot snapshot() {return service.snapshot(null,null,null,f.expert,true);}
    Axis axis(ExpertScheduleKpiService.Snapshot p,String key) {return p.axes().stream().filter(a->a.key().equals(key)).findFirst().orElseThrow();}
    void dayOff(String expert,String date) {
        f.jdbc.update("insert into app.expert_day_offs(id,expert_user_id,off_date,created_at) values(?,?,?::date,now())",UUID.randomUUID().toString(),expert,date);
    }
    void slot(String expert,String date,int hour,int minute,String status) {
        int offset=ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH).getTotalSeconds();
        f.jdbc.update("insert into app.consultation_slots(id,expert_user_id,slot_date,start_time,end_time,status,created_at,updated_at) values(?,?,?::date,?,?,?,?,now())",
                UUID.randomUUID().toString(),expert,date,LocalTime.of(hour,minute).minusSeconds(offset),LocalTime.of(hour,minute).plusMinutes(30).minusSeconds(offset),status,OffsetDateTime.ofInstant(AdminExpertPerformanceV2PostgresTest.NOW,ZoneOffset.UTC));
    }
    @Test void scheduleHas31DaysIncludesDay31DeduplicatesClosuresAndKeepsBookedWork() {
        dayOff(f.expert,"2026-10-31");slot(f.expert,"2026-10-31",9,0,"CLOSED");slot(f.expert,"2026-10-31",10,0,"BOOKED");
        slot(f.expert,"2026-10-30",9,0,"CLOSED");slot(f.expert,"2026-10-30",9,30,"CLOSED");
        var p=snapshot();assertEquals(3,p.calculationVersion());assertEquals(744,p.scheduleSummary().standardSlots());
        assertEquals(25,p.scheduleSummary().offSlots());assertEquals(719,p.scheduleSummary().availableSlots());
        assertEquals("96.64",axis(p,"ATTENDANCE").current().percent().toPlainString());
        assertEquals(720,axis(p,"ATTENDANCE").previous().denominator());
        assertEquals(2,p.scheduleSummary().closedSlots());assertEquals(1,p.scheduleSummary().dayOffDays());
    }
    @Test void specialtyAndTeamUseWeightedSlotsAndNewExpertOnlyContributesAfterCreation() {
        String b=f.expert("OBSTETRICS");String c=f.expert("HEALTH");
        f.jdbc.update("update app.expert_profiles set created_at='2026-10-15T00:00:00+07:00' where user_id=?",b);
        dayOff(f.expert,"2026-10-31");dayOff(b,"2026-10-31");
        var department=service.snapshot(null,null,"OBSTETRICS",null,true);
        assertEquals(744+17*24,department.scheduleSummary().standardSlots());assertEquals(48,department.scheduleSummary().offSlots());
        var team=service.snapshot(null,null,null,null,true);
        assertEquals(department.scheduleSummary().standardSlots()+744,team.scheduleSummary().standardSlots());
        assertEquals(48,team.scheduleSummary().offSlots());assertEquals("100.00",axis(service.snapshot(null,null,"HEALTH",c,true),"ATTENDANCE").current().percent().toPlainString());
    }
    @Test void februaryHas28Or29DaysAndNoAppointmentsAreNeededForAttendance() {
        f.jdbc.update("update app.expert_profiles set created_at='2020-01-01T00:00:00Z' where user_id=?",f.expert);
        for(int year:new int[]{2026,2028}) {
            var clock=Clock.fixed(Instant.parse(year+"-02-15T05:00:00Z"),ZoneOffset.UTC);
            var s=new ExpertScheduleKpiService(new ExpertScheduleKpiRepository(f.reads),new AdminExpertPerformanceRepository(f.reads),new AdminOperationsRepository(f.reads),f.revisions,clock);
            var p=s.snapshot(null,null,null,f.expert,true);assertEquals((year==2028?29:28)*24,p.scheduleSummary().standardSlots());
            assertEquals("100.00",axis(p,"ATTENDANCE").current().percent().toPlainString());
        }
    }
    @Test void completionAndLiveKitCannotMakeLateOrMissingCheckinsOnTime() {
        String[] times={"08:55:00","09:00:00","09:03:00","09:03:01","09:15:00"};
        for(int i=0;i<times.length;i++) {
            String date="2026-10-"+(10+i);String id=f.appointment(f.expert,date,9,"COMPLETED");f.canonical(id,date);
            f.jdbc.update("insert into app.expert_consultation_checkins values(?,?,?::timestamptz)",id,f.expert,date+"T"+times[i]+"+07:00");
        }
        String missing=f.appointment(f.expert,"2026-10-09",9,"COMPLETED");f.canonical(missing,"2026-10-09");f.video(missing,"2026-10-09T02:00:00Z",true);
        var p=snapshot();assertEquals(6,p.checkinSummary().eligibleConsultations());assertEquals(3,p.checkinSummary().onTimeConsultations());
        assertEquals(2,p.checkinSummary().lateConsultations());assertEquals(1,p.checkinSummary().notCheckedInConsultations());
        assertEquals("50.00",axis(p,"PUNCTUALITY").current().percent().toPlainString());assertEquals("100.00",axis(p,"COMPLETION").current().percent().toPlainString());
    }
    @Test void legacyWithoutCheckinIsUnknownRatherThanAbsentAndCurrentSessionCountsAfterGrace() {
        String old=f.appointment(f.expert,"2026-10-01",9,"COMPLETED");f.canonical(old,"2026-10-01");
        f.jdbc.update("update app.expert_checkin_policy set started_at='2026-10-02T00:00:00Z' where id=1");
        String current=f.appointment(f.expert,"2026-10-15",11,"PENDING_CONSULTATION");f.canonical(current,"2026-10-15");
        f.jdbc.update("update app.consultation_requests set scheduled_starts_at='2026-10-15T11:55:00+07:00',scheduled_ends_at='2026-10-15T12:25:00+07:00' where id=?",current);
        var p=snapshot();assertEquals(1,p.checkinSummary().unknownConsultations());assertEquals(1,p.checkinSummary().notCheckedInConsultations());
        assertEquals(1,axis(p,"PUNCTUALITY").current().missingEvidence());assertEquals(1,axis(p,"PUNCTUALITY").current().denominator());
        assertNull(axis(p,"PUNCTUALITY").changePercentagePoints());assertEquals(1,p.summary().eligibleConsultations());
    }
    @Test void reportUsesBulkQueriesMatchesSnapshotAndCsvEscapesExpertNames() {
        dayOff(f.expert,"2026-10-31");f.jdbc.update("update app.expert_profiles set full_name='=SUM(1,2)' where user_id=?",f.expert);
        var report=service.report(null,null,null,f.expert,1,20,false);
        var row=(ExpertScheduleKpiService.ExpertRow)report.items().get(0);
        assertEquals(snapshot().scheduleSummary(),row.scheduleSummary());assertEquals(6,row.axes().size());
        org.mockito.Mockito.clearInvocations(f.reads);service.report(null,null,null,f.expert,1,20,false);
        long queries=org.mockito.Mockito.mockingDetails(f.reads).getInvocations().stream().filter(i->i.getMethod().getName().equals("query")).count();assertEquals(10,queries);
        var exports=new AdminReportService(new AdminOperationsRepository(f.reads),f.clock,f.service,service);
        String csv=new String(exports.export("EXPERT_PERFORMANCE",null,null,null,null,f.expert,3),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("'=SUM(1,2)"));assertTrue(csv.contains("Khung chuẩn 30 phút"));assertTrue(csv.contains("Khách quay lại"));
    }
    @Test void missingExpertsAndInvalidSpecialtyAreRejectedWithoutBroadeningScope() {
        assertThrows(vn.nutrimom.common.exception.BusinessException.class,()->service.snapshot(null,null,"INVALID",null,true));
        assertThrows(vn.nutrimom.common.exception.BusinessException.class,()->service.snapshot(null,null,"HEALTH",f.expert,true));
        var empty=service.snapshot(null,null,"HEALTH",null,true);assertNull(axis(empty,"ATTENDANCE").current().percent());
    }
    @Test void firstPartialMonthKeepsRealScheduleRatesButDoesNotClaimAFullMonthTrend() {
        f.jdbc.update("update app.expert_profiles set created_at='2026-09-22T00:00:00+07:00'");
        slot(f.expert,"2026-09-23",9,0,"CLOSED");slot(f.expert,"2026-09-23",9,30,"CLOSED");slot(f.expert,"2026-09-23",10,0,"CLOSED");
        dayOff(f.expert,"2026-10-31");
        var p=snapshot();var attendance=axis(p,"ATTENDANCE");
        assertEquals(744,attendance.current().denominator());assertEquals(720,attendance.current().numerator());
        assertEquals(216,attendance.previous().denominator());assertEquals(213,attendance.previous().numerator());
        assertEquals("96.77",attendance.current().percent().toPlainString());assertEquals("98.61",attendance.previous().percent().toPlainString());
        assertEquals(LocalDate.of(2026,9,22),p.dataCoverage().filterMinDate());
        assertEquals(LocalDate.of(2026,9,22),p.dataCoverage().previousEffectiveFromDate());
        assertTrue(p.dataCoverage().currentPeriodComplete());assertFalse(p.dataCoverage().previousPeriodComplete());
        assertEquals("PARTIAL_PREVIOUS_PERIOD",p.dataCoverage().comparisonStatus());
        for(var a:p.axes())assertNull(a.changePercentagePoints());
        assertThrows(vn.nutrimom.common.exception.BusinessException.class,()->service.snapshot(LocalDate.of(2026,9,21),LocalDate.of(2026,9,30),null,f.expert,true));
        assertThrows(vn.nutrimom.common.exception.BusinessException.class,()->service.report(LocalDate.of(2026,9,21),LocalDate.of(2026,9,30),null,null,1,20,false));
        var first=service.snapshot(LocalDate.of(2026,9,22),LocalDate.of(2026,9,30),null,f.expert,true);
        assertEquals("NO_PREVIOUS_PERIOD_DATA",first.dataCoverage().comparisonStatus());
    }
    @Test void ratingGrowthIsNotRateGrowthAndEveryReportRowHasItsOwnCoverage() {
        f.jdbc.update("delete from app.consultation_requests where id=?",f.baseline);
        for(int i=0;i<3;i++){String id=f.appointment(f.expert,"2026-09-"+(10+i),9,"COMPLETED");f.review(id,f.expert,5);}
        for(int i=0;i<12;i++){String id=f.appointment(f.expert,"2026-10-"+(i+1<10?"0":"")+(i+1),9,"COMPLETED");f.review(id,f.expert,i<10?5:3);}
        var rated=axis(snapshot(),"SATISFACTION");
        assertEquals(10,rated.current().numerator());assertEquals(12,rated.current().denominator());
        assertEquals("83.33",rated.current().percent().toPlainString());assertEquals("100.00",rated.previous().percent().toPlainString());
        assertEquals("-16.67",rated.changePercentagePoints().toPlainString());
        String newcomer=f.expert("OBSTETRICS");f.jdbc.update("update app.expert_profiles set created_at='2026-09-22T00:00:00+07:00' where user_id=?",newcomer);
        var report=service.report(null,null,"OBSTETRICS",null,1,100,false);
        var row=(ExpertScheduleKpiService.ExpertRow)report.items().stream().filter(r->((ExpertScheduleKpiService.ExpertRow)r).expert().userId().equals(newcomer)).findFirst().orElseThrow();
        assertTrue(((ExpertScheduleKpiService.Snapshot)report.summary().get("snapshot")).dataCoverage().previousPeriodComplete());
        assertFalse(row.dataCoverage().previousPeriodComplete());for(var a:row.axes())assertNull(a.changePercentagePoints());
    }
    @Test void entryDeadlineMaturesOnceAndScheduleEditsAndCheckinInvalidateRealtime() {
        String id=f.appointment(f.expert,"2026-10-15",11,"PENDING_CONSULTATION");
        f.jdbc.update("update app.consultation_requests set scheduled_starts_at='2026-10-15T11:55:00+07:00',scheduled_ends_at='2026-10-15T12:25:00+07:00' where id=?",id);
        f.jdbc.execute("set constraints all immediate");String revision=f.revisions.dashboardRevision();
        dayOff(f.expert,"2026-10-31");assertNotEquals(revision,f.revisions.dashboardRevision());
        var notices=org.mockito.Mockito.mock(vn.nutrimom.notification.service.NotificationService.class);
        var monitor=f.monitor(f.clock,notices);monitor.sweep();String matured=f.revisions.dashboardRevision();monitor.sweep();
        assertEquals(matured,f.revisions.dashboardRevision());
        assertEquals(1,f.jdbc.queryForObject("select count(*) from app.consultation_monitor_events where request_id=? and event_kind='CHECKIN_KPI_DUE'",Integer.class,id));
        assertNotNull(f.jdbc.queryForObject("select entry_kpi_finalized_at from app.consultation_requests where id=?",OffsetDateTime.class,id));
        f.jdbc.update("insert into app.expert_consultation_checkins values(?,?,?::timestamptz)",id,f.expert,"2026-10-15T11:55:00+07:00");
        assertNotEquals(matured,f.revisions.dashboardRevision());
        org.mockito.Mockito.clearInvocations(notices);monitor.sweep();org.mockito.Mockito.verifyNoInteractions(notices);
        var request=org.mockito.Mockito.mock(vn.nutrimom.consultation.domain.ConsultationRequestEntity.class);
        org.mockito.Mockito.when(request.getId()).thenReturn(id);
        monitor.verifiedExpertJoin(request,AdminExpertPerformanceV2PostgresTest.NOW);
        org.mockito.Mockito.verifyNoInteractions(notices);
    }
    @Test void tenThousandAppointmentsUseConstantQueryCount() {
        f.jdbc.update("""
                insert into app.consultation_requests(id,user_id,expert_user_id,specialty,assignment_type,status,
                    scheduled_slot_id,scheduled_date,scheduled_start_time,scheduled_end_time,scheduled_starts_at,scheduled_ends_at,completed_at,created_at,updated_at)
                select gen_random_uuid()::text,?,?,'OBSTETRICS','DIRECT','COMPLETED',gen_random_uuid()::text,date '2026-10-10',time '09:00',time '09:30',
                    '2026-10-10T09:00:00+07:00'::timestamptz,'2026-10-10T09:30:00+07:00'::timestamptz,
                    '2026-10-10T09:30:00+07:00'::timestamptz,'2026-09-01T00:00:00Z'::timestamptz,now() from generate_series(1,10000)
                """,f.customer,f.expert);
        org.mockito.Mockito.clearInvocations(f.reads);long started=System.nanoTime();
        var p=snapshot();assertEquals(10000,p.checkinSummary().notCheckedInConsultations());assertEquals(10000,p.summary().completedConsultations());
        assertEquals(6,org.mockito.Mockito.mockingDetails(f.reads).getInvocations().stream().filter(i->i.getMethod().getName().equals("query")).count());
        assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<3000,"KPI query exceeded the 3s QA budget");
    }
}
