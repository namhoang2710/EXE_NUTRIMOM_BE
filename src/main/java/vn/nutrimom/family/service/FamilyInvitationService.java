package vn.nutrimom.family.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.auth.service.PhoneNormalizer;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.config.FamilyInvitationProperties;
import vn.nutrimom.config.FrontendProperties;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyGroupStatus;
import vn.nutrimom.family.domain.FamilyInvitationEntity;
import vn.nutrimom.family.domain.FamilyInvitationStatus;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.domain.InvitationDeliveryStatus;
import vn.nutrimom.family.dto.AcceptFamilyInvitationRequest;
import vn.nutrimom.family.dto.CreateFamilyInvitationRequest;
import vn.nutrimom.family.dto.FamilyInvitationPreviewResponse;
import vn.nutrimom.family.dto.FamilyInvitationResponse;
import vn.nutrimom.family.dto.FamilyInvitationSummaryResponse;
import vn.nutrimom.family.dto.FamilyMemberResponse;
import vn.nutrimom.family.repository.FamilyGroupRepository;
import vn.nutrimom.family.repository.FamilyInvitationRepository;
import vn.nutrimom.family.repository.FamilyMemberRepository;
import vn.nutrimom.family.service.FamilyInvitationNotifier.DeliveryOutcome;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.NotificationService;

@Service
public class FamilyInvitationService {

    private final FamilyInvitationRepository invitations;
    private final FamilyMemberRepository members;
    private final FamilyGroupRepository groups;
    private final FamilyGroupService groupService;
    private final UserRepository users;
    private final PhoneNormalizer phoneNormalizer;
    private final InvitationTokenService tokens;
    private final FamilyInvitationNotifier notifier;
    private final NotificationService notifications;
    private final FrontendProperties frontend;
    private final FamilyInvitationProperties properties;

    public FamilyInvitationService(FamilyInvitationRepository invitations,
                                   FamilyMemberRepository members,
                                   FamilyGroupRepository groups,
                                   FamilyGroupService groupService,
                                   UserRepository users,
                                   PhoneNormalizer phoneNormalizer,
                                   InvitationTokenService tokens,
                                   FamilyInvitationNotifier notifier,
                                   NotificationService notifications,
                                   FrontendProperties frontend,
                                   FamilyInvitationProperties properties) {
        this.invitations = invitations;
        this.members = members;
        this.groups = groups;
        this.groupService = groupService;
        this.users = users;
        this.phoneNormalizer = phoneNormalizer;
        this.tokens = tokens;
        this.notifier = notifier;
        this.notifications = notifications;
        this.frontend = frontend;
        this.properties = properties;
    }

    /**
     * Tạo lời mời rồi gửi đi ngay.
     *
     * <p>Gửi <em>trong</em> transaction, không chờ {@code afterCommit}: {@code rawToken} chỉ tồn
     * tại trong bộ nhớ của lần gọi này, và ghi {@code sent_at}/{@code delivery_status} cùng một
     * lượt thì dữ liệu không bao giờ lệch. Cái giá là SMTP chậm giữ connection thêm vài giây —
     * chấp nhận được với một endpoint do chính chủ nhóm bấm và đã bị rate-limit.</p>
     *
     * <p>{@code token} vẫn được trả về nguyên như cũ. Giấu nó đi không mua được gì khi
     * {@code invite_url} ngay bên cạnh đã chứa chính nó, mà lại phá giao diện đang chạy — và khi
     * {@code delivery_status} không phải {@code SENT} thì copy tay là đường duy nhất còn lại.</p>
     */
    @Transactional
    public FamilyInvitationResponse create(
            String userId, CreateFamilyInvitationRequest request) {
        FamilyGroupEntity group = groupService.requireOwnedActiveGroup(userId);
        String phone = normalizePhone(request.invitedPhone());
        String email = normalizeEmail(request.invitedEmail());
        if ((phone == null) == (email == null)) {
            throw validation("Provide exactly one of invited_phone or invited_email.");
        }

        InvitationTokenService.TokenMaterial token = tokens.generate();
        FamilyInvitationEntity invitation = new FamilyInvitationEntity();
        invitation.setFamilyGroupId(group.getId());
        invitation.setInvitedPhone(phone);
        invitation.setInvitedEmail(email);
        invitation.setTokenHash(token.tokenHash());
        invitation.setRelationship(request.relationship());
        invitation.setScopes(request.scopes());
        invitation.setStatus(FamilyInvitationStatus.PENDING);
        invitation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(
                request.expiresInHours() == null
                        ? properties.getDefaultExpiryHours() : request.expiresInHours()));
        invitations.saveAndFlush(invitation);

