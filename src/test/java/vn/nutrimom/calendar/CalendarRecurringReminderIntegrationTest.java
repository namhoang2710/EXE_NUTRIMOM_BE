package vn.nutrimom.calendar;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
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

/**
 * Nhắc nhở lặp lại: đặt một lần, lịch tự hiện mỗi lần lặp.
 *
 * <p>Các lần lặp không được lưu thành dòng nên test này cũng là chốt chặn cho việc khai triển
 * đúng số mốc — sai một ngày là người dùng uống thuốc sai một ngày.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CalendarRecurringReminderIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;

    /** "Uống vitamin mỗi tối 20:00" — đặt một lần, cả tuần đều có. */
    @Test
    void dailyReminderShowsUpOnEveryDayOfTheWindow() throws Exception {
        String momId = createUserAccount("0913800001", "Mom Vitamin");
        LocalDate first = LocalDate.now(VN).plusDays(1);
        LocalDate last = first.plusDays(6);

        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CUSTOM","title":"Uống vitamin",
                                 "starts_at":"%s","remind_minutes_before":15,
                                 "repeat":{"rule":"DAILY","interval":1,"until":"%s"}}
                                """.formatted(at(first, 20), last)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.repeat.rule").value("DAILY"))
                .andExpect(jsonPath("$.data.repeat.interval").value(1))
                .andExpect(jsonPath("$.data.next_occurrence").value(iso(at(first, 20))))
                .andExpect(jsonPath("$.data.remind_at").value(iso(at(first, 20).minusMinutes(15))));

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", first.toString()).param("to", last.toString())
                        .with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(7)))
                .andExpect(jsonPath("$.data[*].recurring", everyItem(is(true))))
                .andExpect(jsonPath("$.data[0].date").value(first.toString()))
                .andExpect(jsonPath("$.data[6].date").value(last.toString()))
                .andExpect(jsonPath("$.data[0].starts_at").value(iso(at(first, 20))));
    }

    /** Thuốc uống cách ngày: 7 ngày thì chỉ 4 lần. */
    @Test
    void everyOtherDayReminderSkipsAlternateDays() throws Exception {
        String momId = createUserAccount("0913800011", "Mom Cach Ngay");
        LocalDate first = LocalDate.now(VN).plusDays(1);
        LocalDate last = first.plusDays(6);

        createRepeating(momId, "Thuốc cách ngày", at(first, 8),
                "{\"rule\":\"DAILY\",\"interval\":2,\"until\":\"%s\"}".formatted(last));

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", first.toString()).param("to", last.toString())
                        .with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[1].date").value(first.plusDays(2).toString()))
                .andExpect(jsonPath("$.data[3].date").value(first.plusDays(6).toString()));
    }

    /** Kiểu báo thức iOS: chọn thứ, và nhiều mốc giờ trong ngày. */
    @Test
    void weeklyReminderFiresOnChosenWeekdaysAtEveryChosenTime() throws Exception {
        String momId = createUserAccount("0913800021", "Mom Theo Thu");
        LocalDate monday = LocalDate.now(VN).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);

        createRepeating(momId, "Thuốc sáng tối", at(monday, 8),
                """
                {"rule":"WEEKLY","interval":1,
                 "days_of_week":["MONDAY","WEDNESDAY","FRIDAY"],
                 "times_of_day":["08:00","20:00"],"until":"%s"}
                """.formatted(sunday));

        // 3 thứ × 2 mốc giờ = 6 lần trong tuần.
        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", monday.toString()).param("to", sunday.toString())
                        .with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(6)))
                .andExpect(jsonPath("$.data[0].starts_at").value(iso(at(monday, 8))))
                .andExpect(jsonPath("$.data[1].starts_at").value(iso(at(monday, 20))))
                .andExpect(jsonPath("$.data[2].date").value(monday.plusDays(2).toString()))
                .andExpect(jsonPath("$.data[4].date").value(monday.plusDays(4).toString()));

        // Lưới tháng phải đếm đúng số lần trong NGÀY đó, không phải số chuỗi.
        mockMvc.perform(get("/api/v1/calendar/month")
                        .param("year", String.valueOf(monday.getYear()))
                        .param("month", String.valueOf(monday.getMonthValue()))
                        .with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].event_count").value(2));
    }

    /** Đánh dấu "đã uống" cho đúng một ngày, các ngày khác không đổi. */
    @Test
    void markingOneOccurrenceDoneLeavesTheOtherDaysAlone() throws Exception {
        String momId = createUserAccount("0913800031", "Mom Da Uong");
        LocalDate first = LocalDate.now(VN).plusDays(1);
        LocalDate last = first.plusDays(2);
        String reminderId = createRepeating(momId, "Uống sắt", at(first, 9),
                "{\"rule\":\"DAILY\",\"interval\":1,\"until\":\"%s\"}".formatted(last));

        mockMvc.perform(put("/api/v1/calendar/reminders/{id}/occurrences", reminderId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occurrence_at\":\"%s\",\"status\":\"DONE\"}"
                                .formatted(at(first, 9))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", first.toString()).param("to", last.toString())
                        .with(userJwt(momId)))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].status").value("DONE"))
                .andExpect(jsonPath("$.data[1].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data[2].status").value("SCHEDULED"));

        // Bỏ đánh dấu: status null xoá dòng ngoại lệ, mốc về lại chưa làm.
        mockMvc.perform(put("/api/v1/calendar/reminders/{id}/occurrences", reminderId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occurrence_at\":\"%s\"}".formatted(at(first, 9))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", first.toString()).param("to", last.toString())
                        .with(userJwt(momId)))
                .andExpect(jsonPath("$.data[0].status").value("SCHEDULED"));
    }

    /** Mốc không thuộc chuỗi thì không được tạo dòng ngoại lệ mồ côi. */
    @Test
    void markingAnOccurrenceOutsideTheSeriesIsRejected() throws Exception {
        String momId = createUserAccount("0913800041", "Mom Moc La");
        LocalDate first = LocalDate.now(VN).plusDays(1);
        String reminderId = createRepeating(momId, "Uống sắt", at(first, 9),
                "{\"rule\":\"DAILY\",\"interval\":2}");

        mockMvc.perform(put("/api/v1/calendar/reminders/{id}/occurrences", reminderId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occurrence_at\":\"%s\",\"status\":\"DONE\"}"
                                .formatted(at(first.plusDays(1), 9))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /** Chuỗi lặp không có khái niệm "cả chuỗi đã xong". */
    @Test
    void markingAWholeSeriesDoneIsRejected() throws Exception {
        String momId = createUserAccount("0913800051", "Mom Ca Chuoi");
        LocalDate first = LocalDate.now(VN).plusDays(1);
        String reminderId = createRepeating(momId, "Uống sắt", at(first, 9),
                "{\"rule\":\"DAILY\",\"interval\":1}");

        mockMvc.perform(patch("/api/v1/calendar/reminders/{id}", reminderId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"status\":\"DONE\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /** Bỏ lặp thì quay về mốc một lần, lịch chỉ còn đúng một mốc. */
    @Test
    void clearingTheRepeatTurnsTheSeriesBackIntoASingleEvent() throws Exception {
        String momId = createUserAccount("0913800061", "Mom Bo Lap");
        LocalDate first = LocalDate.now(VN).plusDays(1);
        LocalDate last = first.plusDays(4);
        String reminderId = createRepeating(momId, "Uống sắt", at(first, 9),
                "{\"rule\":\"DAILY\",\"interval\":1,\"until\":\"%s\"}".formatted(last));

        mockMvc.perform(patch("/api/v1/calendar/reminders/{id}", reminderId)
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"clear_repeat\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repeat").doesNotExist());

        mockMvc.perform(get("/api/v1/calendar/events")
                        .param("from", first.toString()).param("to", last.toString())
                        .with(userJwt(momId)))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void invalidRepeatSpecsAreRejected() throws Exception {
        String momId = createUserAccount("0913800071", "Mom Sai Luat");
        LocalDate first = LocalDate.now(VN).plusDays(1);

        // Chọn thứ trong tuần cho kiểu lặp theo ngày.
        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CUSTOM","title":"Sai","starts_at":"%s",
                                 "repeat":{"rule":"DAILY","days_of_week":["MONDAY"]}}
                                """.formatted(at(first, 9))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        // Ngày kết thúc lặp trước ngày bắt đầu.
        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CUSTOM","title":"Sai","starts_at":"%s",
                                 "repeat":{"rule":"DAILY","until":"%s"}}
                                """.formatted(at(first, 9), first.minusDays(5))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        // Quá nhiều mốc giờ trong ngày.
        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(momId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CUSTOM","title":"Sai","starts_at":"%s",
                                 "repeat":{"rule":"DAILY","times_of_day":
                                   ["01:00","02:00","03:00","04:00","05:00","06:00","07:00"]}}
                                """.formatted(at(first, 9))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ----- Helpers -----

    /** Mốc {@code hour}:00 giờ Việt Nam của {@code date}. */
    private static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(VN).toOffsetDateTime();
    }

    private static String iso(OffsetDateTime value) {
        return UTC_ISO.format(value.withOffsetSameInstant(ZoneOffset.UTC));
    }

    private String createRepeating(String userId, String title, OffsetDateTime startsAt,
                                   String repeatJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CUSTOM","title":"%s","starts_at":"%s",
                                 "remind_minutes_before":15,"repeat":%s}
                                """.formatted(title, startsAt, repeatJson)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/id").stringValue();
    }

    private String createUserAccount(String phone, String displayName) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName(displayName);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        return users.saveAndFlush(user).getId();
    }

    private RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
