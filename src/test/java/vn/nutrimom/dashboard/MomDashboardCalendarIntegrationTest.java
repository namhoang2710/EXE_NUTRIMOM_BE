package vn.nutrimom.dashboard;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;

/** Hai block lịch của dashboard mẹ bầu: {@code next_appointment} và {@code upcoming_reminders}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MomDashboardCalendarIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;

    @Test
    void dashboardSurfacesTheNextAppointmentAndUpcomingReminders() throws Exception {
        String momId = createUserAccount("0913700001", "Mom Dashboard");
        createPregnancy(momId);
        createReminder(momId, "Khám định kỳ", 3);
        createReminder(momId, "Tái khám", 10);

        mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.next_appointment.source").value("REMINDER"))
                .andExpect(jsonPath("$.data.next_appointment.title").value("Khám định kỳ"))
                .andExpect(jsonPath("$.data.upcoming_reminders", hasSize(2)))
                .andExpect(jsonPath("$.data.upcoming_reminders[0].title").value("Khám định kỳ"));
    }

    /** Chưa có mốc nào thì hai block rỗng nhưng endpoint vẫn 200 (spec mục 05). */
    @Test
    void dashboardStaysHealthyWithAnEmptyCalendar() throws Exception {
        String momId = createUserAccount("0913700011", "Mom Empty");
        createPregnancy(momId);

        mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.next_appointment").value((Object) null))
                .andExpect(jsonPath("$.data.upcoming_reminders", hasSize(0)));
    }

    /** Lịch không phụ thuộc thai kỳ: nhắc nhở vẫn hiện khi chưa tạo hồ sơ thai kỳ nào. */
    @Test
    void remindersShowUpEvenWithoutAPregnancy() throws Exception {
        String momId = createUserAccount("0913700021", "Mom No Pregnancy");
        createReminder(momId, "Khám tổng quát", 5);

        mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt(momId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pregnancy_summary").value((Object) null))
                .andExpect(jsonPath("$.data.upcoming_reminders", hasSize(1)))
                .andExpect(jsonPath("$.data.next_appointment.title").value("Khám tổng quát"));
    }

    // ----- Helpers -----

    private void createPregnancy(String userId) throws Exception {
        mockMvc.perform(post("/api/v1/pregnancies")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimated_due_date\":\"%s\"}"
                                .formatted(LocalDate.now(ZoneOffset.UTC).plusDays(100))))
                .andExpect(status().isCreated());
    }

    private void createReminder(String userId, String title, int daysFromNow) throws Exception {
        mockMvc.perform(post("/api/v1/calendar/reminders")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"ROUTINE_CHECKUP","title":"%s","starts_at":"%s"}
                                """.formatted(title, LocalDate.now(VN).plusDays(daysFromNow)
                                .atTime(9, 0).atZone(VN).toOffsetDateTime())))
                .andExpect(status().isCreated());
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
