package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
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
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.consultation.domain.*;
import vn.nutrimom.consultation.repository.*;
import vn.nutrimom.contact.domain.*;
import vn.nutrimom.contact.repository.ContactRequestRepository;
import vn.nutrimom.payment.domain.*;
import vn.nutrimom.payment.repository.*;
import vn.nutrimom.notification.repository.NotificationRepository;
import vn.nutrimom.notification.service.NotificationService;
import vn.nutrimom.notification.domain.NotificationType;
import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminOperationsIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-12T05:00:00Z");
    static final AtomicInteger PHONE=new AtomicInteger(500000);
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ExpertProfileRepository experts;
    @Autowired ConsultationRequestRepository requests;
    @Autowired vn.nutrimom.consultation.service.ConsultationRequestService consultationService;
    @Autowired AvailabilitySlotRepository slots;
    @Autowired PaymentOrderRepository payments;
    @Autowired UserSubscriptionRepository subscriptions;
    @Autowired ContactRequestRepository contacts;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationService notificationService;
    @Autowired vn.nutrimom.admin.service.AdminOperationsService operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean Clock clock;
    // These external clients are unrelated to admin read/payment tests; never contact providers.
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    String admin;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(call->Clock.fixed(NOW,call.getArgument(0)));
        admin=user("Admin",UserRole.ADMIN);
    }
    @Test void everyAdminEndpointRequiresBothJwtRoleAndCurrentActiveAccount() throws Exception {
        List<String> endpoints=List.of("/dashboard","/appointments","/experts/page","/revenue/summary",
                "/payment-orders","/subscriptions","/reports/OPERATIONS","/reports/OPERATIONS/export");
        String regular=user("Member",UserRole.USER);
        for(String endpoint:endpoints) {
            mvc.perform(get("/api/v1/admin"+endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin"+endpoint).with(token(regular,"USER"))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin"+endpoint).with(token(regular,"ADMIN"))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin"+endpoint).with(token(admin,"ADMIN"))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/admin/users").with(token(admin,"ADMIN"))).andExpect(status().isOk());
        var account=users.findById(admin).orElseThrow();account.setStatus(UserStatus.LOCKED);users.saveAndFlush(account);
        mvc.perform(get("/api/v1/admin/dashboard").with(token(admin,"ADMIN"))).andExpect(status().isForbidden());
        account.setStatus(UserStatus.ACTIVE);account.getRoles().clear();account.getRoles().add(UserRole.USER);users.saveAndFlush(account);
        mvc.perform(get("/api/v1/admin/reports/REVENUE/export").with(token(admin,"ADMIN"))).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
        mvc.perform(get(java.net.URI.create("/api/v1/a%64min/dashboard")).with(token(admin,"ADMIN")))
                .andExpect(status().isForbidden());
    }
    @Test void validatesBoundsDatesEnumsIdentifiersAndReportsBeforeReading() throws Exception {
        for(String[] query:List.of(new String[]{"page","0"},new String[]{"page","1000001"},new String[]{"pageSize","101"},
                new String[]{"q","x".repeat(101)},new String[]{"status","ALL"},new String[]{"dateBasis","SQL"},
                new String[]{"expertUserId","1-1-1-1-1"})) {
            mvc.perform(get("/api/v1/admin/appointments").param(query[0],query[1]).with(token(admin,"ADMIN")))
                    .andExpect(status().is(422));
        }
        mvc.perform(get("/api/v1/admin/dashboard").param("fromDate","2026-10-01").with(token(admin,"ADMIN")))
                .andExpect(status().is(422));
        mvc.perform(get("/api/v1/admin/dashboard").param("fromDate","2025-01-01").param("toDate","2026-10-12").with(token(admin,"ADMIN")))
                .andExpect(status().is(422));
        mvc.perform(get("/api/v1/admin/dashboard").param("fromDate","2026-10-01").param("toDate","2026-10-13").with(token(admin,"ADMIN")))
                .andExpect(status().is(422));
        mvc.perform(get("/api/v1/admin/dashboard").param("fromDate","2026-02-30").param("toDate","2026-10-12").with(token(admin,"ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/payment-orders/9223372036854775808").with(token(admin,"ADMIN")))
                .andExpect(status().is(422));
        mvc.perform(get("/api/v1/admin/reports/HEALTH/export").with(token(admin,"ADMIN")))
                .andExpect(status().is(422));
        mvc.perform(get("/api/v1/admin/subscriptions").param("expiringWithinDays","31").with(token(admin,"ADMIN")))
                .andExpect(status().is(422));
    }
    @Test void appointmentsIncludeUnassignedFutureAndOverdueWithoutClinicalNotes() throws Exception {
        String member=user("Mother",UserRole.USER),expert=expert();
        var pending=request(member,null,null,ConsultationStatus.PENDING_EXPERT);
        var pastSlot=slot(expert,LocalDate.of(2026,10,12),LocalTime.of(10,0),LocalTime.of(10,30));
        var past=request(member,expert,pastSlot,ConsultationStatus.PENDING_CONSULTATION);
        var future=request(member,expert,slot(expert,LocalDate.of(2026,10,15),LocalTime.of(10,0),LocalTime.of(10,30)),ConsultationStatus.PENDING_CONSULTATION);
        mvc.perform(get("/api/v1/admin/appointments").param("status","PENDING_EXPERT").param("dateBasis","CREATED").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].slot").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].attention_reason").value("WAITING_EXPERT"));
        mvc.perform(get("/api/v1/admin/appointments").param("status","PENDING_CONSULTATION").param("attentionOnly","true").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(past)).andExpect(jsonPath("$.data.items[0].note").doesNotExist());
        mvc.perform(get("/api/v1/admin/appointments").param("fromDate","2026-10-15").param("toDate","2026-10-15").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(future));
        mvc.perform(get("/api/v1/admin/appointments/"+pending).with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.note").doesNotExist()).andExpect(jsonPath("$.data.video").doesNotExist());
        assertEquals(ConsultationStatus.PENDING_CONSULTATION,requests.findById(past).orElseThrow().getStatus());
    }
    @Test void dashboardUsesUnifiedRevenueIctBoundaryDistinctCurrentSubscriptionsAndBoundedCollections() throws Exception {
        String member=user("New Member",UserRole.USER);
        jdbc.update("update app.users set created_at=? where id=?",OffsetDateTime.parse("2026-09-30T17:00:00Z"),member);
        pay(member,1001,PaymentSource.REAL,PaymentOrderStatus.PAID,99000,"2026-09-30T17:00:00Z");
        pay(member,1002,PaymentSource.DEMO,PaymentOrderStatus.PAID,399000,"2026-10-02T00:00:00Z");
        pay(member,1003,PaymentSource.LEGACY_UNKNOWN,PaymentOrderStatus.PAID,99000,"2026-10-03T00:00:00Z");
        pay(member,1004,PaymentSource.REAL,PaymentOrderStatus.PENDING,99000,null);
        pay(member,1005,PaymentSource.REAL,PaymentOrderStatus.PAID,99000,null);
        subscriptions.saveAndFlush(new UserSubscriptionEntity(member,PlanTier.PLAN_99K,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"),OffsetDateTime.parse("2026-11-01T00:00:00Z")));
        subscriptions.saveAndFlush(new UserSubscriptionEntity(member,PlanTier.PLAN_399K,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"),OffsetDateTime.parse("2026-12-01T00:00:00Z")));
        mvc.perform(get("/api/v1/admin/dashboard").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.kpis.real_collected_amount").value(597000))
                .andExpect(jsonPath("$.data.series.collections[0].date").value("2026-10-01"))
                .andExpect(jsonPath("$.data.series.collections[0].amount").value(99000))
                .andExpect(jsonPath("$.data.series.registrations[0].count").value(1))
                .andExpect(jsonPath("$.data.subscription_snapshot.plan_99k_users").value(0))
                .andExpect(jsonPath("$.data.subscription_snapshot.plan_399k_users").value(1));
        mvc.perform(get("/api/v1/admin/revenue/summary").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.collected_amount").value(597000))
                .andExpect(jsonPath("$.data.summary.legacy_unknown_orders").value(1));
        mvc.perform(get("/api/v1/admin/revenue/summary").param("source","ALL").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.collected_amount").value(597000));
        mvc.perform(get("/api/v1/admin/payment-orders/1001").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.order_code").value("1001"))
                .andExpect(jsonPath("$.data.checkout_url").doesNotExist());
    }
    @Test void reportsCsvMatchesCohortAndNeutralizesSpreadsheetFormula() throws Exception {
        String member=user("  =HYPERLINK(\"bad\")",UserRole.USER);
        jdbc.update("update app.users set created_at=? where id=?",OffsetDateTime.parse("2026-10-02T00:00:00Z"),member);
        mvc.perform(get("/api/v1/admin/reports/USERS_SUBSCRIPTIONS").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.summary.current_free_users").value(1));
        var result=mvc.perform(get("/api/v1/admin/reports/USERS_SUBSCRIPTIONS/export").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv")).andReturn();
        String csv=new String(result.getResponse().getContentAsByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"));assertTrue(csv.contains("'  =HYPERLINK("));assertTrue(csv.contains("\"\"bad\"\""));
    }
    @Test void cancelledAppointmentKeepsItsOriginalDateInAdminCalendar() throws Exception {
        String member=user("Cancelled Calendar",UserRole.USER),expert=expert();
        String slot=slot(expert,LocalDate.of(2026,10,12),LocalTime.of(13,0),LocalTime.of(13,30));
        String id=request(member,expert,slot,ConsultationStatus.PENDING_CONSULTATION);
        consultationService.cancel(member,id);
        assertNull(requests.findById(id).orElseThrow().getSlotId());
        mvc.perform(get("/api/v1/admin/appointments").param("fromDate","2026-10-12").param("toDate","2026-10-12")
                .with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.items[0].slot.slot_date").value("2026-10-12"));
        mvc.perform(get("/api/v1/admin/dashboard").with(token(admin,"ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpis.today_total").value(1))
                .andExpect(jsonPath("$.data.kpis.today_pending").value(0));
    }
    @Test void expertWritesRejectInvalidPasswordBlankNameAndStaleVersionWithoutPartialUpdates() throws Exception {
        String phone="091"+String.format("%07d",PHONE.incrementAndGet());
        String body="""
                {"phone":"%s","password":"%s","full_name":"Expert",
                 "specialty":"OBSTETRICS","years_of_experience":1}
                """.formatted(phone,"ấ".repeat(30));
        String member=user("No Admin",UserRole.USER);
        mvc.perform(post("/api/v1/admin/experts").with(token(member,"ADMIN"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        long before=experts.count();
        // Thirty characters pass DTO length validation but exceed BCrypt's 72-byte limit.
        mvc.perform(post("/api/v1/admin/experts").with(token(admin,"ADMIN"))
                .contentType("application/json").content(body)).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.fields.password").exists());
        assertEquals(before,experts.count());assertFalse(users.existsByPhone("+84"+phone.substring(1)));
        String id=expert();
        for(String invalid:List.of("{\"full_name\":\"  \",\"version\":0}",
                "{\"full_name\":\"Changed\",\"version\":-1}","{\"full_name\":\"Changed\"}")) {
            mvc.perform(patch("/api/v1/admin/experts/"+id).with(token(admin,"ADMIN"))
                    .contentType("application/json").content(invalid)).andExpect(status().is(422));
        }
        assertEquals("Expert",experts.findById(id).orElseThrow().getFullName());
        mvc.perform(patch("/api/v1/admin/experts/"+id).with(token(admin,"ADMIN"))
                .contentType("application/json").content("{\"full_name\":\"Updated Expert\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(patch("/api/v1/admin/experts/"+id).with(token(admin,"ADMIN"))
                .contentType("application/json").content("{\"full_name\":\"Stale Update\",\"version\":0}"))
                .andExpect(status().isConflict());
        assertEquals("Updated Expert",experts.findById(id).orElseThrow().getFullName());
    }
    @Test void adminEventDedupKeepsIndependentReadStateAndDoesNotAlterRequest() {
        String other=user("Other Admin",UserRole.ADMIN),source=UUID.randomUUID().toString();
        notificationService.publishAdminOnce(admin,NotificationType.CONTACT,"New","Safe",
                "nutrimom://admin/contact-requests/"+source,"CONTACT_REQUEST",source);
        notificationService.publishAdminOnce(admin,NotificationType.CONTACT,"New","Safe",
                "nutrimom://admin/contact-requests/"+source,"CONTACT_REQUEST",source);
        notificationService.publishAdminOnce(other,NotificationType.CONTACT,"New","Safe",
                "nutrimom://admin/contact-requests/"+source,"CONTACT_REQUEST",source);
        assertEquals(1,notifications.countByUserIdAndReadAtIsNull(admin));
        var record=notifications.findByDedupKey("ADMIN_CONTACT_NEW:"+admin+":"+source).orElseThrow();
        notificationService.markRead(admin,record.getId());
        assertEquals(0,notifications.countByUserIdAndReadAtIsNull(admin));
        assertEquals(1,notifications.countByUserIdAndReadAtIsNull(other));
    }
    @Test void csvRejectsOversizedExportBeforeSendingAnyFile() throws Exception {
        String member=user("Report Limit",UserRole.USER);
        List<Object[]> batch=new ArrayList<>();
        for(int i=0;i<10001;i++) batch.add(new Object[]{UUID.randomUUID().toString(),9000000L+i,member,
                "PLAN_99K",99000,"VND","REAL","PAID",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC),OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC),0L});
        jdbc.batchUpdate("""
                insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,source,status,paid_at,created_at,updated_at,version)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """,batch);
        mvc.perform(get("/api/v1/admin/reports/REVENUE/export").with(token(admin,"ADMIN")))
                .andExpect(status().is(422)).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }
    @Test void recentActivityTopEightMatchesFullUnionWithTimestampTiesAcrossAllSources() {
        for(int i=0;i<12;i++) {
            String member=user("Recent "+i,UserRole.USER);
            var at=OffsetDateTime.ofInstant(NOW.minusSeconds(i%3),ZoneOffset.UTC);
            jdbc.update("update app.users set created_at=? where id=?",at,member);
            pay(member,7000000L+i,PaymentSource.REAL,PaymentOrderStatus.PAID,99000,at.toString());
            String consultation=request(member,null,null,ConsultationStatus.COMPLETED);
            jdbc.update("update app.consultation_requests set completed_at=? where id=?",at,consultation);
            var support=new ContactRequestEntity();support.setUserId(member);support.setTopic(ContactTopic.ACCOUNT);
            support.setMessage("Recent support");support.setStatus(ContactRequestStatus.COMPLETED);
            support.setCompletedAt(at);support.setCompletedBy(admin);contacts.saveAndFlush(support);
        }
        var expected=jdbc.queryForList("""
                select kind||':'||id from (
                  select u.id,'USER_REGISTERED' kind,u.created_at occurred_at from app.users u
                   where exists(select 1 from app.user_roles r where r.user_id=u.id and r.role='USER')
                   and not exists(select 1 from app.user_roles r where r.user_id=u.id and r.role in ('ADMIN','EXPERT'))
                  union all select id,'PAYMENT_PAID',paid_at from app.payment_orders where status='PAID' and paid_at is not null and paid_at<=?
                  union all select id,'CONSULTATION_COMPLETED',completed_at from app.consultation_requests where status='COMPLETED' and completed_at is not null
                  union all select id,'SUPPORT_COMPLETED',completed_at from app.contact_requests where status='COMPLETED' and completed_at is not null
                ) all_activity order by occurred_at desc,id asc,kind asc limit 8
                """,String.class,OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC));
        assertEquals(8,expected.size());
        assertEquals(expected,operations.dashboard(null,null).recentActivity().stream().map(row->row.id()).toList());
    }
    String user(String name,UserRole role) {
        var u=new UserEntity();u.setPhone("+849"+PHONE.incrementAndGet());u.setDisplayName(name);
        u.setStatus(UserStatus.ACTIVE);u.setRoles(Set.of(role));return users.saveAndFlush(u).getId();
    }
    String expert() {
        String id=user("Expert",UserRole.EXPERT);var e=new ExpertProfileEntity();e.setUserId(id);e.setFullName("Expert");
        e.setSpecialty(Specialty.OBSTETRICS);e.setStatus(ExpertStatus.ACTIVE);experts.saveAndFlush(e);return id;
    }
    String slot(String expert,LocalDate day,LocalTime start,LocalTime end) {
        var s=new AvailabilitySlotEntity();s.setExpertUserId(expert);s.setSlotDate(day);s.setStartTime(start);s.setEndTime(end);
        s.setStatus(SlotStatus.BOOKED);return slots.saveAndFlush(s).getId();
    }
    String request(String user,String expert,String slot,ConsultationStatus status) {
        var c=new ConsultationRequestEntity();c.setUserId(user);c.setExpertUserId(expert);c.setSlotId(slot);
        c.setStatus(status);c.setAssignmentType(expert==null?AssignmentType.RANDOM:AssignmentType.DIRECT);
        if(slot!=null)c.captureScheduledSlot(slots.findById(slot).orElseThrow());
        c.setSpecialty(Specialty.OBSTETRICS);c.setNote("PRIVATE CLINICAL NOTE");
        return requests.saveAndFlush(c).getId();
    }
    void pay(String user,long code,PaymentSource source,PaymentOrderStatus status,int amount,String paidAt) {
        var p=new PaymentOrderEntity(code,user,PlanTier.PLAN_99K,amount,"test");p.setSource(source);p.setStatus(status);
        p.setPaidAt(paidAt==null?null:OffsetDateTime.parse(paidAt));payments.saveAndFlush(p);
    }
    RequestPostProcessor token(String subject,String role) {
        return jwt().jwt(t->t.subject(subject).claim("roles",List.of(role))).authorities(new SimpleGrantedAuthority("ROLE_"+role));
    }
}
