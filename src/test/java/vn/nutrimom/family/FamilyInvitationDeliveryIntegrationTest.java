package vn.nutrimom.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.family.repository.FamilyInvitationRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Lời mời có thật sự đi tới tay người được mời hay không.
 *
 * <p>Bật lại {@code email-enabled} vì profile test tắt mặc định — đây là bài duy nhất cần kênh
 * email thật sự chạy để đối chiếu {@code delivery_status}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.family.invitation.email-enabled=true")
class FamilyInvitationDeliveryIntegrationTest extends ApiIntegrationTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @MockitoBean EmailService email;
    @Autowired UserRepository users;
    @Autowired FamilyInvitationRepository invitations;

    @Test
    void invitingByEmailSendsTheHtmlInvitationAndReturnsAnAcceptUrl() throws Exception {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(true);
        Session owner = registerViaOtp(nextPhone(), "Invite Mom");
        createPregnancyAndGroup(owner);
        String invitedEmail = "nguoinha" + SEQ.incrementAndGet() + "@example.com";

        MvcResult created = inviteByEmail(owner, invitedEmail)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.delivery_status").value("SENT"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.sent_at").exists())
                .andReturn();

        String token = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/token").stringValue();
        String inviteUrl = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/invite_url").stringValue();

        ArgumentCaptor<String> acceptUrl = ArgumentCaptor.forClass(String.class);
        verify(email).sendFamilyInvitation(eq(invitedEmail), eq("Invite Mom"), anyString(),
                anyList(), acceptUrl.capture(), any());
        // Link phải là http(s) thường: mail client không mở được scheme riêng, và chính link này
        // sau sẽ tự mở app qua Universal Links khi có app.
        assertThat(acceptUrl.getValue()).startsWith("http://localhost:5173/family/invite?token=");
        assertThat(acceptUrl.getValue()).contains(token);
        assertThat(inviteUrl).isEqualTo(acceptUrl.getValue());
    }

    /**
     * Đăng ký chỉ cần phone HOẶC email, nên có tài khoản không có email. Mời bằng số điện thoại thì
     * sau khi bỏ SMS không còn kênh ngoài nào — phải nói thẳng là SKIPPED chứ không im lặng.
     */
    @Test
    void invitingByPhoneSkipsEmailButStillRaisesAnInAppNotification() throws Exception {
        Session owner = registerViaOtp(nextPhone(), "Phone Mom");
        Session relative = registerViaOtp(nextPhone(), "Phone Relative");
        assertThat(users.findById(relative.userId()).orElseThrow().getEmail()).isNull();
        createPregnancyAndGroup(owner);

        mockMvc.perform(post("/api/v1/family-invitations")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitationBody(
                                relative.phone(), null, "PARTNER",
                                Set.of("SHARED_CALENDAR"), 48))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.delivery_status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.sent_at").doesNotExist())
                // Chủ nhóm vẫn phải lấy được link để tự gửi.
                .andExpect(jsonPath("$.data.invite_url").exists());

        verifyNoInteractions(email);

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + relative.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("FAMILY"))
                .andExpect(jsonPath("$.data.items[0].title")
                        .value("Lời mời tham gia nhóm gia đình"));
    }

    /** SMTP hỏng không được phép làm hỏng cả lời mời — nó vẫn dùng được qua link. */
    @Test
    void aFailedEmailIsReportedRatherThanThrown() throws Exception {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(false);
        Session owner = registerViaOtp(nextPhone(), "Smtp Mom");
        createPregnancyAndGroup(owner);

        inviteByEmail(owner, "khonggui" + SEQ.incrementAndGet() + "@example.com")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.delivery_status").value("FAILED"))
                .andExpect(jsonPath("$.data.sent_at").doesNotExist())
                .andExpect(jsonPath("$.data.invite_url").exists());
    }

    @Test
    void previewShowsWhoInvitedYouWithoutLeakingTheGroup() throws Exception {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(true);
        Session owner = registerViaOtp(nextPhone(), "Preview Mom");
        Session guest = registerViaOtp(nextPhone(), "Preview Guest");
        createPregnancyAndGroup(owner);
        String invitedEmail = "xemtruoc" + SEQ.incrementAndGet() + "@example.com";
        String token = tokenOf(inviteByEmail(owner, invitedEmail).andReturn());

        mockMvc.perform(get("/api/v1/family-invitations/preview")
                        .param("token", token)
                        .header("Authorization", "Bearer " + guest.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviter_display_name").value("Preview Mom"))
                .andExpect(jsonPath("$.data.relationship_label").value("Chồng/bạn đời"))
                .andExpect(jsonPath("$.data.scope_labels[0]").value("Xem lịch khám và nhắc nhở"))
                .andExpect(jsonPath("$.data.target_type").value("EMAIL"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                // Che địa chỉ, và tuyệt đối không trả token hay bất kỳ id nội bộ nào.
                .andExpect(jsonPath("$.data.masked_target").value(
                        invitedEmail.charAt(0) + "***" + invitedEmail.substring(
                                invitedEmail.indexOf('@'))))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.family_group_id").doesNotExist())
                .andExpect(jsonPath("$.data.owner_user_id").doesNotExist())
                .andExpect(jsonPath("$.data.pregnancy_id").doesNotExist());
    }

    @Test
    void previewOfAnUnknownTokenIsNotFound() throws Exception {
        Session guest = registerViaOtp(nextPhone(), "Lost Guest");

        mockMvc.perform(get("/api/v1/family-invitations/preview")
                        .param("token", "khong-ton-tai")
                        .header("Authorization", "Bearer " + guest.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVALID_INVITATION_TOKEN"));
    }

    /** Hết hạn là trạng thái để hiển thị, không phải lỗi: màn hình cần nói "xin link mới". */
    @Test
    void anExpiredInvitationPreviewsAsExpiredInsteadOfFailing() throws Exception {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(true);
        Session owner = registerViaOtp(nextPhone(), "Expired Mom");
        Session guest = registerViaOtp(nextPhone(), "Expired Guest");
        createPregnancyAndGroup(owner);
        MvcResult created = inviteByEmail(owner, "hethan" + SEQ.incrementAndGet() + "@example.com")
                .andReturn();
        String token = tokenOf(created);
        String id = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/id").stringValue();
        invitations.findById(id).ifPresent(invitation -> {
            invitation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
            invitations.save(invitation);
        });

        mockMvc.perform(get("/api/v1/family-invitations/preview")
                        .param("token", token)
                        .header("Authorization", "Bearer " + guest.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"));
    }

    @Test
    void aRevokedInvitationCanNoLongerBeAccepted() throws Exception {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(true);
        Session owner = registerViaOtp(nextPhone(), "Revoke Mom");
        Session guest = registerViaOtp(nextPhone(), "Revoke Guest");
        createPregnancyAndGroup(owner);
        String invitedEmail = "thuhoi" + SEQ.incrementAndGet() + "@example.com";
        attachEmail(guest, invitedEmail);
        MvcResult created = inviteByEmail(owner, invitedEmail).andReturn();
        String token = tokenOf(created);
        String id = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/id").stringValue();

        mockMvc.perform(delete("/api/v1/family-invitations/{id}", id)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        // Thu hồi hai lần vẫn 204: kết quả mong muốn đã đạt rồi.
        mockMvc.perform(delete("/api/v1/family-invitations/{id}", id)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/family-invitations/preview")
                        .param("token", token)
                        .header("Authorization", "Bearer " + guest.accessToken()))
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + guest.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_REVOKED"));
    }

    @Test
    void ownerListsInvitationsWithMaskedTargetsAndNoRawToken() throws Exception {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(true);
        Session owner = registerViaOtp(nextPhone(), "List Mom");
        createPregnancyAndGroup(owner);
        inviteByEmail(owner, "danhsach" + SEQ.incrementAndGet() + "@example.com");

        mockMvc.perform(get("/api/v1/family-invitations")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].masked_target").exists())
                .andExpect(jsonPath("$.data[0].delivery_status").value("SENT"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                // Token thô chỉ sống đúng một lần ở response lúc tạo; DB chỉ giữ bản băm.
                .andExpect(jsonPath("$.data[0].token").doesNotExist())
                .andExpect(jsonPath("$.data[0].invite_url").doesNotExist());
    }

    @Test
    void anotherOwnerCannotRevokeYourInvitation() throws Exception {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(true);
        Session owner = registerViaOtp(nextPhone(), "Mine Mom");
        Session outsider = registerViaOtp(nextPhone(), "Other Mom");
        createPregnancyAndGroup(owner);
        createPregnancyAndGroup(outsider);
        String id = objectMapper.readTree(
                        inviteByEmail(owner, "rieng" + SEQ.incrementAndGet() + "@example.com")
                                .andReturn().getResponse().getContentAsString())
                .at("/data/id").stringValue();

        mockMvc.perform(delete("/api/v1/family-invitations/{id}", id)
                        .header("Authorization", "Bearer " + outsider.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptingAnInvitationNotifiesTheOwner() throws Exception {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(true);
        Session owner = registerViaOtp(nextPhone(), "Notify Mom");
        Session guest = registerViaOtp(nextPhone(), "Notify Guest");
        createPregnancyAndGroup(owner);
        String invitedEmail = "chapnhan" + SEQ.incrementAndGet() + "@example.com";
        attachEmail(guest, invitedEmail);
        String token = tokenOf(inviteByEmail(owner, invitedEmail).andReturn());

        mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + guest.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("FAMILY"))
                .andExpect(jsonPath("$.data.items[0].title").value("Lời mời đã được chấp nhận"));
    }

    // ----- dựng dữ liệu -----

    private static String nextPhone() {
        return String.format("09126%05d", SEQ.incrementAndGet());
    }

    /** Tài khoản đăng ký qua OTP chỉ có số điện thoại; gắn thêm email khi bài test cần. */
    private void attachEmail(Session session, String address) {
        UserEntity user = users.findById(session.userId()).orElseThrow();
        user.setEmail(address);
        users.save(user);
    }

    private org.springframework.test.web.servlet.ResultActions inviteByEmail(
            Session owner, String address) throws Exception {
        return mockMvc.perform(post("/api/v1/family-invitations")
                .header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InvitationBody(
                        null, address, "PARTNER", Set.of("SHARED_CALENDAR"), 48))));
    }

    private String tokenOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/token").stringValue();
    }

    private void createPregnancyAndGroup(Session session) throws Exception {
        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PregnancyBody(
                                LocalDate.now(ZoneOffset.UTC).plusDays(120)))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/family-groups")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    private record PregnancyBody(LocalDate estimatedDueDate) { }
    private record InvitationBody(String invitedPhone, String invitedEmail, String relationship,
                                  Set<String> scopes, int expiresInHours) { }
}
