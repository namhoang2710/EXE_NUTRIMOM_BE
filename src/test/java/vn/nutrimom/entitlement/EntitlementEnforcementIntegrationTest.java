package vn.nutrimom.entitlement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Lỗ nghiệp vụ mà task 17 sinh ra để vá: bảng giá hứa khoá tính năng, backend thì không chặn gì.
 *
 * <p>Ma trận ở đây chính là bảng quyền lợi trong {@code pricing-catalog.ts} của frontend. Bài
 * {@code planNinetyNineStillCannotUseFamilySharing} là bằng chứng chặn <em>đúng theo bảng giá</em>
 * chứ không phải chặn thô kiểu "cứ trả tiền là mở hết".</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// KHONG @Transactional: FamilyInvitationService.create chay tren TransactionTemplate voi
// PROPAGATION_REQUIRES_NEW, nen transaction long trong khong nhin thay nhom ma transaction cua test
// vua tao nhung chua commit. Cac bai family khac bo @Transactional cung vi ly do nay; cac bai
// khong dung chung du lieu vi moi bai mot so dien thoai rieng.
class EntitlementEnforcementIntegrationTest extends ApiIntegrationTestSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'");

    // ----- Lịch nhắc nhở: FREE không có, 99K trở lên có -----

    @Test
    void freeAccountCannotCreateAReminderAndIsToldWhichPlanToBuy() throws Exception {
        Session user = registerViaOtp("0933000001", "Free Reminder");

        createReminder(user)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PLAN_UPGRADE_REQUIRED"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.fields.feature").value("CALENDAR_REMINDERS"))
                // Gói RẺ NHẤT có bán tính năng này, không phải gói đắt nhất.
                .andExpect(jsonPath("$.error.fields.required_plan").value("PLAN_99K"))
                .andExpect(jsonPath("$.error.fields.current_plan").value("FREE"));
    }

    @Test
    void planNinetyNineCanCreateReminders() throws Exception {
        Session user = registerViaOtp("0933000002", "Basic Reminder");
        PlanFixtures.grant(subscriptions, user.userId(), PlanTier.PLAN_99K);

        createReminder(user).andExpect(status().isCreated());
    }

    /** Nút "thêm vào lịch" trên hồ sơ y tế là đường ghi thứ hai — quên chặn là cổng thủng. */
    @Test
    void theMedicalRecordShortcutIsGatedToo() throws Exception {
        Session user = registerViaOtp("0933000003", "Free Shortcut");

        mockMvc.perform(post("/api/v1/medical-records/{id}/reminders", "any-record-id")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reminderFromRecordBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PLAN_UPGRADE_REQUIRED"));
    }

    // ----- Liên kết gia đình: chỉ 399K -----

    @Test
    void freeAccountCannotStartAFamilyGroup() throws Exception {
        Session owner = registerViaOtp("0933000004", "Free Family");
        createPregnancy(owner);

        createFamilyGroup(owner)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PLAN_UPGRADE_REQUIRED"))
                .andExpect(jsonPath("$.error.fields.feature").value("FAMILY_SHARING"))
                .andExpect(jsonPath("$.error.fields.required_plan").value("PLAN_399K"));
    }

    /**
     * Mấu chốt của quyết định "chặn đúng như bảng giá": 99K <strong>có</strong> lịch nhắc nhưng
     * <strong>không</strong> có liên kết gia đình.
     */
    @Test
    void planNinetyNineStillCannotUseFamilySharing() throws Exception {
        Session owner = registerViaOtp("0933000005", "Basic Family");
        PlanFixtures.grant(subscriptions, owner.userId(), PlanTier.PLAN_99K);
        createPregnancy(owner);

        createFamilyGroup(owner)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.fields.current_plan").value("PLAN_99K"))
                .andExpect(jsonPath("$.error.fields.required_plan").value("PLAN_399K"));
    }

    @Test
    void planThreeNinetyNineUnlocksBothFeatures() throws Exception {
        Session owner = registerViaOtp("0933000006", "Full Plan");
        PlanFixtures.grant(subscriptions, owner.userId(), PlanTier.PLAN_399K);
        createPregnancy(owner);

        createReminder(owner).andExpect(status().isCreated());
        createFamilyGroup(owner).andExpect(status().isCreated());
        inviteByPhone(owner, "0933000099").andExpect(status().isCreated());
    }

    /**
     * Gói của CHỦ NHÓM trả tiền cho liên kết, nên người nhà đang ở FREE vẫn nhận lời mời được.
     *
     * <p>Chặn theo gói của người được mời là sai nghiệp vụ: họ không phải người mua.</p>
     */
    @Test
    void aFreeInviteeCanStillAcceptAPaidOwnersInvitation() throws Exception {
        Session owner = registerViaOtp("0933000007", "Paying Owner");
        Session partner = registerViaOtp("0933000008", "Free Partner");
        PlanFixtures.grant(subscriptions, owner.userId(), PlanTier.PLAN_399K);
        createPregnancy(owner);
        createFamilyGroup(owner).andExpect(status().isCreated());

        MvcResult invited = inviteByPhone(owner, partner.phone())
                .andExpect(status().isCreated()).andReturn();
        JsonNode data = objectMapper.readTree(invited.getResponse().getContentAsString()).at("/data");

        mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(data.at("/token").stringValue())))
                .andExpect(status().isOk());
    }

    // ----- Không ai bị nuốt mất dữ liệu khi hết hạn -----

    /**
     * Cổng chỉ chặn đường GHI.
     *
     * <p>Nhắc nhở tạo lúc còn gói phải đọc và xoá được sau khi gói hết hạn — nếu không thì bật cổng
     * đồng nghĩa với nuốt mất dữ liệu của người dùng.</p>
     */
    @Test
    void anExpiredAccountKeepsReadingAndDeletingWhatItAlreadyCreated() throws Exception {
        Session user = registerViaOtp("0933000009", "Lapsed Account");
        PlanFixtures.grant(subscriptions, user.userId(), PlanTier.PLAN_399K);

        MvcResult created = createReminder(user).andExpect(status().isCreated()).andReturn();
        String reminderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/id").stringValue();

        // Gói hết hiệu lực: bỏ dòng đang chạy rồi thay bằng một dòng đã quá hạn.
        subscriptions.deleteAll(subscriptions.findAllByUserIdOrderByCreatedAtDesc(user.userId()));
        PlanFixtures.grantExpired(subscriptions, user.userId(), PlanTier.PLAN_399K);

        mockMvc.perform(get("/api/v1/calendar/reminders/{id}", reminderId)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/calendar/reminders")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/calendar/reminders/{id}", reminderId)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isNoContent());

        // Nhưng tạo mới thì không còn được nữa.
        createReminder(user).andExpect(status().isForbidden());
    }

    // ----- helpers -----

    private ResultActions createReminder(Session user) throws Exception {
        return mockMvc.perform(post("/api/v1/calendar/reminders")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new ReminderBody("ROUTINE_CHECKUP", "Khám thai", startsAt()))));
    }

    private ResultActions createFamilyGroup(Session owner) throws Exception {
        return mockMvc.perform(post("/api/v1/family-groups")
                .header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private ResultActions inviteByPhone(Session owner, String phone) throws Exception {
        return mockMvc.perform(post("/api/v1/family-invitations")
                .header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InvitationBody(
                        phone, "PARTNER", Set.of("PREGNANCY_SUMMARY"), 48))));
    }

    private void createPregnancy(Session owner) throws Exception {
        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PregnancyBody(LocalDate.now(ZoneOffset.UTC).plusDays(100)))))
                .andExpect(status().isCreated());
    }

    private String reminderFromRecordBody() {
        return objectMapper.writeValueAsString(
                new ReminderFromRecordBody("ROUTINE_CHECKUP", startsAt()));
    }

    private String acceptBody(String token) {
        return objectMapper.writeValueAsString(new AcceptBody(token));
    }

    private String startsAt() {
        OffsetDateTime when = LocalDate.now(VN).plusDays(5).atTime(9, 0)
                .atZone(VN).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        return when.format(UTC_ISO);
    }

    private record ReminderBody(String type, String title, String startsAt) { }
    private record ReminderFromRecordBody(String type, String startsAt) { }
    private record PregnancyBody(LocalDate estimatedDueDate) { }
    private record AcceptBody(String token) { }
    private record InvitationBody(
            String invitedPhone, String relationship, Set<String> scopes, int expiresInHours) { }
}
