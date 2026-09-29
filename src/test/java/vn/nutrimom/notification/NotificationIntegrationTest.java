package vn.nutrimom.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import vn.nutrimom.notification.domain.ActivityType;
import vn.nutrimom.notification.domain.ActivityVisibility;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.domain.PushDeviceEntity;
import vn.nutrimom.notification.repository.PushDeviceRepository;
import vn.nutrimom.notification.service.ActivityFeedService;
import vn.nutrimom.notification.service.NotificationService;
import vn.nutrimom.notification.service.PushTokenCipher;

/** Contract test của task 18: devices, notifications và activity feed. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired PushDeviceRepository devices;
    @Autowired PushTokenCipher cipher;
    @Autowired NotificationService notifications;
    @Autowired ActivityFeedService activityFeed;

    // ----- POST /devices -----

    @Test
    void registeringTheSameDeviceTwiceUpsertsInsteadOfDuplicating() throws Exception {
        String userId = account("0914000001", "Device Mom");

        registerDevice(userId, "ANDROID", "token-first", "device-1", "1.0.0", status().isCreated())
                .andExpect(jsonPath("$.data.device_id").value("device-1"))
                .andExpect(jsonPath("$.data.platform").value("ANDROID"))
                .andExpect(jsonPath("$.data.app_version").value("1.0.0"))
                // Token đã gửi lên thì không trả ngược lại, kể cả bản mã hoá.
                .andExpect(jsonPath("$.data.push_token").doesNotExist())
                .andExpect(jsonPath("$.data.push_token_cipher").doesNotExist());

        // Lần hai là cập nhật, không phải tạo mới.
        registerDevice(userId, "IOS", "token-second", "device-1", "1.2.0", status().isOk())
                .andExpect(jsonPath("$.data.platform").value("IOS"))
                .andExpect(jsonPath("$.data.app_version").value("1.2.0"));

        List<PushDeviceEntity> rows = devices.findByUserIdAndActiveTrue(userId);
        assertThat(rows).hasSize(1);
        PushDeviceEntity row = rows.get(0);
        assertThat(cipher.decrypt(row.getPushTokenCipher())).isEqualTo("token-second");
        assertThat(row.getPushTokenCipher()).doesNotContain("token-second");
        assertThat(row.getPushTokenHash()).isEqualTo(cipher.hash("token-second"));
    }

    @Test
    void aPushTokenBelongsToTheAccountThatRegisteredItLast() throws Exception {
        String first = account("0914000002", "Phone Owner A");
        String second = account("0914000003", "Phone Owner B");

        registerDevice(first, "ANDROID", "shared-token", "same-handset", null, status().isCreated());
        // Cùng chiếc máy, tài khoản khác đăng nhập: token phải chuyển chủ, nếu không máy sẽ nhận
        // push của tài khoản đã đăng xuất.
        registerDevice(second, "ANDROID", "shared-token", "same-handset", null, status().isCreated());

        assertThat(devices.findByUserIdAndActiveTrue(first)).isEmpty();
        assertThat(devices.findByUserIdAndActiveTrue(second)).hasSize(1);
    }

    @Test
    void unregisteringIsIdempotentAndScopedToTheCaller() throws Exception {
        String userId = account("0914000004", "Logout Mom");
        String other = account("0914000005", "Other Mom");
        registerDevice(userId, "WEB", "token-web", "device-web", null, status().isCreated());

        // Thiết bị của người khác không bị gỡ nhầm.
        mockMvc.perform(delete("/api/v1/devices/{deviceId}", "device-web").with(userJwt(other)))
                .andExpect(status().isNoContent());
        assertThat(devices.findByUserIdAndActiveTrue(userId)).hasSize(1);

        mockMvc.perform(delete("/api/v1/devices/{deviceId}", "device-web").with(userJwt(userId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/devices/{deviceId}", "device-web").with(userJwt(userId)))
                .andExpect(status().isNoContent());
        assertThat(devices.findByUserIdAndActiveTrue(userId)).isEmpty();
    }

    @Test
    void registerRejectsMissingPlatformOrBlankToken() throws Exception {
        String userId = account("0914000006", "Invalid Mom");

        mockMvc.perform(post("/api/v1/devices").with(userJwt(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"push_token":"t","device_id":"d"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/devices").with(userJwt(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"platform":"ANDROID","push_token":"   ","device_id":"d"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /**
     * Token không giải mã được (vd sau khi xoay {@code NUTRIMOM_PUSH_TOKEN_KEY}) chỉ được phép làm
     * mất cái push, không được kéo đổ thao tác nghiệp vụ.
     *
     * <p>Bước giải mã chạy eager ngay trong {@code publish()}, trước hook after-commit, nên lỗi này
     * nổi lên được cả trong test {@code @Transactional} dù push thật không bao giờ gửi.</p>
     */
    @Test
    void anUndecryptableDeviceTokenDoesNotBreakTheNotification() throws Exception {
        String userId = account("0914000061", "Rotated Key Mom");
        registerDevice(userId, "ANDROID", "token-truoc-khi-xoay-key", "device-cu", null,
                status().isCreated());
        corruptStoredToken(userId, "device-cu");

        publish(userId, "Vẫn phải tới nơi");

        mockMvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Vẫn phải tới nơi"));
    }

    @Test
    void oneBrokenDeviceDoesNotBlockTheOtherRecipientsOfAFanOut() throws Exception {
        String healthy = account("0914000062", "Healthy Device Admin");
        String broken = account("0914000063", "Broken Device Admin");
        registerDevice(healthy, "ANDROID", "token-con-tot", "device-tot", null,
                status().isCreated());
        registerDevice(broken, "IOS", "token-se-hong", "device-hong", null, status().isCreated());
        corruptStoredToken(broken, "device-hong");

        // Fan-out: cùng một sự kiện phát cho nhiều người nhận.
        publish(broken, "Thông báo chung");
        publish(healthy, "Thông báo chung");

        for (String recipient : List.of(healthy, broken)) {
            mockMvc.perform(get("/api/v1/notifications").with(userJwt(recipient)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].title").value("Thông báo chung"));
        }
    }

    // ----- GET /notifications -----

    @Test
    void listReturnsNewestFirstWithSpecFields() throws Exception {
        String userId = account("0914000011", "Feed Mom");
        publish(userId, "Thông báo cũ");
        publish(userId, "Thông báo mới");

        mockMvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].title").value("Thông báo mới"))
                .andExpect(jsonPath("$.data.items[0].type").value("SYSTEM"))
                .andExpect(jsonPath("$.data.items[0].body").exists())
                .andExpect(jsonPath("$.data.items[0].deep_link").value("nutrimom://notifications"))
                .andExpect(jsonPath("$.data.items[0].created_at").exists())
                .andExpect(jsonPath("$.data.items[0].read_at").doesNotExist())
                .andExpect(jsonPath("$.data.has_more").value(false));
    }

    /** Spec mục 22: "Cursor ổn định khi có insert mới". */
    @Test
    void cursorStaysStableWhenNewNotificationsArriveBetweenPages() throws Exception {
        String userId = account("0914000012", "Cursor Mom");
        publish(userId, "Thứ nhất");
        publish(userId, "Thứ hai");
        publish(userId, "Thứ ba");

        MvcResult firstPage = mockMvc.perform(get("/api/v1/notifications")
                        .param("limit", "2").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].title").value("Thứ ba"))
                .andExpect(jsonPath("$.data.items[1].title").value("Thứ hai"))
                .andExpect(jsonPath("$.data.has_more").value(true))
                .andReturn();
        String cursor = objectMapper.readTree(firstPage.getResponse().getContentAsString())
                .at("/data/next_cursor").stringValue();

        // Thông báo mới chen vào giữa hai lần gọi — trang sau không được vì thế mà lệch.
        publish(userId, "Thứ tư");

        mockMvc.perform(get("/api/v1/notifications")
                        .param("limit", "2").param("cursor", cursor).with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Thứ nhất"))
                .andExpect(jsonPath("$.data.has_more").value(false));
    }

    @Test
    void invalidCursorIsRejectedInsteadOfSilentlyReturningPageOne() throws Exception {
        String userId = account("0914000013", "Bad Cursor Mom");

        mockMvc.perform(get("/api/v1/notifications")
                        .param("cursor", "kh0ng-phai-cursor").with(userJwt(userId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unreadOnlyFiltersOutWhatHasBeenRead() throws Exception {
        String userId = account("0914000014", "Unread Mom");
        String read = publish(userId, "Đã đọc");
        publish(userId, "Chưa đọc");

        mockMvc.perform(post("/api/v1/notifications/{id}/read", read).with(userJwt(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Chưa đọc"));
    }

    // ----- read / read-all -----

    @Test
    void markingReadIsIdempotentAndKeepsTheFirstTimestamp() throws Exception {
        String userId = account("0914000021", "Read Mom");
        String id = publish(userId, "Một thông báo");

        MvcResult first = mockMvc.perform(post("/api/v1/notifications/{id}/read", id)
                        .with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_at").exists())
                .andReturn();
        String readAt = objectMapper.readTree(first.getResponse().getContentAsString())
                .at("/data/read_at").stringValue();

        mockMvc.perform(post("/api/v1/notifications/{id}/read", id).with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_at").value(readAt));
    }

    @Test
    void readAllIsIdempotentAndReportsHowManyChanged() throws Exception {
        String userId = account("0914000022", "Read All Mom");
        publish(userId, "A");
        publish(userId, "B");

        mockMvc.perform(post("/api/v1/notifications/read-all").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(2));

        mockMvc.perform(post("/api/v1/notifications/read-all").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(0));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true").with(userJwt(userId)))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /** Spec mục 22 "IDOR": người khác không đọc được, và trả 404 chứ không phải 403. */
    @Test
    void anotherUserCanNeitherSeeNorMarkTheNotification() throws Exception {
        String owner = account("0914000031", "Owner Mom");
        String stranger = account("0914000032", "Stranger Mom");
        String id = publish(owner, "Riêng tư");

        mockMvc.perform(post("/api/v1/notifications/{id}/read", id).with(userJwt(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/notifications").with(userJwt(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(post("/api/v1/notifications/read-all").with(userJwt(stranger)))
                .andExpect(jsonPath("$.data.updated").value(0));

        // Chủ sở hữu vẫn còn nguyên thông báo chưa đọc.
        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true").with(userJwt(owner)))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    // ----- GET /activity-feed -----

    @Test
    void activityFeedShowsOwnEventsAndHidesThemFromStrangers() throws Exception {
        String owner = account("0914000041", "Activity Mom");
        String stranger = account("0914000042", "Nobody");
        activityFeed.record(owner, null, null, ActivityType.CONTACT_COMPLETED,
                "Yêu cầu hỗ trợ đã được xử lý", ActivityVisibility.OWNER_ONLY);

        mockMvc.perform(get("/api/v1/activity-feed").with(userJwt(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("CONTACT_COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].title")
                        .value("Yêu cầu hỗ trợ đã được xử lý"))
                // Feed cố ý không có body/preview để không rò dữ liệu medical (spec mục 18).
                .andExpect(jsonPath("$.data.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.has_more").value(false));

        mockMvc.perform(get("/api/v1/activity-feed").with(userJwt(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ----- Luồng nghiệp vụ thật -----

    @Test
    void completingAContactRequestNotifiesItsOwner() throws Exception {
        String userId = account("0914000051", "Contact Mom");

        MvcResult created = mockMvc.perform(post("/api/v1/contact-requests").with(userJwt(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"ACCOUNT","message":"Quên mật khẩu."}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String requestId = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/id").stringValue();

        mockMvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(post("/api/v1/admin/contact-requests/{id}/complete", requestId)
                        .with(adminJwt()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("CONTACT"))
                .andExpect(jsonPath("$.data.items[0].title").value("Yêu cầu hỗ trợ đã được xử lý"))
                .andExpect(jsonPath("$.data.items[0].source_type").value("CONTACT_REQUEST"))
                .andExpect(jsonPath("$.data.items[0].source_id").value(requestId))
                .andExpect(jsonPath("$.data.items[0].deep_link")
                        .value("nutrimom://contact-requests/" + requestId))
                // Nội dung thắc mắc không được nhắc lại trong thông báo.
                .andExpect(jsonPath("$.data.items[0].body").value(
                        "Đội hỗ trợ đã hoàn tất yêu cầu của bạn. Mở ứng dụng để xem lại."));

        mockMvc.perform(get("/api/v1/activity-feed").with(userJwt(userId)))
                .andExpect(jsonPath("$.data.items[0].type").value("CONTACT_COMPLETED"));
    }

    // ----- helpers -----

    /** Mô phỏng ciphertext được mã hoá bằng một key khác: giải mã sẽ ném ngay khi dựng push. */
    private void corruptStoredToken(String userId, String deviceId) {
        PushDeviceEntity device = devices.findByUserIdAndDeviceId(userId, deviceId).orElseThrow();
        device.setPushTokenCipher("bm90LWEtdmFsaWQtY2lwaGVydGV4dA==");
        devices.saveAndFlush(device);
    }

    private String publish(String userId, String title) {
        return notifications.publish(userId, NotificationType.SYSTEM, title,
                "Nội dung thông báo.", "nutrimom://notifications", null, null).id();
    }

    private org.springframework.test.web.servlet.ResultActions registerDevice(
            String userId, String platform, String pushToken, String deviceId, String appVersion,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus) throws Exception {
        java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("platform", platform);
        body.put("push_token", pushToken);
        body.put("device_id", deviceId);
        if (appVersion != null) {
            body.put("app_version", appVersion);
        }
        return mockMvc.perform(post("/api/v1/devices").with(userJwt(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(expectedStatus);
    }

    private String account(String phone, String displayName) {
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

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject("admin").claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
