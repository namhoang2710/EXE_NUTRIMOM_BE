package vn.nutrimom.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.common.email.EmailService.MailResult;
import vn.nutrimom.family.repository.FamilyInvitationRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Email chỉ được rời tiến trình sau khi lời mời đã commit.
 *
 * <p>Thư không rollback được. Gửi bên trong transaction nghĩa là một cú rollback muộn để lại người
 * được mời cầm một lá thư trỏ tới lời mời không tồn tại — và họ không có cách nào biết.</p>
 *
 * <p><strong>Bắt buộc bật {@code email-enabled}.</strong> Profile test tắt cờ này, nên
 * {@code sendEmail} trả {@code SKIPPED} trước khi chạm {@link EmailService} và mọi
 * {@code verifyNoInteractions(email)} đều xanh một cách rỗng tuếch, bất kể code đúng hay sai.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.family.invitation.email-enabled=true")
class FamilyInvitationRollbackIntegrationTest extends ApiIntegrationTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @MockitoBean EmailService email;
    @MockitoSpyBean FamilyInvitationRepository invitations;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    /**
     * A — bất biến thứ tự: lúc {@link EmailService} được gọi, dòng lời mời đã commit.
     *
     * <p>Bài này đỏ trên bản cũ (gửi trong transaction) — đó là điều làm nó thành test hồi quy
     * thật, chứ không phải một bài mô tả lại code hiện tại.</p>
     */
    @Test
    void theEmailLeavesOnlyAfterTheInvitationIsCommitted() throws Exception {
        Session owner = registerViaOtp(nextPhone(), "Commit Mom");
        createPregnancyAndGroup(owner);
        String invitedEmail = "saucommit" + SEQ.incrementAndGet() + "@example.com";
        int[] visibleRows = { -1 };
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenAnswer(call -> {
                    visibleRows[0] = countFromAnotherConnection(invitedEmail);
                    return MailResult.ok();
                });

        mockMvc.perform(invite(owner, invitedEmail))
                .andExpect(status().isCreated())
                // Response vẫn trả delivery_status đồng bộ: FE dùng nó làm công cụ chẩn đoán đầu
                // tiên, nên pha 2 không được phép làm field này biến mất.
                .andExpect(jsonPath("$.data.delivery_status").value("SENT"))
                .andExpect(jsonPath("$.data.sent_at").exists());

        assertThat(visibleRows[0])
                .describedAs("lời mời phải commit xong trước khi email rời tiến trình")
                .isEqualTo(1);
    }

    /**
     * B — rollback thật: transaction đổ sau khi dòng đã được flush, và không có thư nào đi.
     *
     * <p>A một mình không đủ. A chỉ chứng minh "email đi sau commit" ở đường thành công; B chứng
     * minh đường hỏng không để lại thư ma. Ngược lại B một mình cũng không đủ — nó vẫn xanh trên
     * một implementation gửi sau commit nhưng xử lý sai rollback muộn.</p>
     */
    @Test
    void aRollbackAfterFlushLeavesNoInvitationAndNoEmail() throws Exception {
        Session owner = registerViaOtp(nextPhone(), "Rollback Mom");
        createPregnancyAndGroup(owner);
        String invitedEmail = "rollback" + SEQ.incrementAndGet() + "@example.com";
        doAnswer(call -> {
            call.callRealMethod();
            // Đổ SAU khi dòng đã nằm trong transaction: đúng hình dạng của một commit hỏng.
            throw new DataAccessResourceFailureException("db went away");
        }).when(invitations).saveAndFlush(any());

        mockMvc.perform(invite(owner, invitedEmail))
                .andExpect(status().is5xxServerError());

        verifyNoInteractions(email);
        assertThat(countInvitations(invitedEmail)).isZero();
    }

    /**
     * C — pha 3 hỏng: DB chết ngay sau khi thư đã đi.
     *
     * <p>Ghim quyết định "không ném ở pha 3". Ném ở đó chỉ biến một dòng DB lệch thành 500 cho chủ
     * nhóm <em>cộng</em> một lá thư ma trong hộp thư người được mời — lời mời thì vẫn nằm trong DB
     * và vẫn dùng được qua link.</p>
     */
    @Test
    void aFailureRecordingDeliveryStillReturnsTheRealOutcome() throws Exception {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(MailResult.ok());
        Session owner = registerViaOtp(nextPhone(), "Record Mom");
        createPregnancyAndGroup(owner);
        String invitedEmail = "ghinhan" + SEQ.incrementAndGet() + "@example.com";
        doThrow(new DataAccessResourceFailureException("db went away"))
                .when(invitations).recordDelivery(anyString(), any(), any());

        mockMvc.perform(invite(owner, invitedEmail))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.delivery_status").value("SENT"))
                .andExpect(jsonPath("$.data.sent_at").exists());

        // Dòng trong DB thì vắng field — đó là cái giá đã chấp nhận, và FE xử vắng mặt như FAILED.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.family_invitations"
                        + " WHERE invited_email = ? AND delivery_status IS NULL",
                Integer.class, invitedEmail)).isEqualTo(1);
    }

    // ----- dựng dữ liệu -----

    /**
     * Đếm bằng một connection <strong>riêng</strong>, lấy thẳng từ {@link DataSource}.
     *
     * <p>Không dùng {@link JdbcTemplate} ở đây: nó đi qua {@code DataSourceUtils}, nên khi thread
     * đang có transaction nó mượn lại đúng connection đó và nhìn thấy cả dòng chưa commit — bài
     * test sẽ xanh y hệt nhau trên code đúng lẫn code sai. H2 chạy READ_COMMITTED, nên một
     * connection riêng chỉ thấy những gì đã commit.</p>
     */
    private int countFromAnotherConnection(String invitedEmail) throws SQLException {
        try (Connection db = dataSource.getConnection();
             PreparedStatement query = db.prepareStatement(
                     "SELECT count(*) FROM app.family_invitations WHERE invited_email = ?")) {
            query.setString(1, invitedEmail);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private int countInvitations(String invitedEmail) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.family_invitations WHERE invited_email = ?",
                Integer.class, invitedEmail);
    }

    private static String nextPhone() {
        return String.format("09144%05d", SEQ.incrementAndGet());
    }

    private org.springframework.test.web.servlet.RequestBuilder invite(
            Session owner, String invitedEmail) throws Exception {
        return post("/api/v1/family-invitations")
                .header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InvitationBody(
                        null, invitedEmail, "PARTNER", Set.of("SHARED_CALENDAR"), 48)));
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
}
