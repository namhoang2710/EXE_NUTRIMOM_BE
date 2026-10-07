package vn.nutrimom.family;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Lịch của mẹ nhìn từ tài khoản người nhà.
 *
 * <p>Bài quan trọng nhất ở đây không phải "thấy được nhắc nhở" mà là "không thấy hồ sơ y tế dù có
 * cố" — đó là ranh giới duy nhất ngăn dữ liệu y tế đi vòng qua đường lịch.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SharedCalendarIntegrationTest extends ApiIntegrationTestSupport {

    /** DB dùng chung cả class nên mỗi test phải có số điện thoại riêng, nếu không 409 trùng. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private Session owner;
    private Session partner;
    private String memberId;
    private String pregnancyId;

    @BeforeEach
    void grantSharedCalendar() throws Exception {
        owner = registerViaOtp(nextPhone(), "Calendar Mom");
        partner = registerViaOtp(nextPhone(), "Calendar Dad");
        pregnancyId = createPregnancyAndGroup(owner);
        String token = createInvitation(owner, partner.phone(), Set.of("SHARED_CALENDAR"));
        MvcResult accepted = accept(partner, token);
        memberId = objectMapper.readTree(accepted.getResponse().getContentAsString())
                .at("/data/id").stringValue();

        createReminder(owner, "Uong sat");
        createMedicalRecord(owner, "Sieu am 4D nghi ngo di tat tim");
    }

    @Test
    void familyMemberSeesRemindersButNeverMedicalRecords() throws Exception {
        mockMvc.perform(get("/api/v1/family/shared-calendar/events")
                        .param("from", today().toString())
                        .param("to", today().plusDays(7).toString())
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.owner_display_name").value("Calendar Mom"))
                .andExpect(jsonPath("$.data.allowed_sources", hasItem("REMINDER")))
                .andExpect(jsonPath("$.data.allowed_sources", not(hasItem("MEDICAL_RECORD"))))
                .andExpect(jsonPath("$.data.events[*].source", hasItem("REMINDER")))
                .andExpect(jsonPath("$.data.events[*].source",
                        everyItem(is(not("MEDICAL_RECORD")))))
                .andExpect(jsonPath("$.data.events[*].title", hasItem("Uong sat")));
    }

    /**
     * Deep link trong mốc lịch trỏ tới endpoint chỉ chủ sở hữu mở được, nên người nhà không được
     * nhận nó — bấm vào chỉ ăn 403. Jackson để {@code non_null} nên field biến mất hẳn khỏi JSON.
     */
    @Test
    void sharedEventsCarryNoOwnerOnlyDeepLink() throws Exception {
        mockMvc.perform(get("/api/v1/family/shared-calendar/events")
                        .param("from", today().toString())
                        .param("to", today().plusDays(7).toString())
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[*].title", hasItem("Uong sat")))
                .andExpect(jsonPath("$.data.events[*].deep_link").doesNotExist());
    }

    /** Màn hình của chính mẹ bầu vẫn cần deep link — việc cắt chỉ áp cho lối vào của người nhà. */
    @Test
    void theOwnersOwnCalendarKeepsItsDeepLink() throws Exception {
        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", today().toString())
                        .param("to", today().plusDays(7).toString())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].deep_link", hasItem(containsString("nutrimom://"))));
    }

    /** Không được phép lách bằng cách tự chọn nguồn: giao rỗng phải ra rỗng, không phải "tất cả". */
    @Test
    void askingExplicitlyForMedicalRecordsReturnsNothing() throws Exception {
        mockMvc.perform(get("/api/v1/family/shared-calendar/events")
                        .param("from", today().toString())
                        .param("to", today().plusDays(7).toString())
                        .param("types", "MEDICAL_RECORD")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events.length()").value(0));
    }

    /** MEDICAL_RECORDS là scope của module hồ sơ y tế, không phải chìa khoá phụ cho lịch. */
    @Test
    void grantingMedicalRecordsScopeStillKeepsRecordsOffTheCalendar() throws Exception {
        updateScopes(Set.of("SHARED_CALENDAR", "MEDICAL_RECORDS"));

        mockMvc.perform(get("/api/v1/family/shared-calendar/events")
                        .param("from", today().toString())
                        .param("to", today().plusDays(7).toString())
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed_sources", not(hasItem("MEDICAL_RECORD"))))
                .andExpect(jsonPath("$.data.events[*].source",
                        everyItem(is(not("MEDICAL_RECORD")))));
    }

    @Test
    void revokingTheScopeClosesTheCalendarImmediately() throws Exception {
        updateScopes(Set.of("FAMILY_TASKS"));

        mockMvc.perform(get("/api/v1/family/shared-calendar/events")
                        .param("from", today().toString())
                        .param("to", today().plusDays(7).toString())
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SHARING_SCOPE_REQUIRED"));
    }

    @Test
    void accountWithoutMembershipIsRejected() throws Exception {
        Session stranger = registerViaOtp(nextPhone(), "Stranger");

        mockMvc.perform(get("/api/v1/family/shared-calendar/events")
                        .param("from", today().toString())
                        .param("to", today().plusDays(7).toString())
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SHARING_SCOPE_REQUIRED"));
    }

    @Test
    void monthGridIsScopedToTheOwnerToo() throws Exception {
        LocalDate day = today();
        mockMvc.perform(get("/api/v1/family/shared-calendar/month")
                        .param("year", String.valueOf(day.getYear()))
                        .param("month", String.valueOf(day.getMonthValue()))
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed_sources", not(hasItem("MEDICAL_RECORD"))))
                .andExpect(jsonPath("$.data.days[*].types",
                        everyItem(not(hasItem("MEDICAL_RECORD")))));
    }

    /** Dashboard người nhà từng trả mảng rỗng cứng dù scope đang bật. */
    @Test
    void partnerDashboardReportsTheRealCalendarInsteadOfAnEmptyArray() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/partner")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shared_calendar[*].title", hasItem("Uong sat")))
                .andExpect(jsonPath("$.data.shared_calendar[*].source",
                        everyItem(is(not("MEDICAL_RECORD")))))
                // Dashboard người nhà là bề mặt thứ hai của cùng dữ liệu, rò deep link y hệt.
                .andExpect(jsonPath("$.data.shared_calendar[*].deep_link").doesNotExist());
    }

    // ----- dựng dữ liệu -----

    private static String nextPhone() {
        return String.format("09124%05d", SEQ.incrementAndGet());
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private String createPregnancyAndGroup(Session session) throws Exception {
        MvcResult pregnancy = mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PregnancyBody(today().plusDays(120)))))
                .andExpect(status().isCreated())
                .andReturn();
        mockMvc.perform(post("/api/v1/family-groups")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
        return objectMapper.readTree(pregnancy.getResponse().getContentAsString())
                .at("/data/id").stringValue();
    }

    private String createInvitation(Session inviter, String phone, Set<String> scopes)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/family-invitations")
                        .header("Authorization", "Bearer " + inviter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InvitationBody(phone, "PARTNER", scopes, 48))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/token").stringValue();
    }

    private MvcResult accept(Session session, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptBody(token))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void updateScopes(Set<String> scopes) throws Exception {
        MvcResult current = mockMvc.perform(get("/api/v1/family-members")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode members = objectMapper.readTree(current.getResponse().getContentAsString())
                .at("/data");
        long version = 0;
        for (JsonNode node : members) {
            if (memberId.equals(node.at("/id").stringValue())) {
                version = node.at("/version").asLong();
            }
        }
        mockMvc.perform(patch("/api/v1/family-members/" + memberId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateMemberBody(scopes, version))))
                .andExpect(status().isOk());
    }

    private void createReminder(Session session, String title) throws Exception {
        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReminderBody(
                                "CUSTOM", title,
                                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)))))
                .andExpect(status().isCreated());
    }

    private void createMedicalRecord(Session session, String title) throws Exception {
        mockMvc.perform(post("/api/v1/medical-records")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MedicalRecordBody(
                                pregnancyId, "ULTRASOUND", title,
                                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)))))
                .andExpect(status().isCreated());
    }

    private record PregnancyBody(LocalDate estimatedDueDate) { }
    private record InvitationBody(
            String invitedPhone, String relationship, Set<String> scopes, int expiresInHours) { }
    private record AcceptBody(String token) { }
    private record UpdateMemberBody(Set<String> scopes, long version) { }
    private record ReminderBody(String type, String title, OffsetDateTime startsAt) { }
    private record MedicalRecordBody(
            String pregnancyId, String category, String title, OffsetDateTime occurredAt) { }
}
