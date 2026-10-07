package vn.nutrimom.family.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.config.FamilyInvitationProperties;
import vn.nutrimom.config.FrontendProperties;
import vn.nutrimom.family.domain.FamilyInvitationEntity;
import vn.nutrimom.family.domain.InvitationDeliveryStatus;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.NotificationService;

/**
 * Đưa một lời mời tới tay người được mời.
 *
 * <p>Hai kênh, độc lập nhau:</p>
 * <ul>
 *   <li><strong>Email</strong> — chỉ khi mời bằng email. Gửi tới <em>chính địa chỉ chủ nhóm nhập</em>,
 *       cố ý không tra tài khoản rồi gửi sang địa chỉ đã đăng ký: làm vậy sẽ biến
 *       {@code delivery_status} thành chỗ dò "email này có tài khoản NutriMom hay không".</li>
 *   <li><strong>Thông báo in-app</strong> — cho cả hai kiểu mời, nếu tra được tài khoản khớp. Vì
 *       người được mời bắt buộc phải có tài khoản sẵn mới accept được, đây là kênh đến nơi đáng tin
 *       nhất. Khi có app mobile, {@code PushSender} phía sau {@code NotificationService} tự lo phần
 *       đẩy push mà module này không phải sửa gì.</li>
 * </ul>
 *
 * <p><strong>Hai kênh không chia sẻ số phận.</strong> Email hỏng, SMTP chưa cấu hình, hay cờ
 * {@code app.family.invitation.email-enabled} tắt đều không ngăn thông báo in-app được tạo — và
 * ngược lại. Trước đây cờ tắt gửi chặn cả hai, nên một môi trường không cấu hình SMTP làm người
 * được mời mất luôn lời mời trong app; đó là lỗi, không phải thiết kế.</p>
 *
 * <p>Mời bằng số điện thoại không có kênh ngoài nào — dự án chưa tích hợp nhà cung cấp SMS. Trường
 * hợp đó trả {@link InvitationDeliveryStatus#SKIPPED} để giao diện biết mà mời chủ nhóm tự gửi
 * link, thay vì im lặng để họ tưởng tin nhắn đã đi.</p>
 *
 * <p>Lớp này <strong>không bao giờ ném</strong>: lời mời đã nằm trong DB và vẫn dùng được qua link,
 * nên một cú SMTP hỏng không được phép làm hỏng cả thao tác.</p>
 */
@Service
public class FamilyInvitationNotifier {

    private static final Logger log = LoggerFactory.getLogger(FamilyInvitationNotifier.class);

    private final EmailService email;
    private final NotificationService notifications;
    private final UserRepository users;
    private final FrontendProperties frontend;
    private final FamilyInvitationProperties properties;

    public FamilyInvitationNotifier(EmailService email,
                                    NotificationService notifications,
                                    UserRepository users,
                                    FrontendProperties frontend,
                                    FamilyInvitationProperties properties) {
        this.email = email;
        this.notifications = notifications;
        this.users = users;
        this.frontend = frontend;
        this.properties = properties;
    }

    public DeliveryOutcome deliver(FamilyInvitationEntity invitation, String rawToken,
                                   String inviterDisplayName) {
        InvitationDeliveryStatus status = sendEmail(invitation, inviterDisplayName, rawToken);
            notifyInApp(invitation, inviterDisplayName);
        return new DeliveryOutcome(status,
                status == InvitationDeliveryStatus.SENT ? OffsetDateTime.now(ZoneOffset.UTC) : null);
    }

