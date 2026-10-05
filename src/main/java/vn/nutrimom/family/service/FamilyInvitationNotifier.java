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
        if (!properties.isSendEnabled()) {
            return new DeliveryOutcome(InvitationDeliveryStatus.SKIPPED, null);
        }
        String inviteUrl = frontend.inviteUrl(rawToken);
        InvitationDeliveryStatus status = sendEmail(invitation, inviterDisplayName, inviteUrl);
        notifyInApp(invitation, rawToken, inviterDisplayName);
        return new DeliveryOutcome(status,
                status == InvitationDeliveryStatus.SENT ? OffsetDateTime.now(ZoneOffset.UTC) : null);
    }

    private InvitationDeliveryStatus sendEmail(FamilyInvitationEntity invitation,
                                               String inviterDisplayName, String inviteUrl) {
        if (invitation.getInvitedEmail() == null) {
            return InvitationDeliveryStatus.SKIPPED;
        }
        try {
            boolean sent = email.sendFamilyInvitation(
                    invitation.getInvitedEmail(),
                    inviterDisplayName,
                    FamilyScopeLabels.of(invitation.getRelationship()),
                    FamilyScopeLabels.of(invitation.getScopes()),
                    inviteUrl,
                    invitation.getExpiresAt());
            return sent ? InvitationDeliveryStatus.SENT : InvitationDeliveryStatus.FAILED;
        } catch (RuntimeException ex) {
            log.warn("Không gửi được email mời cho lời mời {}: {}",
                    invitation.getId(), ex.getMessage());
            return InvitationDeliveryStatus.FAILED;
        }
    }

    /**
     * Không tìm thấy tài khoản khớp thì im lặng bỏ qua — lời mời vẫn hợp lệ, và việc phản hồi khác
     * nhau giữa "có tài khoản" và "không" chính là oracle liệt kê tài khoản.
     */
    private void notifyInApp(FamilyInvitationEntity invitation, String rawToken,
                             String inviterDisplayName) {
        try {
            invitee(invitation).ifPresent(user -> notifications.publish(
                    user.getId(), NotificationType.FAMILY,
                    "Lời mời tham gia nhóm gia đình",
                    (inviterDisplayName == null ? "Một thành viên" : inviterDisplayName)
                            + " mời bạn cùng theo dõi thai kỳ.",
                    "nutrimom://family/invitations?token=" + rawToken,
                    "FAMILY_INVITATION", invitation.getId()));
        } catch (RuntimeException ex) {
            log.warn("Không tạo được thông báo in-app cho lời mời {}: {}",
                    invitation.getId(), ex.getMessage());
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
