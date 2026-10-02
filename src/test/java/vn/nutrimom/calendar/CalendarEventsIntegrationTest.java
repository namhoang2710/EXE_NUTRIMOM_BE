package vn.nutrimom.calendar;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;

/** Lịch trộn ba nguồn: hồ sơ y tế, buổi tư vấn đã xác nhận, nhắc nhở tự tạo (spec mục 10). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CalendarEventsIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;

    @Test
    void mergedEventsCoverAllThreeSourcesSortedByStart() throws Exception {
        String momId = createUserAccount("0913500001", "Mom Calendar");
        String pregnancyId = createPregnancy(momId);
        LocalDate day = daysFromToday(2);

        createMedicalRecord(momId, pregnancyId, "Siêu âm 12 tuần",
                day.atTime(6, 0).atZone(VN).toOffsetDateTime());
        createReminder(momId, "Uống sắt", day.atTime(20, 0).atZone(VN).toOffsetDateTime());
        String expertId = createExpert("0913500002", "HEALTH", "BS Lan");
        bookDirect(momId, expertId, day, "09:00").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", day.toString()).param("to", day.toString())
                        .param("timezone", "Asia/Ho_Chi_Minh")
                        .with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].source").value("MEDICAL_RECORD"))
                .andExpect(jsonPath("$.data[0].title").value("Siêu âm 12 tuần"))
                .andExpect(jsonPath("$.data[0].date").value(day.toString()))
                .andExpect(jsonPath("$.data[1].source").value("CONSULTATION"))
                .andExpect(jsonPath("$.data[1].status").value("PENDING_CONSULTATION"))
                .andExpect(jsonPath("$.data[1].subtitle").value("Chuyên gia BS Lan"))
                .andExpect(jsonPath("$.data[1].ends_at").exists())
                .andExpect(jsonPath("$.data[2].source").value("REMINDER"))
                .andExpect(jsonPath("$.data[2].deep_link").exists());
    }

    @Test
    void consultationAppearsOnlyAfterItHasASlot() throws Exception {
        String momId = createUserAccount("0913500011", "Mom Pending");
        createExpert("0913500012", "HEALTH", "BS Cho");
        LocalDate day = daysFromToday(3);

        // RANDOM còn PENDING_EXPERT: chưa chuyên gia nào nhận nên chưa có mốc giờ nào để vẽ.
        mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignment_type\":\"RANDOM\",\"specialty\":\"HEALTH\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", day.minusDays(5).toString())
                        .param("to", day.plusDays(5).toString())
                        .with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void cancelledConsultationDisappearsFromCalendar() throws Exception {
        String momId = createUserAccount("0913500021", "Mom Cancel");
        String expertId = createExpert("0913500022", "HEALTH", "BS Huy");
        LocalDate day = daysFromToday(4);
        MvcResult booked = bookDirect(momId, expertId, day, "10:00")
                .andExpect(status().isCreated()).andReturn();
        String requestId = readData(booked, "/data/id");

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", day.toString()).param("to", day.toString())
                        .with(userJwt(momId)))
                .andExpect(jsonPath("$.data", hasSize(1)));

        mockMvc.perform(post("/api/v1/consultation-requests/{id}/cancel", requestId)
                        .with(userJwt(momId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", day.toString()).param("to", day.toString())
                        .with(userJwt(momId)))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void calendarIsScopedToOwner() throws Exception {
        String momId = createUserAccount("0913500031", "Mom Owner");
        String otherId = createUserAccount("0913500032", "Mom Other");
        LocalDate day = daysFromToday(2);
        createReminder(momId, "Riêng tư", day.atTime(9, 0).atZone(VN).toOffsetDateTime());

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", day.toString()).param("to", day.toString())
                        .with(userJwt(otherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void typesFilterNarrowsSources() throws Exception {
        String momId = createUserAccount("0913500041", "Mom Filter");
        String pregnancyId = createPregnancy(momId);
        LocalDate day = daysFromToday(2);
        createMedicalRecord(momId, pregnancyId, "Xét nghiệm máu",
                day.atTime(8, 0).atZone(VN).toOffsetDateTime());
        createReminder(momId, "Tái khám", day.atTime(15, 0).atZone(VN).toOffsetDateTime());

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", day.toString()).param("to", day.toString())
                        .param("types", "REMINDER")
                        .with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].source").value("REMINDER"));
    }

    /**
     * Khung giờ tư vấn lưu theo ngày/giờ Việt Nam, nên khi người xem ở múi giờ khác, ngày địa phương
     * của họ lệch với {@code slot_date}. Lọc khít {@code slot_date} ở DB sẽ làm rụng mốc này.
     */
    @Test
    void vietnamSlotStaysOnTheCorrectLocalDayForAForeignTimezone() throws Exception {
        String momId = createUserAccount("0913500051", "Mom Abroad");
        String expertId = createExpert("0913500052", "HEALTH", "BS Xa");
        LocalDate day = daysFromToday(3);
        bookDirect(momId, expertId, day, "08:00").andExpect(status().isCreated());

        // 08:00 giờ VN = 01:00 UTC, tức vẫn là chiều hôm trước ở Los Angeles.
        LocalDate localDay = day.atTime(8, 0).atZone(VN)
                .withZoneSameInstant(ZoneId.of("America/Los_Angeles")).toLocalDate();

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", localDay.toString()).param("to", localDay.toString())
                        .param("timezone", "America/Los_Angeles")
                        .with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].source").value("CONSULTATION"))
                .andExpect(jsonPath("$.data[0].date").value(localDay.toString()));
    }

    @Test
    void invalidRangeAndTimezoneAreValidationErrorsNotServerErrors() throws Exception {
        String momId = createUserAccount("0913500061", "Mom Bad Params");
        LocalDate today = daysFromToday(0);

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", today.toString()).param("to", today.minusDays(1).toString())
                        .with(userJwt(momId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", today.toString()).param("to", today.plusDays(367).toString())
                        .with(userJwt(momId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", today.toString()).param("to", today.toString())
                        .param("timezone", "Mars/Olympus")
                        .with(userJwt(momId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void monthViewListsOnlyDaysWithEventsAndRejectsBadMonth() throws Exception {
        String momId = createUserAccount("0913500071", "Mom Month");
        LocalDate day = daysFromToday(2);
        createReminder(momId, "Khám định kỳ", day.atTime(9, 0).atZone(VN).toOffsetDateTime());
        createReminder(momId, "Uống vitamin", day.atTime(21, 0).atZone(VN).toOffsetDateTime());

        mockMvc.perform(get("/api/v1/calendar/month")
                        .param("year", String.valueOf(day.getYear()))
                        .param("month", String.valueOf(day.getMonthValue()))
                        .param("timezone", "Asia/Ho_Chi_Minh")
                        .with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].date").value(day.toString()))
                .andExpect(jsonPath("$.data[0].event_count").value(2))
                .andExpect(jsonPath("$.data[0].types", hasSize(1)))
                .andExpect(jsonPath("$.data[0].types[0]").value("REMINDER"));

        mockMvc.perform(get("/api/v1/calendar/month")
                        .param("year", String.valueOf(day.getYear())).param("month", "13")
                        .with(userJwt(momId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ----- Helpers -----

    private static LocalDate daysFromToday(int days) {
        return LocalDate.now(VN).plusDays(days);
    }

    private org.springframework.test.web.servlet.ResultActions bookDirect(
            String userId, String expertUserId, LocalDate date, String startTime) throws Exception {
        return mockMvc.perform(post("/api/v1/consultation-requests")
                .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"assignment_type":"DIRECT","expert_user_id":"%s",
                         "slot_date":"%s","start_time":"%s"}
                        """.formatted(expertUserId, date, startTime)));
    }

    private String createPregnancy(String userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pregnancies")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimated_due_date\":\"%s\"}"
                                .formatted(LocalDate.now(ZoneOffset.UTC).plusDays(100))))
                .andExpect(status().isCreated()).andReturn();
        return readData(result, "/data/id");
    }

    private String createMedicalRecord(String userId, String pregnancyId, String title,
                                       OffsetDateTime occurredAt) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/medical-records")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pregnancy_id":"%s","category":"ULTRASOUND","title":"%s",
                                 "occurred_at":"%s","facility_name":"BV Từ Dũ"}
                                """.formatted(pregnancyId, title, occurredAt)))
                .andExpect(status().isCreated()).andReturn();
        return readData(result, "/data/id");
    }

    private String createReminder(String userId, String title, OffsetDateTime startsAt)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CUSTOM","title":"%s","starts_at":"%s"}
                                """.formatted(title, startsAt)))
                .andExpect(status().isCreated()).andReturn();
        return readData(result, "/data/id");
    }

    private String createExpert(String phone, String specialty, String fullName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/experts")
                        .with(adminJwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","password":"password123","full_name":"%s",
                                 "specialty":"%s","title":"Chuyen gia","workplace":"NutriMom",
                                 "years_of_experience":5,"bio":"Gioi thieu"}
                                """.formatted(phone, fullName, specialty)))
                .andExpect(status().isCreated()).andReturn();
        return readData(result, "/data/user_id");
    }

    private String createUserAccount(String phone, String displayName) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName(displayName);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        return users.saveAndFlush(user).getId();
    }

    private String readData(MvcResult result, String pointer) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at(pointer).stringValue();
    }

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject("admin").claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
