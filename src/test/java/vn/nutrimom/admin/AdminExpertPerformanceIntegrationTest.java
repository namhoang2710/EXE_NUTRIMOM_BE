package vn.nutrimom.admin;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.admin.repository.AdminExpertPerformanceRepository;
import vn.nutrimom.admin.repository.AdminExpertPerformanceRepository.Counts;
import vn.nutrimom.admin.repository.AdminExpertPerformanceRepository.ExtendedCounts;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminExpertPerformanceIntegrationTest {
    static final String PATH="/api/v1/admin/experts/performance";
    static final Instant NOW=Instant.parse("2026-10-14T17:30:00Z"); // Already Oct 15 in Vietnam.
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @MockitoBean AdminExpertPerformanceRepository repository;
    @MockitoBean vn.nutrimom.admin.repository.ExpertScheduleKpiRepository schedules;
    @MockitoBean Clock clock;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    String admin;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(call->Clock.fixed(NOW,call.getArgument(0)));
        when(repository.experts(any(),any(),any())).thenReturn(new AdminExpertPerformanceRepository.Experts(3,2,null));
        when(repository.counts(any(),any(),any())).thenReturn(List.of(new Counts("CURRENT",10,8,9,8,7,5,4,22),
                new Counts("PREVIOUS",10,7,10,9,6,5,3,20)));
        when(repository.extendedCounts(any(),any(),any())).thenReturn(List.of(
                new ExtendedCounts("CURRENT",10,7,2,6,3),new ExtendedCounts("PREVIOUS",5,5,0,4,0)));
        admin=account(UserStatus.ACTIVE,UserRole.ADMIN);
    }
    @Test void v3UsesScheduleAttendanceAndServerEntryActionContractAndReports() throws Exception {
        when(schedules.window(any())).thenReturn(new vn.nutrimom.admin.repository.ExpertScheduleKpiRepository.Window(LocalDate.of(2026,9,1),LocalDate.of(2026,9,1),null));
        when(schedules.counts(any(),eq(false))).thenReturn(List.of(new vn.nutrimom.admin.repository.ExpertScheduleKpiRepository.Schedule("CURRENT",null,744,24,1,0)));
        when(repository.countsV3(any(),any(),any(),eq(false))).thenReturn(List.of());
        when(repository.extendedCounts(any(),any(),any(),eq(true),eq(true))).thenReturn(List.of());
        when(repository.reportExperts(any(),any(),any(),anyInt(),anyInt())).thenReturn(List.of());
        mvc.perform(get(PATH).param("calculationVersion","3").param("includeExtendedMetrics","true").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.calculation_version").value(3))
                .andExpect(jsonPath("$.data.period.to_date").value("2026-10-31"))
                .andExpect(jsonPath("$.data.period.previous_from_date").value("2026-09-01"))
                .andExpect(jsonPath("$.data.rules.punctuality_evidence").value("SERVER_ENTRY_ACTION"))
                .andExpect(jsonPath("$.data.schedule_summary.standard_slots").value(744))
                .andExpect(jsonPath("$.data.schedule_summary.off_slots").value(24))
                .andExpect(jsonPath("$.data.axes[0].current.percent").value(96.77))
                .andExpect(jsonPath("$.data.data_coverage.filter_min_date").value("2026-09-01"))
                .andExpect(jsonPath("$.data.data_coverage.previous_period_complete").value(true))
                .andExpect(jsonPath("$.data.data_coverage",org.hamcrest.Matchers.hasKey("checkin_tracking_started_at")));
        mvc.perform(get("/api/v1/admin/reports/EXPERT_PERFORMANCE").param("calculationVersion","3").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.snapshot.calculation_version").value(3));
        mvc.perform(get("/api/v1/admin/reports/EXPERT_PERFORMANCE/export").param("calculationVersion","3").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type","text/csv;charset=UTF-8"));
        mvc.perform(get("/api/v1/admin/reports/REVENUE").param("calculationVersion","3").with(token(admin,"ADMIN"))).andExpect(status().is(422));
        mvc.perform(get(PATH).param("calculationVersion","3").param("fromDate","2026-08-31").param("toDate","2026-09-30").with(token(admin,"ADMIN"))).andExpect(status().is(422));
    }
    @Test void v2ContractIncludesCoverageVerifiedArrivalRulesAndUnknownCounts() throws Exception {
        when(repository.dataWindow(any())).thenReturn(new AdminExpertPerformanceRepository.DataWindow(LocalDate.of(2026,9,1),OffsetDateTime.parse("2026-10-01T00:00:00Z")));
        when(repository.countsV2(any(),any(),any(),eq(false))).thenReturn(List.of(
                new AdminExpertPerformanceRepository.PresenceCounts("CURRENT",null,new Counts("CURRENT",10,8,6,6,5,5,4,22),8,2,1,2)));
        when(repository.extendedCounts(any(),any(),any(),eq(true))).thenReturn(List.of());
        mvc.perform(get(PATH).param("calculationVersion","2").param("includeExtendedMetrics","true").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.calculation_version").value(2))
                .andExpect(jsonPath("$.data.axes.length()").value(6)).andExpect(jsonPath("$.data.rules.on_time_grace_minutes").value(3))
                .andExpect(jsonPath("$.data.rules.attendance_evidence").value("VERIFIED_FIRST_ROOM_JOIN"))
                .andExpect(jsonPath("$.data.axes[0].current.status").value("PARTIAL_DATA"))
                .andExpect(jsonPath("$.data.axes[0].current.percent").value(75))
                .andExpect(jsonPath("$.data.summary.absent_consultations").value(2))
                .andExpect(jsonPath("$.data.summary.late_consultations").value(1))
                .andExpect(jsonPath("$.data.summary.unknown_attendance_consultations").value(2))
                .andExpect(jsonPath("$.data.data_coverage.earliest_scheduled_date").value("2026-09-01"));
        mvc.perform(get(PATH).param("calculationVersion","9").with(token(admin,"ADMIN"))).andExpect(status().is(422));
    }
    @Test void v2KeepsNullableCoverageFieldsInJson() throws Exception {
        when(repository.dataWindow(any())).thenReturn(new AdminExpertPerformanceRepository.DataWindow(null,null));
        when(repository.countsV2(any(),any(),any(),eq(false))).thenReturn(List.of());
        when(repository.extendedCounts(any(),any(),any(),eq(true))).thenReturn(List.of());
        mvc.perform(get(PATH).param("calculationVersion","2").param("includeExtendedMetrics","true").with(token(admin,"ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data_coverage",org.hamcrest.Matchers.hasKey("earliest_scheduled_date")))
                .andExpect(jsonPath("$.data.data_coverage",org.hamcrest.Matchers.hasKey("tracking_started_at")))
                .andExpect(jsonPath("$.data.data_coverage.tracking_started_at").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.data_coverage.comparison_status").value("NO_SCHEDULED_DATA"));
    }
    @Test void expertReportRoutesUseV2AndRequireAdminAndValidPagination() throws Exception {
        when(repository.dataWindow(any())).thenReturn(new AdminExpertPerformanceRepository.DataWindow(LocalDate.of(2026,9,1),null));
        when(repository.countsV2(any(),any(),any(),eq(false))).thenReturn(List.of());
        when(repository.extendedCounts(any(),any(),any(),eq(true))).thenReturn(List.of());
        when(repository.reportExperts(any(),any(),any(),anyInt(),anyInt())).thenReturn(List.of());
        String path="/api/v1/admin/reports/EXPERT_PERFORMANCE";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        String user=account(UserStatus.ACTIVE,UserRole.USER);
        mvc.perform(get(path).with(token(user,"USER"))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.snapshot.calculation_version").value(2))
                .andExpect(jsonPath("$.data.summary.snapshot.data_coverage",org.hamcrest.Matchers.hasKey("tracking_started_at")))
                .andExpect(jsonPath("$.data.type").value("EXPERT_PERFORMANCE"));
        mvc.perform(get(path).param("pageSize","101").with(token(admin,"ADMIN"))).andExpect(status().is(422));
        mvc.perform(get(path).param("source","REAL").with(token(admin,"ADMIN"))).andExpect(status().is(422));
        mvc.perform(get(path+"/export").with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type","text/csv;charset=UTF-8"));
        when(repository.experts(any(),any(),any())).thenReturn(new AdminExpertPerformanceRepository.Experts(10001,10001,null));
        mvc.perform(get(path+"/export").with(token(admin,"ADMIN"))).andExpect(status().is(422));
    }
    @Test void adminGetsSnakeCaseBoundedSnapshotAndVietnamDefaultPeriod() throws Exception {
        mvc.perform(get(PATH).with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.data.period.from_date").value("2026-10-01"))
                .andExpect(jsonPath("$.data.period.to_date").value("2026-10-15"))
                .andExpect(jsonPath("$.data.axes.length()").value(4))
                .andExpect(jsonPath("$.data.axes[0].current.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.axes[0].current.missing_evidence").value(0))
                .andExpect(jsonPath("$.data.axes[0].label").value("Chuyên cần"))
                .andExpect(jsonPath("$.data.axes[0].current.percent").value(80))
                .andExpect(jsonPath("$.data.axes[1].current.percent").value(70))
                .andExpect(jsonPath("$.data.rules.on_time_grace_minutes").value(0))
                .andExpect(jsonPath("$.data.rules.attendance_evidence").value("COMPLETED_OR_VERIFIED_ROOM_JOIN"))
                .andExpect(jsonPath("$.data.axes[2].current.percent").value(80))
                .andExpect(jsonPath("$.data.summary.active_experts").value(2))
                .andExpect(jsonPath("$.data.summary.average_rating").value(4.4))
                .andExpect(jsonPath("$.data.summary.departures_status").value("NOT_TRACKED"))
                .andExpect(jsonPath("$.data.realtime_revision").value("0"));
        verify(repository,times(1)).counts(isNull(),isNull(),any());
    }
    @Test void permissionsRequireBothJwtAdminAndCurrentActiveAdminAccount() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for(UserRole role:List.of(UserRole.USER,UserRole.EXPERT)) {
            String user=account(UserStatus.ACTIVE,role);
            mvc.perform(get(PATH).with(token(user,role.name()))).andExpect(status().isForbidden());
            mvc.perform(get(PATH).with(token(user,"ADMIN"))).andExpect(status().isForbidden());
        }
        String locked=account(UserStatus.LOCKED,UserRole.ADMIN);
        mvc.perform(get(PATH).with(token(locked,"ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }
    @Test void rejectsMalformedCrossFieldFutureAndUnboundedFiltersBeforeRead() throws Exception {
        for(String query:List.of("fromDate=2026-10-01","fromDate=2026-10-16&toDate=2026-10-16",
                "fromDate=2026-10-02&toDate=2026-10-01","fromDate=2025-01-01&toDate=2026-10-01",
                "expertUserId=not-a-uuid","specialty=invalid")) {
            mvc.perform(get(PATH+"?"+query).with(token(admin,"ADMIN"))).andExpect(status().is(422));
        }
        mvc.perform(get(PATH+"?fromDate=bad&toDate=2026-10-01").with(token(admin,"ADMIN"))).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
    @Test void selectedExpertOutsideScopeReturns404AndUnknownDataIsNull() throws Exception {
        String expert=UUID.randomUUID().toString();
        when(repository.experts(any(),eq(expert),any())).thenReturn(new AdminExpertPerformanceRepository.Experts(0,0,null));
        mvc.perform(get(PATH).param("expertUserId",expert).with(token(admin,"ADMIN"))).andExpect(status().isNotFound());
        verify(repository,never()).counts(any(),any(),any());
        when(repository.counts(any(),any(),any())).thenReturn(List.of());
        mvc.perform(get(PATH).with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.axes[0].current.status").value("NO_ELIGIBLE_CONSULTATIONS"))
                .andExpect(jsonPath("$.data.axes[0].current.percent").isEmpty())
                .andExpect(jsonPath("$.data.summary.average_rating").isEmpty());
    }
    @Test void extendedMetricsAreOptInAndDescribeBusinessHoursAndCoverage() throws Exception {
        mvc.perform(get(PATH).with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.axes.length()").value(4));
        verify(repository,never()).extendedCounts(any(),any(),any());
        mvc.perform(get(PATH).param("includeExtendedMetrics","true").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.axes.length()").value(6))
                .andExpect(jsonPath("$.data.axes[4].key").value("TIMELY_ACCEPTANCE"))
                .andExpect(jsonPath("$.data.axes[4].current.percent").value(70))
                .andExpect(jsonPath("$.data.axes[4].current.status").value("PARTIAL_DATA"))
                .andExpect(jsonPath("$.data.axes[4].current.missing_evidence").value(2))
                .andExpect(jsonPath("$.data.axes[4].change_percentage_points").isEmpty())
                .andExpect(jsonPath("$.data.axes[5].key").value("RETURNING_CUSTOMERS"))
                .andExpect(jsonPath("$.data.axes[5].current.percent").value(50))
                .andExpect(jsonPath("$.data.axes[5].change_percentage_points").value(50))
                .andExpect(jsonPath("$.data.rules.acceptance_sla_minutes").value(30))
                .andExpect(jsonPath("$.data.rules.acceptance_opening_time").value("08:00"))
                .andExpect(jsonPath("$.data.rules.acceptance_closing_time").value("20:00"))
                .andExpect(jsonPath("$.data.rules.acceptance_timezone").value("Asia/Ho_Chi_Minh"));
        verify(repository,times(1)).extendedCounts(isNull(),isNull(),any());
    }
    @Test void appliedFiltersReachSqlAndResponseScopeWithoutExtraRequests() throws Exception {
        String selected=UUID.randomUUID().toString();
        var profile=new vn.nutrimom.admin.dto.AdminExpertPerformanceDtos.SelectedExpert(selected,"QA","HEALTH","ACTIVE","ACTIVE",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC));
        when(repository.experts(eq("HEALTH"),eq(selected),any())).thenReturn(new AdminExpertPerformanceRepository.Experts(1,1,profile));
        mvc.perform(get(PATH).param("fromDate","2026-10-05").param("toDate","2026-10-10")
                .param("specialty","HEALTH").param("expertUserId",selected).param("includeExtendedMetrics","true")
                .with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period.from_date").value("2026-10-05"))
                .andExpect(jsonPath("$.data.period.to_date").value("2026-10-10"))
                .andExpect(jsonPath("$.data.period.previous_from_date").value("2026-09-29"))
                .andExpect(jsonPath("$.data.specialty").value("HEALTH"))
                .andExpect(jsonPath("$.data.expert.user_id").value(selected));
        var parameters=org.mockito.ArgumentCaptor.forClass(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class);
        verify(repository,times(1)).counts(eq("HEALTH"),eq(selected),parameters.capture());
        org.junit.jupiter.api.Assertions.assertEquals(LocalDate.of(2026,10,5),parameters.getValue().getValue("fromDate"));
        org.junit.jupiter.api.Assertions.assertEquals(LocalDate.of(2026,10,10),parameters.getValue().getValue("toDate"));
        org.junit.jupiter.api.Assertions.assertEquals(30,parameters.getValue().getValue("acceptanceMinutes"));
        verify(repository,times(1)).extendedCounts(eq("HEALTH"),eq(selected),any());
    }
    @Test void extendedUnknownAndNoDenominatorRemainNullNotZero() throws Exception {
        when(repository.extendedCounts(any(),any(),any())).thenReturn(List.of(new ExtendedCounts("CURRENT",0,0,2,0,0)));
        mvc.perform(get(PATH).param("includeExtendedMetrics","true").with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.axes[4].current.percent").isEmpty())
                .andExpect(jsonPath("$.data.axes[4].current.status").value("NO_ACCEPTANCE_EVIDENCE"))
                .andExpect(jsonPath("$.data.axes[5].current.percent").isEmpty())
                .andExpect(jsonPath("$.data.axes[5].current.status").value("NO_COMPLETED_CUSTOMERS"));
        mvc.perform(get(PATH).param("includeExtendedMetrics","invalid").with(token(admin,"ADMIN"))).andExpect(status().isBadRequest());
    }
    String account(UserStatus status,UserRole role) {
        var user=new UserEntity();user.setEmail(UUID.randomUUID()+"@qa.test");user.setDisplayName("KPI fixture");
        user.setStatus(status);user.setRoles(Set.of(role));return users.saveAndFlush(user).getId();
    }
    RequestPostProcessor token(String id,String role) {return jwt().jwt(j->j.subject(id).expiresAt(NOW.plusSeconds(600)))
            .authorities(new SimpleGrantedAuthority("ROLE_"+role));}
}