    /**
     * Cờ tắt gửi được kiểm <em>trước</em> khi chạm tới {@link EmailService} chứ không phải sau.
     *
     * <p>Không có bean {@code JavaMailSender} thì {@code sendHtml} rơi vào nhánh mock và trả
     * {@code false} — gate ở phía sau sẽ biến mọi lời mời thành {@code FAILED} thay vì
     * {@code SKIPPED}, tức là báo "có gì đó hỏng" cho một cấu hình cố ý.</p>
     */
    private InvitationDeliveryStatus sendEmail(FamilyInvitationEntity invitation,
                                               String inviterDisplayName, String rawToken) {
        if (!properties.isEmailEnabled() || invitation.getInvitedEmail() == null) {
            logDelivery(invitation, "EMAIL", InvitationDeliveryStatus.SKIPPED, null);
            return InvitationDeliveryStatus.SKIPPED;
        }
        try {
            boolean sent = email.sendFamilyInvitation(
                    invitation.getInvitedEmail(),
                    inviterDisplayName,
                    FamilyScopeLabels.of(invitation.getRelationship()),
                    FamilyScopeLabels.of(invitation.getScopes()),
                    frontend.inviteUrl(rawToken),
                    invitation.getExpiresAt());
            InvitationDeliveryStatus status = sent
                    ? InvitationDeliveryStatus.SENT : InvitationDeliveryStatus.FAILED;
            logDelivery(invitation, "EMAIL", status, null);
            return status;
        } catch (RuntimeException ex) {
            logDelivery(invitation, "EMAIL", InvitationDeliveryStatus.FAILED, ex);
            return InvitationDeliveryStatus.FAILED;
        }
    }

    /**
     * Một dòng log cho mỗi kênh, đủ để lần ra sự cố giao hàng mà không rò gì.
     *
     * <p>Cố ý KHÔNG có: raw token, invite URL, email hay số điện thoại người nhận. Lời mời được
     * nhận dạng bằng {@code invitation_id} — tra ra mọi thứ còn lại từ DB khi thật sự cần, dưới
     * quyền truy cập của DB chứ không phải quyền đọc log.</p>
     */
    private void logDelivery(FamilyInvitationEntity invitation, String channel,
                             InvitationDeliveryStatus status, RuntimeException failure) {
        log.info("family_invitation_delivery invitation_id={} channel={} delivery_status={} error={}",
                invitation.getId(), channel, status,
                failure == null ? "none" : failure.getClass().getSimpleName());
    }

    /**
     * Không tìm thấy tài khoản khớp thì im lặng bỏ qua — lời mời vẫn hợp lệ, và việc phản hồi khác
     * nhau giữa "có tài khoản" và "không" chính là oracle liệt kê tài khoản.
     *
     * <p><strong>Deep link mang id, không mang token.</strong> Bản cũ ghi
     * {@code ?token=<raw-token>} vào {@code notifications.deep_link}, tức là ghi thẳng một token
     * dùng được vào DB dưới dạng chữ thường — vô hiệu hoá chính lý do tồn tại của cột
     * {@code token_hash}, và token còn nằm lại đó sau khi lời mời đã được chấp nhận hay thu hồi.
     * Người được mời mở lời mời bằng id qua nhóm endpoint {@code /family-invitations/{id}/...},
     * vốn phân quyền bằng email/sđt của tài khoản đang đăng nhập chứ không bằng việc giữ bí mật.</p>
     */
    private void notifyInApp(FamilyInvitationEntity invitation, String inviterDisplayName) {
        try {
            Optional<UserEntity> invitee = invitee(invitation);
            invitee.ifPresent(user -> notifications.publish(
                    user.getId(), NotificationType.FAMILY,
                    "Lời mời tham gia nhóm gia đình",
                    (inviterDisplayName == null ? "Một thành viên" : inviterDisplayName)
                            + " mời bạn cùng theo dõi thai kỳ.",
                    "nutrimom://family/invitations/" + invitation.getId(),
                    "FAMILY_INVITATION", invitation.getId()));
            logDelivery(invitation, "IN_APP", invitee.isPresent()
                    ? InvitationDeliveryStatus.SENT : InvitationDeliveryStatus.SKIPPED, null);
        } catch (RuntimeException ex) {
            logDelivery(invitation, "IN_APP", InvitationDeliveryStatus.FAILED, ex);
        }
    }

    private Optional<UserEntity> invitee(FamilyInvitationEntity invitation) {
        if (invitation.getInvitedEmail() != null) {
            return users.findByEmailIgnoreCase(invitation.getInvitedEmail());
        }
        if (invitation.getInvitedPhone() != null) {
            return users.findByPhone(invitation.getInvitedPhone());
        }
        return Optional.empty();
    }

    /** @param sentAt chỉ có giá trị khi thật sự gửi được, để màn hình không khoe một mốc giờ giả. */
    public record DeliveryOutcome(InvitationDeliveryStatus status, OffsetDateTime sentAt) {
    }
}
