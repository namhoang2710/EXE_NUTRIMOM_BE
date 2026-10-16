package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.admin.dto.AdminOperationsDtos.CustomerSnapshot;
import vn.nutrimom.admin.repository.AdminDashboardMetricsRepository;
import vn.nutrimom.admin.service.AdminDashboardMetricsService;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.consultation.domain.*;
import vn.nutrimom.consultation.repository.*;
import vn.nutrimom.payment.domain.*;
import vn.nutrimom.payment.repository.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminDashboardRedesignIntegrationTest {
    static final OffsetDateTime NOW=OffsetDateTime.parse("2026-10-13T05:00:00Z");
    static final AtomicInteger PHONE=new AtomicInteger(650000);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired UserSubscriptionRepository subscriptions;
    @Autowired PaymentOrderRepository payments;
    @Autowired ExpertProfileRepository experts;
    @Autowired AvailabilitySlotRepository slots;
    @Autowired ConsultationRequestRepository requests;
    @Autowired AdminDashboardMetricsRepository metrics;
    @Autowired AdminDashboardMetricsService metricService;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    String admin;
    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(NOW.toInstant());when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(call->Clock.fixed(NOW.toInstant(),call.getArgument(0)));
        // Aggregate fixtures are explicit ledger scenarios; trigger behavior has its own PostgreSQL suite.
        try(var connection=jdbc.getDataSource().getConnection()) {
            if(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"))
                jdbc.execute("alter table app.user_subscriptions disable trigger trg_subscription_revenue");
        }
        admin=user(UserStatus.ACTIVE,UserRole.ADMIN);
    }
    @Test void dashboardUsesUnifiedLifetimeRevenueEligibleCustomersAndDistinctTodayBookers() throws Exception {
        String basic=user(UserStatus.LOCKED,UserRole.USER),premium=user(UserStatus.ACTIVE,UserRole.USER);
        String expired=user(UserStatus.DISABLED,UserRole.USER),future=user(UserStatus.ACTIVE,UserRole.USER);
        user(UserStatus.ACTIVE,UserRole.USER);
        String staff=user(UserStatus.ACTIVE,UserRole.USER,UserRole.ADMIN);
        String expert=user(UserStatus.ACTIVE,UserRole.USER,UserRole.EXPERT);
        sub(basic,PlanTier.PLAN_99K,NOW.minusDays(1),NOW.plusDays(30));
        sub(premium,PlanTier.PLAN_99K,NOW.minusDays(1),NOW.plusDays(20));
        sub(premium,PlanTier.PLAN_399K,NOW.minusDays(1),NOW.plusDays(60));
        sub(expired,PlanTier.PLAN_399K,NOW.minusDays(30),NOW);
        sub(future,PlanTier.PLAN_399K,NOW.plusDays(1),NOW.plusDays(31));
        sub(staff,PlanTier.PLAN_399K,NOW.minusDays(1),NOW.plusDays(30));
        var profile=new ExpertProfileEntity();profile.setUserId(expert);profile.setFullName("QA Expert");
        profile.setSpecialty(Specialty.OBSTETRICS);experts.saveAndFlush(profile);
        appointment(premium,expert,9,ConsultationStatus.PENDING_CONSULTATION);
        appointment(premium,expert,10,ConsultationStatus.COMPLETED);
        appointment(basic,expert,11,ConsultationStatus.PENDING_CONSULTATION);
        appointment(expired,expert,13,ConsultationStatus.CANCELLED);
        moneyFixture(premium);
        var result=mvc.perform(get("/api/v1/admin/dashboard").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.overview.lifetime_real_collected_amount").value(2598))
                .andExpect(jsonPath("$.data.overview.today_booked_appointments").value(3))
                .andExpect(jsonPath("$.data.overview.today_booking_users").value(2))
                .andExpect(jsonPath("$.data.today_schedule.hourly_bookings.length()").value(24))
                .andExpect(jsonPath("$.data.today_schedule.hourly_bookings[9].count").value(1))
                .andExpect(jsonPath("$.data.today_schedule.hourly_bookings[13].count").value(0))
                .andExpect(jsonPath("$.data.customer_snapshot.total_customers").value(5))
                .andExpect(jsonPath("$.data.customer_snapshot.free_users").value(3))
                .andExpect(jsonPath("$.data.customer_snapshot.plan_99k_users").value(1))
                .andExpect(jsonPath("$.data.customer_snapshot.plan_399k_users").value(1))
                .andExpect(jsonPath("$.data.monthly_revenue.items.length()").value(10))
                .andExpect(jsonPath("$.data.monthly_revenue.items[0].amount").value(0))
                .andExpect(jsonPath("$.data.monthly_revenue.items[1].amount").value(200))
                .andExpect(jsonPath("$.data.monthly_revenue.items[9].amount").value(2298))
                .andExpect(jsonPath("$.data.plan_399k_adoption.current_ratio_percent").value(20))
                .andExpect(jsonPath("$.data.plan_399k_adoption.comparison_status").value("INSUFFICIENT_HISTORY"))
                .andReturn();
        var root=json.readTree(result.getResponse().getContentAsString());
        assertEquals(root.at("/data/snapshot_at"),root.at("/data/customer_snapshot/as_of"));
        assertTrue(root.at("/data/plan_399k_adoption").has("previous_ratio_percent"));
        assertTrue(root.at("/data/plan_399k_adoption/previous_ratio_percent").isNull());
        Files.writeString(Path.of("target/admin-redesign-contract.json"),result.getResponse().getContentAsString());
        mvc.perform(get("/api/v1/admin/dashboard").param("fromDate","2026-09-01").param("toDate","2026-09-15")
                .with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overview.lifetime_real_collected_amount").value(2598))
                .andExpect(jsonPath("$.data.customer_snapshot.total_customers").value(5))
                .andExpect(jsonPath("$.data.monthly_revenue.items[9].amount").value(2298));
        assertEquals(0,jdbc.queryForObject("select count(*) from app.admin_customer_metrics",Integer.class));
    }
    @Test void emptyCustomerBaseHasExplicitNullsAndThirtyMissingHistoryPointsWithoutWrites() throws Exception {
        var response=mvc.perform(get("/api/v1/admin/dashboard").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.customer_snapshot.total_customers").value(0))
                .andExpect(jsonPath("$.data.plan_399k_adoption.comparison_status").value("NO_CUSTOMERS"))
                .andExpect(jsonPath("$.data.plan_399k_adoption.daily_series.length()").value(30)).andReturn();
        var data=json.readTree(response.getResponse().getContentAsString()).at("/data");
        assertTrue(data.at("/plan_399k_adoption/current_ratio_percent").isNull());
        assertTrue(data.at("/plan_399k_adoption/daily_series/0").has("as_of"));
        assertTrue(data.at("/plan_399k_adoption/daily_series/0/as_of").isNull());
        assertEquals(data.at("/snapshot_at"),data.at("/plan_399k_adoption/daily_series/29/as_of"));
        assertEquals(0,jdbc.queryForObject("select count(*) from app.admin_customer_metrics",Integer.class));
    }
    @Test void adoptionUsesOnlyLastObservedPreviousMonthEndAndNeverOverwritesNewerHistory() throws Exception {
        for(int i=0;i<4;i++) {String id=user(UserStatus.ACTIVE,UserRole.USER);if(i<2)sub(id,PlanTier.PLAN_399K,NOW.minusDays(1),NOW.plusDays(30));}
        var close=OffsetDateTime.parse("2026-09-30T16:59:00Z");
        metrics.save(new CustomerSnapshot(close.minusMinutes(59),4,2,0,2));
        metrics.save(new CustomerSnapshot(close,4,3,0,1));
        metrics.save(new CustomerSnapshot(close.minusMinutes(30),4,0,0,4));
        metrics.save(new CustomerSnapshot(close.minusDays(1),4,1,0,3));
        metrics.save(new CustomerSnapshot(NOW.plusDays(1),4,0,0,4));
        var response=mvc.perform(get("/api/v1/admin/dashboard").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.plan_399k_adoption.comparison_status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.plan_399k_adoption.current_ratio_percent").value(50))
                .andExpect(jsonPath("$.data.plan_399k_adoption.previous_ratio_percent").value(25))
                .andExpect(jsonPath("$.data.plan_399k_adoption.change_percentage_points").value(25)).andReturn();
        var data=json.readTree(response.getResponse().getContentAsString()).at("/data/plan_399k_adoption");
        assertEquals(close.toInstant(),Instant.parse(data.at("/previous_as_of").stringValue()));
        var sameDay=data.at("/daily_series/16"); // Sep 30, with Sep 14 as day zero.
        assertEquals(data.at("/previous_as_of"),sameDay.at("/as_of"));
        assertEquals(25,sameDay.at("/ratio_percent").intValue());
        Files.writeString(Path.of("target/admin-redesign-history-contract.json"),response.getResponse().getContentAsString());
    }
    @Test void startupObservationCapturesOnlyNowAndExpiryAffectsSubsequentSnapshots() throws Exception {
        String member=user(UserStatus.ACTIVE,UserRole.USER);sub(member,PlanTier.PLAN_399K,NOW.minusDays(1),NOW.plusHours(1));
        metricService.capture();
        assertEquals(1,jdbc.queryForObject("select count(*) from app.admin_customer_metrics",Integer.class));
        when(clock.instant()).thenReturn(NOW.plusHours(2).toInstant());
        metricService.capture();
        var rows=metrics.dailyHistory(LocalDate.of(2026,10,13),NOW.plusHours(2));
        assertEquals(1,rows.size());assertEquals(0,rows.get(0).plan399kUsers());assertEquals(1,rows.get(0).freeUsers());
        mvc.perform(get("/api/v1/admin/dashboard").with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_399k_adoption.comparison_status").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.data.plan_399k_adoption.current_ratio_percent").value(0));
    }
    @Test void lifetimeRevenueLinkMatchesDashboardAndRejectsConflictingOrInvalidRanges() throws Exception {
        String member=user(UserStatus.ACTIVE,UserRole.USER);moneyFixture(member);
        var response=mvc.perform(get("/api/v1/admin/revenue/summary").param("source","REAL").param("range","ALL_TIME")
                .with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("ALL_TIME"))
                .andExpect(jsonPath("$.data.summary.collected_amount").value(600))
                .andExpect(jsonPath("$.data.summary.paid_orders").value(3)).andReturn();
        assertTrue(json.readTree(response.getResponse().getContentAsString()).at("/data/period").isNull());
        Files.writeString(Path.of("target/admin-redesign-revenue-contract.json"),response.getResponse().getContentAsString());
        mvc.perform(get("/api/v1/admin/payment-orders").param("range","ALL_TIME").param("source","REAL")
                .param("status","PAID").param("dateBasis","PAID").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_items").value(3));
        for(String endpoint:List.of("/revenue/summary","/payment-orders")) {
            mvc.perform(get("/api/v1/admin"+endpoint).param("range","ALL_TIME").param("fromDate","2026-10-01")
                    .with(token(admin,"ADMIN"))).andExpect(status().is(422));
            mvc.perform(get("/api/v1/admin"+endpoint).param("range","typo").with(token(admin,"ADMIN")))
                    .andExpect(status().is(422));
            mvc.perform(get("/api/v1/admin"+endpoint).param("range","ALL_TIME").with(token(member,"ADMIN")))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void comparisonsUseVietnamMonthBoundariesAndThirtyFullDaysIncludingEmptyDays() throws Exception {
        String member=user(UserStatus.ACTIVE,UserRole.USER);
        String prior=user(UserStatus.ACTIVE,UserRole.USER);
        String expert=user(UserStatus.ACTIVE,UserRole.EXPERT);
        jdbc.update("update app.users set created_at=? where id=?",OffsetDateTime.parse("2026-09-30T17:00:00Z"),member);
        jdbc.update("update app.users set created_at=? where id=?",OffsetDateTime.parse("2026-09-30T16:59:59Z"),prior);
        var profile=new ExpertProfileEntity();profile.setUserId(expert);profile.setFullName("QA Expert");
        profile.setSpecialty(Specialty.OBSTETRICS);experts.saveAndFlush(profile);
        appointment(member,expert,8,ConsultationStatus.PENDING_CONSULTATION);
        appointment(member,expert,9,ConsultationStatus.COMPLETED,LocalDate.of(2026,9,13));
        appointment(member,expert,10,ConsultationStatus.PENDING_CONSULTATION,LocalDate.of(2026,10,12));
        appointment(member,expert,11,ConsultationStatus.COMPLETED,LocalDate.of(2026,9,12));
        appointment(member,expert,12,ConsultationStatus.CANCELLED,LocalDate.of(2026,10,12));
        moneyFixture(member);
        sub(member,PlanTier.PLAN_399K,NOW.minusDays(1),NOW.plusDays(30));
        metrics.save(new CustomerSnapshot(OffsetDateTime.parse("2026-09-30T16:59:00Z"),2,2,0,0));
        var response=mvc.perform(get("/api/v1/admin/dashboard").param("fromDate","2026-09-01").param("toDate","2026-09-05")
                .with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metric_comparisons.previous_month").value("2026-09"))
                .andExpect(jsonPath("$.data.metric_comparisons.booking_baseline_days").value(30))
                .andExpect(jsonPath("$.data.metric_comparisons.revenue.current_value").value(2598))
                .andExpect(jsonPath("$.data.metric_comparisons.revenue.previous_value").value(300))
                .andExpect(jsonPath("$.data.metric_comparisons.customers.current_value").value(1))
                .andExpect(jsonPath("$.data.metric_comparisons.customers.previous_value").value(1))
                .andExpect(jsonPath("$.data.metric_comparisons.appointments.current_value").value(1))
                .andExpect(jsonPath("$.data.metric_comparisons.plan_399k.current_value").value(50))
                .andExpect(jsonPath("$.data.metric_comparisons.plan_399k.previous_value").value(0)).andReturn();
        double density=json.readTree(response.getResponse().getContentAsString())
                .at("/data/metric_comparisons/appointments/previous_value").doubleValue();
        assertEquals(2.0/30,density,1e-10);
    }
    @Test void januaryComparisonRetainsPriorYearRevenueAndDecemberRegistrations() {
        String member=user(UserStatus.ACTIVE,UserRole.USER),prior=user(UserStatus.ACTIVE,UserRole.USER);
        jdbc.update("update app.users set created_at=? where id=?",OffsetDateTime.parse("2026-12-31T17:00:00Z"),member);
        jdbc.update("update app.users set created_at=? where id=?",OffsetDateTime.parse("2026-12-31T16:59:59Z"),prior);
        pay(member,820001,100,PaymentSource.REAL,PaymentOrderStatus.PAID,"2026-12-31T16:59:59Z");
        pay(member,820002,200,PaymentSource.REAL,PaymentOrderStatus.PAID,"2026-12-31T17:00:00Z");
        var at=OffsetDateTime.parse("2027-01-14T05:00:00Z");
        var cash=metrics.collections(at);var customers=metrics.customerMetrics(at);
        assertEquals(300,cash.lifetimeAmount());assertEquals(100,cash.previousLifetimeAmount());
        assertEquals(1,cash.monthly().items().size());assertEquals(200,cash.monthly().items().get(0).amount());
        assertEquals(1,customers.currentMonthNew());assertEquals(1,customers.previousMonthNew());
    }
    String user(UserStatus status,UserRole...roles) {
        var row=new UserEntity();row.setPhone("+849"+PHONE.incrementAndGet());row.setDisplayName("QA Customer");
        row.setStatus(status);row.setRoles(Set.of(roles));return users.saveAndFlush(row).getId();
    }
    void sub(String user,PlanTier tier,OffsetDateTime start,OffsetDateTime end) {
        subscriptions.saveAndFlush(new UserSubscriptionEntity(user,tier,start,end));
    }
    void moneyFixture(String user) {
        pay(user,810001,100,PaymentSource.REAL,PaymentOrderStatus.PAID,"2025-12-30T00:00:00Z");
        pay(user,810002,200,PaymentSource.REAL,PaymentOrderStatus.PAID,"2026-01-31T17:00:00Z");
        pay(user,810003,300,PaymentSource.REAL,PaymentOrderStatus.PAID,"2026-09-30T17:00:00Z");
        pay(user,810004,999,PaymentSource.DEMO,PaymentOrderStatus.PAID,"2026-10-01T00:00:00Z");
        pay(user,810005,999,PaymentSource.LEGACY_UNKNOWN,PaymentOrderStatus.PAID,"2026-10-01T00:00:00Z");
        pay(user,810006,999,PaymentSource.REAL,PaymentOrderStatus.PAID,null);
        pay(user,810007,999,PaymentSource.REAL,PaymentOrderStatus.PENDING,null);
        pay(user,810008,400,PaymentSource.REAL,PaymentOrderStatus.PAID,"2026-10-14T00:00:00Z");
    }
    void pay(String user,long code,int amount,PaymentSource source,PaymentOrderStatus status,String paidAt) {
        var row=new PaymentOrderEntity(code,user,PlanTier.PLAN_99K,amount,"QA");row.setSource(source);row.setStatus(status);
        row.setPaidAt(paidAt==null?null:OffsetDateTime.parse(paidAt));payments.saveAndFlush(row);
    }
    void appointment(String user,String expert,int hour,ConsultationStatus status) {
        appointment(user,expert,hour,status,LocalDate.of(2026,10,13));
    }
    void appointment(String user,String expert,int hour,ConsultationStatus status,LocalDate day) {
        var slot=new AvailabilitySlotEntity();slot.setExpertUserId(expert);slot.setSlotDate(day);
        slot.setStartTime(LocalTime.of(hour,0));slot.setEndTime(LocalTime.of(hour,30));slot.setStatus(SlotStatus.BOOKED);
        slots.saveAndFlush(slot);
        var row=new ConsultationRequestEntity();row.setUserId(user);row.setExpertUserId(expert);row.setStatus(status);
        row.setAssignmentType(AssignmentType.DIRECT);row.setSpecialty(Specialty.OBSTETRICS);row.captureScheduledSlot(slot);
        if(status!=ConsultationStatus.CANCELLED)row.setSlotId(slot.getId());
        if(status==ConsultationStatus.COMPLETED)row.setCompletedAt(NOW.minusHours(1));requests.saveAndFlush(row);
    }
    RequestPostProcessor token(String subject,String role) {
        return jwt().jwt(t->t.subject(subject).claim("roles",List.of(role))).authorities(new SimpleGrantedAuthority("ROLE_"+role));
    }
}
