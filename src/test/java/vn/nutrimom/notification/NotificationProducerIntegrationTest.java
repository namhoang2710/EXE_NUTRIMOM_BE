package vn.nutrimom.notification;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;

/**
 * Ai được báo khi có việc xảy ra ở luồng tư vấn và hộp thư hỗ trợ.
 *
 * <p>Điểm quan trọng nhất ở đây là mốc giờ hẹn: với yêu cầu RANDOM, chuyên gia mới là người chọn
 * khung giờ, nên thông báo gửi cho user bắt buộc phải nói rõ giờ — nếu không user dễ lỡ buổi tư vấn
 * do người khác sắp xếp.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationProducerIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;

    @Test
    void randomRequestReachesEveryExpertOfThatSpecialtyAndNobodyElse() throws Exception {
        String userId = createUserAccount("0916000001", "Random Mom");
        String health1 = createExpert("0916000002", "HEALTH", "BS Suc Khoe 1");
        String health2 = createExpert("0916000003", "HEALTH", "BS Suc Khoe 2");
        String psychology = createExpert("0916000004", "PSYCHOLOGY", "BS Tam Ly");

        createRandom(userId, "HEALTH").andExpect(status().isCreated());

        for (String expert : List.of(health1, health2)) {
            mockMvc.perform(get("/api/v1/notifications").with(expertJwt(expert)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].type").value("CONSULTATION"))
                    .andExpect(jsonPath("$.data.items[0].title")
                            .value("Có yêu cầu tư vấn mới đang chờ"))
                    .andExpect(jsonPath("$.data.items[0].deep_link")
                            .value("nutrimom://expert/consultations"));
        }

        // Chuyên khoa khác không bị làm phiền.
        mockMvc.perform(get("/api/v1/notifications").with(expertJwt(psychology)))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        // Người đặt không tự nhận thông báo về yêu cầu của chính mình.
        mockMvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void acceptingARandomRequestTellsTheUserTheExactAppointmentTime() throws Exception {
        String userId = createUserAccount("0916000011", "Miss Nothing Mom");
        String expertId = createExpert("0916000012", "OBSTETRICS", "BS San Khoa");
        LocalDate date = LocalDate.now(VN).plusDays(2);

        MvcResult created = createRandom(userId, "OBSTETRICS")
                .andExpect(status().isCreated()).andReturn();
        String requestId = readData(created, "/data/id");

        mockMvc.perform(post("/api/v1/expert/consultation-requests/{id}/accept", requestId)
                        .with(expertJwt(expertId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slot_date":"%s","start_time":"09:00"}
                                """.formatted(date)))
                .andExpect(status().isOk());

        // Giờ hẹn do chuyên gia chọn, nên phải nằm ngay trong body chứ không bắt user mở app mới biết.
        mockMvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title")
                        .value("Chuyên gia đã tiếp nhận yêu cầu tư vấn"))
                .andExpect(jsonPath("$.data.items[0].body").value(
                        "Buổi tư vấn của bạn được xếp lúc 09:00 ngày " + date.format(DATE)
                                + ". Vui lòng có mặt đúng giờ."))
                .andExpect(jsonPath("$.data.items[0].deep_link")
                        .value("nutrimom://consultations/" + requestId));
    }

    @Test
    void directBookingTellsTheChosenExpertWhenItIs() throws Exception {
        String userId = createUserAccount("0916000021", "Direct Mom");
        String expertId = createExpert("0916000022", "HEALTH", "BS Duoc Chon");
        LocalDate date = LocalDate.now(VN).plusDays(3);

        mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"DIRECT","expert_user_id":"%s",
                                 "slot_date":"%s","start_time":"14:30"}
                                """.formatted(expertId, date)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/notifications").with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Bạn có lịch tư vấn mới"))
                .andExpect(jsonPath("$.data.items[0].body").value(
                        "Một người dùng vừa đặt lịch tư vấn với bạn lúc 14:30 ngày "
                                + date.format(DATE) + "."));

        // User tự đặt thì không cần báo lại chính họ.
        mockMvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void cancellingAClaimedRequestNotifiesTheExpertNotTheUser() throws Exception {
        String userId = createUserAccount("0916000031", "Cancel Mom");
        String expertId = createExpert("0916000032", "HEALTH", "BS Bi Huy");
        LocalDate date = LocalDate.now(VN).plusDays(4);

        MvcResult created = mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"DIRECT","expert_user_id":"%s",
                                 "slot_date":"%s","start_time":"10:00"}
                                """.formatted(expertId, date)))
                .andExpect(status().isCreated()).andReturn();
        String requestId = readData(created, "/data/id");

        mockMvc.perform(post("/api/v1/consultation-requests/{id}/cancel", requestId)
                        .with(userJwt(userId)))
                .andExpect(status().isOk());

        // Chuyên gia có 2 thông báo: đặt lịch rồi huỷ. User vẫn không có cái nào.
        mockMvc.perform(get("/api/v1/notifications").with(expertJwt(expertId)))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].title").value("Một buổi tư vấn đã bị huỷ"));
        mockMvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void randomRequestStillWaitingForAnExpertNotifiesNobodyWhenCancelled() throws Exception {
        String userId = createUserAccount("0916000041", "Early Cancel Mom");
        String expertId = createExpert("0916000042", "HEALTH", "BS Chua Nhan");

        MvcResult created = createRandom(userId, "HEALTH")
                .andExpect(status().isCreated()).andReturn();
        String requestId = readData(created, "/data/id");

        mockMvc.perform(post("/api/v1/consultation-requests/{id}/cancel", requestId)
                        .with(userJwt(userId)))
                .andExpect(status().isOk());

        // Chuyên gia chỉ có đúng thông báo broadcast lúc đầu, không có thêm thông báo huỷ:
        // chưa ai nhận yêu cầu đó nên không có chuyên gia cụ thể nào để báo.
        mockMvc.perform(get("/api/v1/notifications").with(expertJwt(expertId)))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title")
                        .value("Có yêu cầu tư vấn mới đang chờ"));
    }

    @Test
    void newContactRequestNotifiesEveryActiveAdmin() throws Exception {
        String userId = createUserAccount("0916000051", "Contact Mom");
        String adminA = createAdminAccount("0916000052", "Admin A");
        String adminB = createAdminAccount("0916000053", "Admin B");

        MvcResult created = mockMvc.perform(post("/api/v1/contact-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"ACCOUNT","message":"Toi quen mat khau"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String requestId = readData(created, "/data/id");

        for (String admin : List.of(adminA, adminB)) {
            mockMvc.perform(get("/api/v1/notifications").with(adminJwt(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].type").value("CONTACT"))
                    .andExpect(jsonPath("$.data.items[0].title").value("Có yêu cầu hỗ trợ mới"))
                    .andExpect(jsonPath("$.data.items[0].deep_link")
                            .value("nutrimom://admin/contact-requests/" + requestId))
                    // Nội dung thắc mắc không lọt vào thông báo (có thể hiện trên màn hình khoá).
                    .andExpect(jsonPath("$.data.items[0].body")
                            .value("Một người dùng vừa gửi yêu cầu hỗ trợ. Mở hộp thư để xem và liên hệ lại."));
        }

        mockMvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ----- helpers -----

    private ResultActions createRandom(String userId, String specialty) throws Exception {
        return mockMvc.perform(post("/api/v1/consultation-requests")
                .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"assignment_type":"RANDOM","specialty":"%s"}
                        """.formatted(specialty)));
    }

    private String createExpert(String phone, String specialty, String fullName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/experts")
                        .with(adminJwt("admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","password":"password123","full_name":"%s",
                                 "specialty":"%s","title":"Chuyen gia","workplace":"NutriMom",
                                 "years_of_experience":5}
                                """.formatted(phone, fullName, specialty)))
                .andExpect(status().isCreated())
                .andReturn();
        return readData(result, "/data/user_id");
    }

    private String createUserAccount(String phone, String displayName) {
        return createAccount(phone, displayName, UserRole.USER);
    }

    private String createAdminAccount(String phone, String displayName) {
        return createAccount(phone, displayName, UserRole.ADMIN);
    }

    private String createAccount(String phone, String displayName, UserRole role) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName(displayName);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(role));
        return users.saveAndFlush(user).getId();
    }

    private String readData(MvcResult result, String pointer) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at(pointer).stringValue();
    }

    private RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("ADMIN")))
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