        DeliveryOutcome outcome = notifier.deliver(invitation, token.rawToken(),
                users.findById(userId).map(UserEntity::getDisplayName).orElse(null));
        invitation.setDeliveryStatus(outcome.status());
        invitation.setSentAt(outcome.sentAt());
        invitations.save(invitation);

        return toResponse(invitation, token.rawToken());
    }

    /**
     * Xem trước lời mời trước khi bấm chấp nhận.
     *
     * <p>Yêu cầu đăng nhập: người được mời dù sao cũng phải có tài khoản sẵn mới accept được, nên
     * mở endpoint này ra public chỉ tặng thêm một bề mặt để dò token mà không đổi lại được gì.</p>
     *
     * <p>Hết hạn, đã dùng hay đã thu hồi đều trả 200 kèm {@code status} — màn hình cần nói được
     * "lời mời đã hết hạn, xin link mới" chứ không phải một trang lỗi trống. Chỉ token
     * <em>không tồn tại</em> mới là 404.</p>
     */
    @Transactional(readOnly = true)
    public FamilyInvitationPreviewResponse preview(String viewerUserId, String rawToken) {
        FamilyInvitationEntity invitation = invitations.findByTokenHash(tokens.hash(rawToken))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_INVITATION_TOKEN, "Invitation token is invalid."));
        FamilyGroupEntity group = groups.findById(invitation.getFamilyGroupId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_INVITATION_TOKEN, "Invitation token is invalid."));
        String inviter = users.findById(group.getOwnerUserId())
                .map(UserEntity::getDisplayName)
                .orElse(null);
        boolean byEmail = invitation.getInvitedEmail() != null;
        return new FamilyInvitationPreviewResponse(
                inviter,
                invitation.getRelationship().name(),
                FamilyScopeLabels.of(invitation.getRelationship()),
                scopeNames(invitation.getScopes()),
                FamilyScopeLabels.of(invitation.getScopes()),
                byEmail ? "EMAIL" : "PHONE",
                byEmail ? maskEmail(invitation.getInvitedEmail())
                        : maskPhone(invitation.getInvitedPhone()),
                invitation.getExpiresAt(),
                effectiveStatus(invitation));
    }

    @Transactional(readOnly = true)
    public List<FamilyInvitationSummaryResponse> list(String ownerUserId) {
        FamilyGroupEntity group = groupService.requireOwnedActiveGroup(ownerUserId);
        return invitations.findByFamilyGroupIdOrderByCreatedAtDesc(group.getId()).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Idempotent: thu hồi lại một lời mời đã thu hồi vẫn là 204, vì kết quả mong muốn đã đạt. */
    @Transactional
    public void revoke(String ownerUserId, String invitationId) {
        FamilyInvitationEntity invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Invitation was not found."));
        // Không thuộc nhóm mình sở hữu thì ra 404, không phải 403 — đúng quy ước chống IDOR đang
        // dùng ở FamilyMemberService.
        groupService.requireOwnedActiveGroup(ownerUserId, invitation.getFamilyGroupId());
        if (invitation.getStatus() == FamilyInvitationStatus.ACCEPTED) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_USED,
                    "An accepted invitation can no longer be revoked.");
        }
        if (invitation.getStatus() == FamilyInvitationStatus.REVOKED) {
            return;
        }
        invitation.setStatus(FamilyInvitationStatus.REVOKED);
        invitation.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        invitations.save(invitation);
    }

    @Transactional
    public FamilyMemberResponse accept(
            String userId, AcceptFamilyInvitationRequest request) {
        UserEntity user = users.findByIdForUpdate(userId)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "The authenticated account is unavailable."));
        FamilyInvitationEntity invitation = invitations
                .findByTokenHashForUpdate(tokens.hash(request.token()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INVITATION_TOKEN, "Invitation token is invalid."));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (invitation.getStatus() == FamilyInvitationStatus.REVOKED) {
            throw new BusinessException(ErrorCode.INVITATION_REVOKED, "Invitation has been revoked.");
        }
        if (invitation.getAcceptedAt() != null) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_USED, "Invitation token has already been used.");
        }
        if (!invitation.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED, "Invitation token has expired.");
        }

        FamilyGroupEntity group = groups.findByIdAndStatus(
                        invitation.getFamilyGroupId(), FamilyGroupStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_GROUP_NOT_FOUND, "The invitation's family group is unavailable."));
        if (group.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.OWNER_ALREADY_IN_GROUP, "The pregnancy owner does not need a family membership.");
        }
        requireMatchingInviteTarget(invitation, user);
        if (members.findByFamilyGroupIdAndUserIdAndStatus(
                group.getId(), userId, FamilyMemberStatus.ACTIVE).isPresent()) {
            throw new BusinessException(ErrorCode.FAMILY_MEMBER_EXISTS, "The account already has an active membership in this group.");
        }

        FamilyMemberEntity member = new FamilyMemberEntity();
        member.setFamilyGroupId(group.getId());
        member.setUserId(userId);
        member.setRelationship(invitation.getRelationship());
        member.setMembershipRole(invitation.getRelationship().membershipRole());
        member.setScopes(invitation.getScopes());
        member.setStatus(FamilyMemberStatus.ACTIVE);
        members.saveAndFlush(member);

        invitation.setAcceptedAt(now);
        invitation.setStatus(FamilyInvitationStatus.ACCEPTED);
        invitations.save(invitation);
        if (user.getOnboardingStatus() != OnboardingStatus.COMPLETED) {
            user.setOnboardingStatus(OnboardingStatus.COMPLETED);
            users.saveAndFlush(user);
        }
        notifyOwnerAccepted(group, user, member);
        return toMemberResponse(member);
    }

    /** Chủ nhóm cần biết lời mời đã tới nơi; nếu không họ chỉ có cách tự vào xem danh sách. */
    private void notifyOwnerAccepted(FamilyGroupEntity group, UserEntity user,
                                     FamilyMemberEntity member) {
        notifications.publish(group.getOwnerUserId(), NotificationType.FAMILY,
                "Lời mời đã được chấp nhận",
                (user.getDisplayName() == null ? "Một người thân" : user.getDisplayName())
                        + " đã tham gia nhóm gia đình của bạn.",
                "nutrimom://family/members/" + member.getId(),
                "FAMILY_MEMBER", member.getId());
    }

    private void requireMatchingInviteTarget(
            FamilyInvitationEntity invitation, UserEntity user) {
        boolean phoneMatches = invitation.getInvitedPhone() != null
                && invitation.getInvitedPhone().equals(user.getPhone());
        boolean emailMatches = invitation.getInvitedEmail() != null
                && user.getEmail() != null
                && invitation.getInvitedEmail().equalsIgnoreCase(user.getEmail());
        if (!phoneMatches && !emailMatches) {
            throw new BusinessException(ErrorCode.INVITATION_TARGET_MISMATCH, "Invitation token belongs to a different account.");
        }
    }

    private String normalizePhone(String phone) {
        return phone == null || phone.isBlank()
                ? null : phoneNormalizer.normalizeVietnamesePhone(phone);
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank()
                ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private FamilyInvitationResponse toResponse(
            FamilyInvitationEntity invitation, String rawToken) {
        return new FamilyInvitationResponse(
                invitation.getId(), invitation.getFamilyGroupId(),
                invitation.getInvitedPhone(), invitation.getInvitedEmail(),
                rawToken, frontend.inviteUrl(rawToken),
                invitation.getRelationship().name(),
                scopeNames(invitation.getScopes()),
                invitation.getStatus().name(),
                invitation.getDeliveryStatus() == null
                        ? null : invitation.getDeliveryStatus().name(),
                invitation.getSentAt(),
                invitation.getExpiresAt(), invitation.getCreatedAt());
    }

    private FamilyInvitationSummaryResponse toSummary(FamilyInvitationEntity invitation) {
        boolean byEmail = invitation.getInvitedEmail() != null;
        return new FamilyInvitationSummaryResponse(
                invitation.getId(),
                byEmail ? "EMAIL" : "PHONE",
                byEmail ? maskEmail(invitation.getInvitedEmail())
                        : maskPhone(invitation.getInvitedPhone()),
                invitation.getRelationship().name(),
                scopeNames(invitation.getScopes()),
                effectiveStatus(invitation),
                invitation.getDeliveryStatus() == null
                        ? null : invitation.getDeliveryStatus().name(),
                invitation.getSentAt(),
                invitation.getExpiresAt(), invitation.getCreatedAt());
    }

    /** EXPIRED không phải giá trị lưu trong DB, nó là so sánh với đồng hồ lúc đọc. */
    private String effectiveStatus(FamilyInvitationEntity invitation) {
        if (invitation.getStatus() == FamilyInvitationStatus.PENDING
                && !invitation.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            return "EXPIRED";
        }
        return invitation.getStatus().name();
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String maskPhone(String phone) {
        return phone.length() <= 4 ? "***" : "***" + phone.substring(phone.length() - 4);
    }

    private FamilyMemberResponse toMemberResponse(FamilyMemberEntity member) {
        return new FamilyMemberResponse(
                member.getId(), member.getFamilyGroupId(), member.getUserId(),
                member.getRelationship().name(), member.getMembershipRole().name(),
                scopeNames(member.getScopes()), member.getStatus().name(),
                member.getVersion(), member.getCreatedAt(), member.getUpdatedAt());
    }

    private List<String> scopeNames(Set<FamilyScope> scopes) {
        return scopes.stream().map(Enum::name).sorted().toList();
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
