package vn.nutrimom.calendar;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

/** CRUD nhắc nhở, nút tạo từ hồ sơ y tế, optimistic lock và quy ước 404-không-403. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CalendarReminderIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;

    @Test
    void createdReminderShowsUpOnTheCalendarAndInItsOwnList() throws Exception {
        String momId = createUserAccount("0913600001", "Mom Reminder");
        LocalDate day = daysFromToday(5);
        OffsetDateTime startsAt = day.atTime(9, 0).atZone(VN).toOffsetDateTime();

        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"ROUTINE_CHECKUP","title":"Khám định kỳ tháng 3",
                                 "starts_at":"%s","remind_minutes_before":1440,
                                 "facility_name":"BV Hùng Vương","note":"Mang sổ khám"}
                                """.formatted(startsAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.date").value(day.toString()))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.data.remind_at")
                        .value(iso(startsAt.minusMinutes(1440))))
                .andExpect(jsonPath("$.data.next_suggestion").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(get("/api/v1/calendar/reminders").with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].facility_name").value("BV Hùng Vương"));
    }

    @Test
    void reminderFromMedicalRecordCopiesTitleFacilityAndPregnancy() throws Exception {
        String momId = createUserAccount("0913600011", "Mom From Record");
        String pregnancyId = createPregnancy(momId);
        String recordId = createMedicalRecord(momId, pregnancyId);
        OffsetDateTime startsAt = daysFromToday(14).atTime(8, 30).atZone(VN).toOffsetDateTime();

        mockMvc.perform(post("/api/v1/medical-records/{id}/reminders", recordId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"FOLLOW_UP","starts_at":"%s","remind_minutes_before":120}
                                """.formatted(startsAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("Tái khám: Siêu âm 12 tuần"))
                .andExpect(jsonPath("$.data.facility_name").value("BV Từ Dũ"))
                .andExpect(jsonPath("$.data.pregnancy_id").value(pregnancyId))
                .andExpect(jsonPath("$.data.source_record_id").value(recordId));
    }

    @Test
    void reminderCannotBeCreatedFromSomeoneElsesMedicalRecord() throws Exception {
        String momId = createUserAccount("0913600021", "Mom Owner");
        String otherId = createUserAccount("0913600022", "Mom Other");
        String recordId = createMedicalRecord(momId, createPregnancy(momId));
        OffsetDateTime startsAt = daysFromToday(7).atTime(8, 0).atZone(VN).toOffsetDateTime();

        mockMvc.perform(post("/api/v1/medical-records/{id}/reminders", recordId)
                        .with(userJwt(otherId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"FOLLOW_UP","starts_at":"%s"}
                                """.formatted(startsAt)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void patchWithAStaleVersionIsRejected() throws Exception {
        String momId = createUserAccount("0913600031", "Mom Version");
        String reminderId = createReminder(momId, "FOLLOW_UP", daysFromToday(6));

        mockMvc.perform(patch("/api/v1/calendar/reminders/{id}", reminderId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"Đổi tên lần 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Đổi tên lần 1"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(patch("/api/v1/calendar/reminders/{id}", reminderId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"Đổi tên lần 2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void markingDoneSuggestsTheNextMilestoneForRecurringTypesOnly() throws Exception {
        String momId = createUserAccount("0913600041", "Mom Done");
        LocalDate day = daysFromToday(3);
        String followUp = createReminder(momId, "FOLLOW_UP", day);
        String custom = createReminder(momId, "CUSTOM", day);
        OffsetDateTime startsAt = day.atTime(9, 0).atZone(VN).toOffsetDateTime();

        mockMvc.perform(patch("/api/v1/calendar/reminders/{id}", followUp)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.next_suggestion").value(iso(startsAt.plusDays(7))));

        mockMvc.perform(patch("/api/v1/calendar/reminders/{id}", custom)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.next_suggestion").doesNotExist());
    }

    @Test
    void clearingTheOffsetAlsoClearsTheDerivedRemindAt() throws Exception {
        String momId = createUserAccount("0913600051", "Mom Offset");
        String reminderId = createReminder(momId, "CUSTOM", daysFromToday(4));

        mockMvc.perform(patch("/api/v1/calendar/reminders/{id}", reminderId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"clear_remind_minutes_before\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remind_minutes_before").doesNotExist())
                .andExpect(jsonPath("$.data.remind_at").doesNotExist());
    }

    /**
     * {@code remind_at} là cột dẫn xuất, phải tính lại khi dời giờ — nếu không, job sẽ bắn theo mốc
     * cũ. Ràng buộc DB tương ứng không được test che vì H2 {@code create-drop} bỏ CHECK.
     */
    @Test
    void movingTheStartRecomputesTheDerivedRemindAt() throws Exception {
        String momId = createUserAccount("0913600101", "Mom Reschedule");
        String reminderId = createReminder(momId, "FOLLOW_UP", daysFromToday(4));
        OffsetDateTime moved = daysFromToday(9).atTime(14, 0).atZone(VN).toOffsetDateTime();

        mockMvc.perform(patch("/api/v1/calendar/reminders/{id}", reminderId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"starts_at\":\"%s\"}".formatted(moved)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.starts_at").value(iso(moved)))
                .andExpect(jsonPath("$.data.remind_minutes_before").value(60))
                .andExpect(jsonPath("$.data.remind_at").value(iso(moved.minusMinutes(60))));
    }

    @Test
    void deleteIsSoftAndTheSecondAttemptIsNotFound() throws Exception {
        String momId = createUserAccount("0913600061", "Mom Delete");
        String reminderId = createReminder(momId, "CUSTOM", daysFromToday(2));

        mockMvc.perform(delete("/api/v1/calendar/reminders/{id}", reminderId).with(userJwt(momId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/calendar/reminders/{id}", reminderId).with(userJwt(momId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/calendar/reminders").with(userJwt(momId)))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void anotherUsersReminderIsAlwaysNotFound() throws Exception {
        String momId = createUserAccount("0913600071", "Mom A");
        String otherId = createUserAccount("0913600072", "Mom B");
        String reminderId = createReminder(momId, "CUSTOM", daysFromToday(2));

        mockMvc.perform(get("/api/v1/calendar/reminders/{id}", reminderId).with(userJwt(otherId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/calendar/reminders/{id}", reminderId)
                        .with(userJwt(otherId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"Chiếm quyền\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/calendar/reminders/{id}", reminderId).with(userJwt(otherId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void pastStartAndOversizedOffsetAreValidationErrors() throws Exception {
        String momId = createUserAccount("0913600081", "Mom Invalid");

        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CUSTOM","title":"Hôm qua","starts_at":"%s"}
                                """.formatted(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CUSTOM","title":"Nhắc quá sớm","starts_at":"%s",
                                 "remind_minutes_before":20000}
                                """.formatted(daysFromToday(30).atTime(9, 0).atZone(VN)
                                .toOffsetDateTime())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void reminderCannotBeAttachedToSomeoneElsesPregnancy() throws Exception {
        String momId = createUserAccount("0913600091", "Mom P1");
        String otherId = createUserAccount("0913600092", "Mom P2");
        String foreignPregnancy = createPregnancy(otherId);

        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CUSTOM","title":"Mượn thai kỳ","starts_at":"%s",
                                 "pregnancy_id":"%s"}
                                """.formatted(daysFromToday(3).atTime(9, 0).atZone(VN)
                                .toOffsetDateTime(), foreignPregnancy)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/medical-records/{id}/reminders", UUID.randomUUID().toString())
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"FOLLOW_UP","starts_at":"%s"}
                                """.formatted(daysFromToday(3).atTime(9, 0).atZone(VN)
                                .toOffsetDateTime())))
                .andExpect(status().isNotFound());
    }

    // ----- Helpers -----

    private static LocalDate daysFromToday(int days) {
        return LocalDate.now(VN).plusDays(days);
    }

    /**
     * Chuỗi ISO đúng như Jackson sinh ra cho một OffsetDateTime UTC — luôn có phần giây, nên
     * {@code OffsetDateTime#toString} (rút gọn giây bằng 0) không khớp.
     */
    private static String iso(OffsetDateTime value) {
        return UTC_ISO.format(value.withOffsetSameInstant(ZoneOffset.UTC));
    }

    private String createReminder(String userId, String type, LocalDate day) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"%s","title":"Mốc %s","starts_at":"%s",
                                 "remind_minutes_before":60}
                                """.formatted(type, type,
                                day.atTime(9, 0).atZone(VN).toOffsetDateTime())))
                .andExpect(status().isCreated()).andReturn();
        return readData(result, "/data/id");
    }

    private String createPregnancy(String userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pregnancies")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimated_due_date\":\"%s\"}"
                                .formatted(LocalDate.now(ZoneOffset.UTC).plusDays(100))))
                .andExpect(status().isCreated()).andReturn();
        return readData(result, "/data/id");
    }

    private String createMedicalRecord(String userId, String pregnancyId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/medical-records")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pregnancy_id":"%s","category":"ULTRASOUND",
                                 "title":"Siêu âm 12 tuần","occurred_at":"%s",
                                 "facility_name":"BV Từ Dũ"}
                                """.formatted(pregnancyId,
                                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))))
                .andExpect(status().isCreated()).andReturn();
        return readData(result, "/data/id");
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

    private RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
