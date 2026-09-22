package vn.nutrimom.consultation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConsultationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;

    @Test
    void adminCreatesExpertThenUserDiscoversAndSeesSpecialties() throws Exception {
        String expertId = createExpert("0912000001", "PSYCHOLOGY", "BS Tam Ly");
        String userId = createUserAccount("0912000002", "Mom A");

        mockMvc.perform(get("/api/v1/experts").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].user_id").value(expertId))
                .andExpect(jsonPath("$.data[0].full_name").value("BS Tam Ly"))
                .andExpect(jsonPath("$.data[0].specialty").value("PSYCHOLOGY"))
                .andExpect(jsonPath("$.data[0].average_rating").value(0));

        mockMvc.perform(get("/api/v1/experts/{id}", expertId).with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.years_of_experience").value(5));

        mockMvc.perform(get("/api/v1/reference-data/specialties").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value("PSYCHOLOGY"));
    }

    @Test
    void directBookingFlipsSlotAndRejectsDoubleBooking() throws Exception {
        String expertId = createExpert("0912000011", "OBSTETRICS", "BS San Khoa");
        String slotId = createSlot(expertId, "2026-12-01", "09:00:00", "09:30:00");
        String user1 = createUserAccount("0912000012", "Mom 1");
        String user2 = createUserAccount("0912000013", "Mom 2");

        mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(user1)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"DIRECT","expert_user_id":"%s","slot_id":"%s"}
                                """.formatted(expertId, slotId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONSULTATION"))
                .andExpect(jsonPath("$.data.slot.id").value(slotId));

        mockMvc.perform(get("/api/v1/experts/{id}/slots", expertId)
                        .param("date", "2026-12-01").with(userJwt(user1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("BOOKED"));

        mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(user2)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"DIRECT","expert_user_id":"%s","slot_id":"%s"}
                                """.formatted(expertId, slotId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_UNAVAILABLE"));

        // Bộ lọc slot phía chuyên gia: theo trạng thái và theo ngày
        mockMvc.perform(get("/api/v1/expert/slots").param("status", "BOOKED")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("BOOKED"));
        mockMvc.perform(get("/api/v1/expert/slots").param("status", "OPEN")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/expert/slots").param("date", "2026-12-02")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void randomFlowPoolAcceptCompleteThenReviewUpdatesAverage() throws Exception {
        String expertId = createExpert("0912000021", "HEALTH", "BS Suc Khoe");
        String slotId = createSlot(expertId, "2026-12-02", "10:00:00", "10:30:00");
        String userId = createUserAccount("0912000022", "Mom R");

        MvcResult created = mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"RANDOM","specialty":"HEALTH","note":"xin tu van"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_EXPERT"))
                .andReturn();
        String requestId = readData(created, "/data/id");

        mockMvc.perform(get("/api/v1/expert/consultation-requests")
                        .param("type", "pool").with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(requestId));

        mockMvc.perform(post("/api/v1/expert/consultation-requests/{id}/accept", requestId)
                        .with(expertJwt(expertId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot_id\":\"%s\"}".formatted(slotId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONSULTATION"))
                .andExpect(jsonPath("$.data.slot.id").value(slotId));

        mockMvc.perform(post("/api/v1/expert/consultation-requests/{id}/complete", requestId)
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/consultation-requests/{id}/review", requestId)
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"comment\":\"tot\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rating").value(4));

        mockMvc.perform(get("/api/v1/experts/{id}", expertId).with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.average_rating").value(4.0))
                .andExpect(jsonPath("$.data.rating_count").value(1));

        // Đánh giá lần hai -> 409
        mockMvc.perform(post("/api/v1/consultation-requests/{id}/review", requestId)
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVIEW_ALREADY_EXISTS"));

        // Chuyên gia xem được đầy đủ review
        mockMvc.perform(get("/api/v1/expert/reviews").with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rating").value(4))
                .andExpect(jsonPath("$.data.items[0].comment").value("tot"));

        // Lọc review theo số sao
        mockMvc.perform(get("/api/v1/expert/reviews").param("rating", "4")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1));
        mockMvc.perform(get("/api/v1/expert/reviews").param("rating", "5")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(0));
        // Lọc chỉ đánh giá có nhận xét
        mockMvc.perform(get("/api/v1/expert/reviews").param("has_comment", "true")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1));
    }

    @Test
    void assignedListSortsByAppointmentAndFiltersHistory() throws Exception {
        String expertId = createExpert("0912000071", "OBSTETRICS", "BS G");
        String slotLater = createSlot(expertId, "2026-12-12", "09:00:00", "09:30:00");
        String slotEarlier = createSlot(expertId, "2026-12-11", "09:00:00", "09:30:00");
        String user1 = createUserAccount("0912000072", "Alice");
        String user2 = createUserAccount("0912000073", "Bob");

        String reqLater = bookDirect(user1, expertId, slotLater);
        bookDirect(user2, expertId, slotEarlier);

        // Mặc định PENDING_CONSULTATION, sắp theo giờ hẹn tăng dần (11/12 trước 12/12)
        mockMvc.perform(get("/api/v1/expert/consultation-requests").with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(2))
                .andExpect(jsonPath("$.data.items[0].slot.slot_date").value("2026-12-11"))
                .andExpect(jsonPath("$.data.items[1].slot.slot_date").value("2026-12-12"));

        // Tìm theo tên user
        mockMvc.perform(get("/api/v1/expert/consultation-requests").param("q", "ali")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].user_display_name").value("Alice"));

        // Hoàn thành 1 buổi rồi lọc lịch sử COMPLETED
        mockMvc.perform(post("/api/v1/expert/consultation-requests/{id}/complete", reqLater)
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/expert/consultation-requests").param("status", "COMPLETED")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(reqLater));
    }

    @Test
    void secondExpertAcceptIsRejectedAsAlreadyClaimed() throws Exception {
        String expertA = createExpert("0912000031", "PSYCHOLOGY", "BS A");
        String expertB = createExpert("0912000032", "PSYCHOLOGY", "BS B");
        String slotA = createSlot(expertA, "2026-12-03", "08:00:00", "08:30:00");
        String slotB = createSlot(expertB, "2026-12-03", "08:00:00", "08:30:00");
        String userId = createUserAccount("0912000033", "Mom C");

        MvcResult created = mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"RANDOM","specialty":"PSYCHOLOGY"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String requestId = readData(created, "/data/id");

        mockMvc.perform(post("/api/v1/expert/consultation-requests/{id}/accept", requestId)
                        .with(expertJwt(expertA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot_id\":\"%s\"}".formatted(slotA)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/expert/consultation-requests/{id}/accept", requestId)
                        .with(expertJwt(expertB)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot_id\":\"%s\"}".formatted(slotB)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_ALREADY_CLAIMED"));
    }

    @Test
    void reviewNotAllowedBeforeCompletedAndRowLevelIsolation() throws Exception {
        String expertId = createExpert("0912000041", "HEALTH", "BS D");
        String slotId = createSlot(expertId, "2026-12-04", "14:00:00", "14:30:00");
        String user1 = createUserAccount("0912000042", "Mom 1");
        String user2 = createUserAccount("0912000043", "Mom 2");

        MvcResult created = mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(user1)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"DIRECT","expert_user_id":"%s","slot_id":"%s"}
                                """.formatted(expertId, slotId)))
                .andExpect(status().isCreated())
                .andReturn();
        String requestId = readData(created, "/data/id");

        // Chưa hoàn thành -> không được đánh giá
        mockMvc.perform(post("/api/v1/consultation-requests/{id}/review", requestId)
                        .with(userJwt(user1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVIEW_NOT_ALLOWED"));

        // User khác không thấy request này -> 404
        mockMvc.perform(get("/api/v1/consultation-requests/{id}", requestId).with(userJwt(user2)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // ----- helpers -----

    private String createExpert(String phone, String specialty, String fullName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/experts")
                        .with(adminJwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","password":"password123","full_name":"%s",
                                 "specialty":"%s","title":"Chuyen gia","workplace":"NutriMom",
                                 "years_of_experience":5}
                                """.formatted(phone, fullName, specialty)))
                .andExpect(status().isCreated())
                .andReturn();
        return readData(result, "/data/user_id");
    }

    private String createSlot(String expertUserId, String date, String start, String end)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expert/slots")
                        .with(expertJwt(expertUserId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slot_date":"%s","start_time":"%s","end_time":"%s"}
                                """.formatted(date, start, end)))
                .andExpect(status().isCreated())
                .andReturn();
        return readData(result, "/data/id");
    }

    private String bookDirect(String userId, String expertUserId, String slotId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"DIRECT","expert_user_id":"%s","slot_id":"%s"}
                                """.formatted(expertUserId, slotId)))
                .andExpect(status().isCreated())
                .andReturn();
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

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject("admin").claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private RequestPostProcessor expertJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("EXPERT")))
                .authorities(new SimpleGrantedAuthority("ROLE_EXPERT"));
    }
}
