package vn.nutrimom.family;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.family.domain.FamilyInvitationEntity;
import vn.nutrimom.family.repository.FamilyInvitationRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Người được mời tự mở và chấp nhận lời mời bằng id, không cần token.
 *
 * <p>Đây là lối vào đi cùng thông báo in-app. Nó tồn tại để deep link khỏi phải mang token thô;
 * phân quyền ở đây là email/sđt của tài khoản đang đăng nhập, không phải việc giữ một bí mật.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FamilyInvitationRecipientIntegrationTest extends ApiIntegrationTestSupport {

    /** DB dùng chung cả class nên mỗi test phải có số điện thoại riêng, nếu không 409 trùng. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired UserRepository users;
    @Autowired FamilyInvitationRepository invitations;

    private Session owner;
    private Session invitee;

    @BeforeEach
    void setUp() throws Exception {
        owner = registerViaOtp(nextPhone(), "Recipient Mom");
        invitee = registerViaOtp(nextPhone(), "Recipient Dad");
        createPregnancyAndGroup(owner);
    }

    @Test
    void theInviteeSeesThePendingInvitationAndAcceptsItByIdWithoutAToken() throws Exception {
        String invitationId = invite(invitee.phone());

        mockMvc.perform(get("/api/v1/family-invitations/received")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(invitationId))
                .andExpect(jsonPath("$.data[0].inviter_display_name").value("Recipient Mom"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                // Trạng thái SMTP của chủ nhóm không phải việc của người được mời.
                .andExpect(jsonPath("$.data[0].delivery_status").doesNotExist())
                .andExpect(jsonPath("$.data[0].sent_at").doesNotExist());

        mockMvc.perform(get("/api/v1/family-invitations/{id}/preview", invitationId)
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviter_display_name").value("Recipient Mom"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.family_group_id").doesNotExist());

        mockMvc.perform(post("/api/v1/family-invitations/{id}/accept", invitationId)
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value(invitee.userId()))
                .andExpect(jsonPath("$.data.relationship").value("PARTNER"));

        // Hộp thư chỉ chứa thứ bấm được, nên lời mời đã dùng phải rời khỏi danh sách.
        mockMvc.perform(get("/api/v1/family-invitations/received")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * Id không phải bí mật — nó nằm trong deep link thông báo và trong danh sách của chủ nhóm — nên
     * người lạ phải nhận 404, không phải 403. Trả 403 sẽ biến endpoint thành chỗ dò id tồn tại.
     */
    @Test
    void anotherAccountGetsNotFoundRatherThanForbidden() throws Exception {
        String invitationId = invite(invitee.phone());
        Session stranger = registerViaOtp(nextPhone(), "Recipient Stranger");

        mockMvc.perform(get("/api/v1/family-invitations/{id}/preview", invitationId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/family-invitations/{id}/accept", invitationId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/family-invitations/received")
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * Hộp thư và màn chi tiết cố ý bất đối xứng: danh sách chỉ chứa thứ hành động được, còn
     * {@code /{id}/preview} luôn giải thích được — kể cả khi người ta mở một thông báo cũ.
     */
    @Test
    void anExpiredInvitationLeavesTheInboxButStillPreviews() throws Exception {
        String invitationId = invite(invitee.phone());
        expire(invitationId);

        mockMvc.perform(get("/api/v1/family-invitations/received")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/v1/family-invitations/{id}/preview", invitationId)
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"));
    }

    /** Thu hồi cũng rút lời mời khỏi hộp thư, không để lại một dòng bấm vào là lỗi. */
    @Test
    void aRevokedInvitationLeavesTheInboxAndCannotBeAcceptedById() throws Exception {
        String invitationId = invite(invitee.phone());

        mockMvc.perform(delete("/api/v1/family-invitations/{id}", invitationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/family-invitations/received")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(post("/api/v1/family-invitations/{id}/accept", invitationId)
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_REVOKED"));
    }

    /**
     * Mời bằng email tìm được tài khoản bất kể hoa thường: lời mời lưu dạng đã chuẩn hoá còn email
     * trên tài khoản thì không, nên phép so khớp phải chịu được chênh lệch đó.
     */
    @Test
    void anEmailInvitationIsFoundRegardlessOfLetterCase() throws Exception {
        String address = "Recipient.Mixed" + SEQ.incrementAndGet() + "@Example.COM";
        attachEmail(invitee, address);

        String invitationId = inviteByEmail(address.toUpperCase());

        mockMvc.perform(get("/api/v1/family-invitations/received")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(invitationId))
                .andExpect(jsonPath("$.data[0].target_type").value("EMAIL"));

        mockMvc.perform(post("/api/v1/family-invitations/{id}/accept", invitationId)
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk());
    }

    /** Token vẫn dùng được như cũ — link trong email không bị lối vào theo id thay thế. */
    @Test
    void theEmailTokenPathStillWorksAlongsideTheIdPath() throws Exception {
        MvcResult created = createInvitation(invitee.phone(), null);
        String token = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/token").stringValue();

        mockMvc.perform(get("/api/v1/family-invitations/preview")
                        .param("token", token)
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + invitee.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptBody(token))))
                .andExpect(status().isOk());
    }

    /**
     * Chuông thông báo phải nhảy số ngay khi lời mời tới.
     *
     * <p>{@code unread-count} mới chỉ được kiểm chung chung ở {@code NotificationIntegrationTest},
     * nơi thông báo do chính bài test tạo ra. Ở đây nó đi qua đường thật: chủ nhóm bấm mời, và
     * người được mời thấy số tăng mà không phải mở danh sách.</p>
     */
    @Test
    void receivingAnInvitationBumpsTheUnreadBell() throws Exception {
        long before = unreadCount(invitee);

        invite(invitee.phone());

        assertThat(unreadCount(invitee)).isEqualTo(before + 1);
    }

    /**
     * Địa chỉ dán từ chỗ khác, kèm khoảng trắng hai đầu — ra 422, không phải lời mời.
     *
     * <p>Bài {@link #anEmailInvitationIsFoundRegardlessOfLetterCase} phủ nửa hoa/thường của phép
     * chuẩn hoá. Nửa khoảng trắng thì <strong>không</strong> đi tới được chỗ chuẩn hoá:
     * {@code @Email} trên {@code CreateFamilyInvitationRequest} chạy trước service, và
     * {@code " an@example.com "} không khớp pattern của nó. Cái {@code trim()} trong
     * {@code normalizeEmail} chỉ còn phục vụ email đọc ra từ bảng {@code users}.</p>
     *
     * <p>Bài này ghim hành vi đang chạy để nó không đổi một cách tình cờ. Nếu muốn chủ nhóm dán
     * được địa chỉ kèm khoảng trắng thì phải cắt ở tầng deserialize, trước bean validation — đó là
     * một thay đổi contract, không phải một bài test.</p>
     */
    @Test
    void anEmailWithSurroundingWhitespaceIsRejectedBeforeItReachesNormalisation() throws Exception {
        String address = "Recipient.Spaced" + SEQ.incrementAndGet() + "@Example.COM";
        attachEmail(invitee, address);

        mockMvc.perform(post("/api/v1/family-invitations")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitationBody(
                                null, " " + address + " ", "PARTNER",
                                Set.of("SHARED_CALENDAR"), 48))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/family-invitations/received")
                        .header("Authorization", "Bearer " + invitee.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private long unreadCount(Session session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/count").asLong();
    }

    // ----- dựng dữ liệu -----

    private static String nextPhone() {
        return String.format("09133%05d", SEQ.incrementAndGet());
    }

    private String invite(String phone) throws Exception {
        return idOf(createInvitation(phone, null));
    }

    private String inviteByEmail(String email) throws Exception {
        return idOf(createInvitation(null, email));
    }

    private MvcResult createInvitation(String phone, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/family-invitations")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitationBody(
                                phone, email, "PARTNER", Set.of("SHARED_CALENDAR"), 48))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String idOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.at("/data/id").stringValue();
    }

    /** Đẩy hạn về quá khứ thẳng trong DB: đợi 48 giờ thì bài test không chạy nổi. */
    private void expire(String invitationId) {
        FamilyInvitationEntity invitation = invitations.findById(invitationId).orElseThrow();
        invitation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        invitations.saveAndFlush(invitation);
    }

    /** Đăng ký bằng OTP chỉ cho tài khoản có phone, nên email phải gắn thêm ở đây. */
    private void attachEmail(Session session, String address) {
        UserEntity user = users.findById(session.userId()).orElseThrow();
        user.setEmail(address);
        users.saveAndFlush(user);
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
                                  Set<String> scopes, Integer expiresInHours) { }

    private record AcceptBody(String token) { }
}
