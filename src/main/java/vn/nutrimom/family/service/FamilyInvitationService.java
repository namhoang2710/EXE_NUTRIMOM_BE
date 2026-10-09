package vn.nutrimom.family.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
import vn.nutrimom.family.dto.AcceptFamilyInvitationRequest;
import vn.nutrimom.family.dto.CreateFamilyInvitationRequest;
import vn.nutrimom.family.dto.FamilyInvitationPreviewResponse;
import vn.nutrimom.family.dto.FamilyInvitationResponse;
import vn.nutrimom.family.dto.FamilyInvitationSummaryResponse;
import vn.nutrimom.family.dto.FamilyMemberResponse;
import vn.nutrimom.family.dto.ReceivedFamilyInvitationResponse;
import vn.nutrimom.family.repository.FamilyGroupRepository;
import vn.nutrimom.family.repository.FamilyInvitationRepository;
import vn.nutrimom.family.repository.FamilyMemberRepository;
import vn.nutrimom.family.service.FamilyInvitationNotifier.DeliveryOutcome;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.NotificationService;

@Service
public class FamilyInvitationService {

    private static final Logger log = LoggerFactory.getLogger(FamilyInvitationService.class);

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
    private final TransactionTemplate tx;

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
                                   FamilyInvitationProperties properties,
                                   PlatformTransactionManager transactions) {
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
        this.tx = new TransactionTemplate(transactions);
        // REQUIRES_NEW, không phải REQUIRED mặc định: hôm nay không có transaction ngoài nào bọc
        // create (caller duy nhất là FamilyInvitationController), nhưng với REQUIRED thì mai mốt
        // ai gọi create từ trong một transaction là pha 1 lặng lẽ join vào đó — email lại gửi
        // trước commit y như cũ, và không bài test nào đỏ.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Tạo lời mời rồi gửi đi.
     *
     * <p><strong>Email chỉ rời tiến trình sau khi transaction đã commit.</strong> Gửi bên trong
     * transaction thì một cú rollback muộn — commit hỏng, hay bất kỳ ai sau này bọc {@code create}
     * trong một transaction lớn hơn — để lại người được mời cầm một lá thư trỏ tới lời mời không
     * tồn tại. Thư không rollback được, nên nó phải đi sau.</p>
     *
     * <p>Ba pha, cố ý không dùng {@code @TransactionalEventListener(AFTER_COMMIT)}:
     * {@link FamilyInvitationResponse} là record bất biến và được dựng xong trước khi callback
     * chạy, nên callback không với tới được — response sẽ mất hẳn field {@code delivery_status}
     * (Jackson cấu hình {@code non_null} nên bỏ luôn key), đúng thứ FE đang dùng làm công cụ chẩn
     * đoán đầu tiên.</p>
     *
     * <ol>
     *   <li>Trong transaction: lưu lời mời và tạo thông báo in-app. Hai thứ này chia sẻ số phận —
     *       lời mời rollback thì thông báo rollback theo.</li>
     *   <li>Ngoài transaction: gửi email.</li>
     *   <li>Transaction riêng: ghi lại {@code delivery_status}/{@code sent_at}.</li>
     * </ol>
     *
     * <p>{@code rawToken} vẫn chỉ tồn tại trong bộ nhớ của lần gọi này: {@code tokens.generate()}
     * là CPU thuần nên được hoist lên đầu hàm, sống trong frame này và được lambda pha 1 bắt. Nó
     * không đi vào {@link Draft} hay bất kỳ carrier nào khác.</p>
     *
     * <p>{@code token} vẫn được trả về nguyên như cũ. Giấu nó đi không mua được gì khi
     * {@code invite_url} ngay bên cạnh đã chứa chính nó, mà lại phá giao diện đang chạy — và khi
     * {@code delivery_status} không phải {@code SENT} thì copy tay là đường duy nhất còn lại.</p>
     */
    public FamilyInvitationResponse create(
            String userId, CreateFamilyInvitationRequest request) {
        String phone = normalizePhone(request.invitedPhone());
        String email = normalizeEmail(request.invitedEmail());
        if ((phone == null) == (email == null)) {
            throw validation("Provide exactly one of invited_phone or invited_email.");
        }
        InvitationTokenService.TokenMaterial token = tokens.generate();

        // execute chỉ trả về SAU commit, nên mọi dòng dưới nó đã đứng ngoài transaction.
        Draft draft = tx.execute(transaction -> {
            FamilyGroupEntity group = groupService.requireOwnedActiveGroup(userId);
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
            String inviterName = users.findById(userId)
                    .map(UserEntity::getDisplayName).orElse(null);
            notifier.notifyInApp(invitation, inviterName);
            return new Draft(invitation, inviterName);
        });

        DeliveryOutcome outcome = notifier.sendEmail(
                draft.invitation(), token.rawToken(), draft.inviterName());
        recordDelivery(draft.invitation().getId(), outcome);

        // Dựng từ giá trị trong bộ nhớ, không đọc lại DB: đọc lại tốn một query và có thể trả về
        // REVOKED cho chính response vừa tạo, nếu chủ nhóm thu hồi trong lúc SMTP đang chạy.
        return toResponse(draft.invitation(), outcome, token.rawToken());
    }

    /**
     * Kết quả pha 1.
     *
     * <p>{@code rawToken} cố ý KHÔNG nằm ở đây — nó sống trong frame của {@link #create}.</p>
     */
    private record Draft(FamilyInvitationEntity invitation, String inviterName) { }

    /**
     * Pha 3: ghi kết quả gửi, trong transaction riêng của nó.
     *
     * <p>Hỏng ở đây (DB chết ngay sau khi email đã đi) chỉ được log, không được ném. Ném chỉ biến
     * "một dòng DB lệch" thành "500 cho chủ nhóm + một lá thư ma trong hộp thư người được mời" —
     * lời mời vẫn nằm trong DB và vẫn dùng được qua link.</p>
     */
    private void recordDelivery(String invitationId, DeliveryOutcome outcome) {
        try {
            tx.executeWithoutResult(transaction -> invitations.recordDelivery(
                    invitationId, outcome.status(), outcome.sentAt()));
        } catch (RuntimeException ex) {
            log.warn("family_invitation_delivery_not_recorded invitation_id={} "
                    + "delivery_status={} error={}",
                    invitationId, outcome.status(), ex.getClass().getSimpleName());
        }
    }

    /**
     * Xem trước lời mời theo token trong link email.
     *
     * <p>Không nhận id người xem, và đó là quyết định chứ không phải thiếu sót: giữ token
     * <em>chính là</em> phân quyền trên đường này. Tầng web vẫn bắt đăng nhập — đó là lớp chống dò
     * token — nhưng ai khớp với lời mời thì để {@link #accept} quyết. Lý do đầy đủ nằm ở javadoc
     * của {@code FamilyInvitationController.preview}.</p>
     *
     * <p>Hết hạn, đã dùng hay đã thu hồi đều trả 200 kèm {@code status} — màn hình cần nói được
     * "lời mời đã hết hạn, xin link mới" chứ không phải một trang lỗi trống. Chỉ token
     * <em>không tồn tại</em> mới là 404.</p>
     */
    @Transactional(readOnly = true)
    public FamilyInvitationPreviewResponse preview(String rawToken) {
        FamilyInvitationEntity invitation = invitations.findByTokenHash(tokens.hash(rawToken))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_INVITATION_TOKEN, "Invitation token is invalid."));
        return toPreview(invitation);
    }

    /**
     * Xem trước theo id, cho người được mời đi từ thông báo in-app.
     *
     * <p>Không lọc trạng thái như {@link #received}: hết hạn / đã thu hồi / đã dùng đều trả 200
     * kèm {@code status}. Hai endpoint bất đối xứng là cố ý — danh sách chỉ chứa thứ bấm được, màn
     * chi tiết thì luôn giải thích được, kể cả khi người ta mở một thông báo cũ.</p>
     */
    @Transactional(readOnly = true)
    public FamilyInvitationPreviewResponse previewById(String viewerUserId, String invitationId) {
        UserEntity viewer = users.findById(viewerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "The authenticated account is unavailable."));
        FamilyInvitationEntity invitation = invitations.findById(invitationId)
                .orElseThrow(this::invitationNotFound);
        if (!isAddressedTo(invitation, viewer)) {
            throw invitationNotFound();
        }
        return toPreview(invitation);
    }

    /**
     * Lời mời đang chờ chính người đang đăng nhập xử lý.
     *
     * <p>Tên người mời lấy theo lô: ba truy vấn cố định (lời mời → nhóm → tên chủ nhóm) thay vì
     * hai truy vấn mỗi dòng.</p>
     */
    @Transactional(readOnly = true)
    public List<ReceivedFamilyInvitationResponse> received(String viewerUserId) {
        UserEntity viewer = users.findById(viewerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "The authenticated account is unavailable."));
        List<FamilyInvitationEntity> pending = invitations.findReceived(
                normalizeEmail(viewer.getEmail()), viewer.getPhone(),
                OffsetDateTime.now(ZoneOffset.UTC));
        if (pending.isEmpty()) {
            return List.of();
        }

        Map<String, String> ownerByGroup = new HashMap<>();
        groups.findAllById(pending.stream()
                        .map(FamilyInvitationEntity::getFamilyGroupId).distinct().toList())
                .forEach(group -> ownerByGroup.put(group.getId(), group.getOwnerUserId()));
        Map<String, String> nameByOwner = users
                .findDisplayNamesByIdIn(Set.copyOf(ownerByGroup.values())).stream()
                .collect(HashMap::new,
                        (map, view) -> map.put(view.getId(), view.getDisplayName()),
                        HashMap::putAll);

        return pending.stream()
                .map(invitation -> toReceived(invitation,
                        nameByOwner.get(ownerByGroup.get(invitation.getFamilyGroupId()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FamilyInvitationSummaryResponse> list(String ownerUserId) {
        FamilyGroupEntity group = groupService.requireOwnedActiveGroup(ownerUserId);
        return invitations.findByFamilyGroupIdOrderByCreatedAtDesc(group.getId()).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Idempotent: thu hồi lại một lời mời đã thu hồi vẫn là 204, vì kết quả mong muốn đã đạt.
     *
     * <p>Khoá dòng ngay từ lúc đọc, cùng dòng mà {@link #accept} khoá. Đọc không khoá rồi mới ghi
     * là cách để chủ nhóm thu hồi và người được mời chấp nhận cùng thắng: check
     * {@code status == ACCEPTED} dưới đây sẽ đọc một dòng mà transaction accept chưa commit, rồi
     * người ghi sau đè lên người ghi trước.</p>
     */
    @Transactional
    public void revoke(String ownerUserId, String invitationId) {
        FamilyInvitationEntity invitation = invitations.findByIdForUpdate(invitationId)
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

    /**
     * Chấp nhận bằng token trong link email.
     *
     * <p>Thứ tự kiểm ở đây là: trạng thái trước, đối tượng sau. Sở hữu token <em>chính là</em>
     * phân quyền, nên người gọi xứng đáng nhận câu trả lời cụ thể "lời mời đã bị thu hồi" thay vì
     * một lỗi chung chung. {@link #acceptById} đảo ngược thứ tự này, xem lý do ở đó.</p>
     */
    @Transactional
    public FamilyMemberResponse accept(
            String userId, AcceptFamilyInvitationRequest request) {
        UserEntity user = requireActiveUser(userId);
        return acceptLocked(user, invitations
                .findByTokenHashForUpdate(tokens.hash(request.token()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INVITATION_TOKEN, "Invitation token is invalid.")));
    }

    /**
     * Chấp nhận bằng id, cho người được mời đi từ thông báo in-app.
     *
     * <p>Kiểm đối tượng <strong>trước</strong> mọi kiểm trạng thái, và trả 404 chứ không 403: id
     * không phải bí mật — nó nằm trong deep link thông báo và trong danh sách của chủ nhóm. Trả
     * 403 ở đây sẽ biến endpoint thành chỗ dò "id này có tồn tại không". Đường token thì ngược
     * lại, vì ở đó người gọi đã chứng minh họ giữ bí mật.</p>
     *
     * <p>Thứ tự khoá giống hệt {@link #accept}: dòng user trước, dòng lời mời sau. Đảo lại sẽ
     * deadlock với nhau khi một người bấm cả hai lối vào từ hai thiết bị.</p>
     */
    @Transactional
    public FamilyMemberResponse acceptById(String userId, String invitationId) {
        UserEntity user = requireActiveUser(userId);
        FamilyInvitationEntity invitation = invitations.findByIdForUpdate(invitationId)
                .orElseThrow(this::invitationNotFound);
        if (!isAddressedTo(invitation, user)) {
            throw invitationNotFound();
        }
        return acceptLocked(user, invitation);
    }

    /**
     * Phần dùng chung của hai lối vào, chạy khi dòng lời mời đã bị khoá.
     *
     * <p>{@link #requireMatchingInviteTarget} vẫn được gọi ở đây kể cả khi {@link #acceptById} đã
     * kiểm một lần: hai phép so sánh chuỗi trong bộ nhớ rẻ hơn là luồn một cờ "đã kiểm rồi" xuyên
     * qua hàm này.</p>
     */
    private FamilyMemberResponse acceptLocked(UserEntity user, FamilyInvitationEntity invitation) {
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
        if (group.getOwnerUserId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.OWNER_ALREADY_IN_GROUP, "The pregnancy owner does not need a family membership.");
        }
        requireMatchingInviteTarget(invitation, user);
        if (members.findByFamilyGroupIdAndUserIdAndStatus(
                group.getId(), user.getId(), FamilyMemberStatus.ACTIVE).isPresent()) {
            throw new BusinessException(ErrorCode.FAMILY_MEMBER_EXISTS, "The account already has an active membership in this group.");
        }

        FamilyMemberEntity member = new FamilyMemberEntity();
        member.setFamilyGroupId(group.getId());
        member.setUserId(user.getId());
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

    private UserEntity requireActiveUser(String userId) {
        return users.findByIdForUpdate(userId)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "The authenticated account is unavailable."));
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
        if (!isAddressedTo(invitation, user)) {
            throw new BusinessException(ErrorCode.INVITATION_TARGET_MISMATCH, "Invitation token belongs to a different account.");
        }
    }

    /**
     * Lời mời này có gửi cho đúng người đang đăng nhập không.
     *
     * <p>So khớp với email/sđt <em>sống</em> của tài khoản, chứ không với một liên kết lưu sẵn lúc
     * tạo lời mời. Đây cũng chính là điều kiện mà {@code findReceived} dùng, nên hộp thư và thao
     * tác chấp nhận không bao giờ bất đồng.</p>
     */
    private boolean isAddressedTo(FamilyInvitationEntity invitation, UserEntity user) {
        boolean phoneMatches = invitation.getInvitedPhone() != null
                && invitation.getInvitedPhone().equals(user.getPhone());
        boolean emailMatches = invitation.getInvitedEmail() != null
                && user.getEmail() != null
                && invitation.getInvitedEmail().equalsIgnoreCase(user.getEmail());
        return phoneMatches || emailMatches;
    }

    private BusinessException invitationNotFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Invitation was not found.");
    }

    private String normalizePhone(String phone) {
        return phone == null || phone.isBlank()
                ? null : phoneNormalizer.normalizeVietnamesePhone(phone);
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank()
                ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private FamilyInvitationResponse toResponse(FamilyInvitationEntity invitation,
                                                DeliveryOutcome outcome, String rawToken) {
        return new FamilyInvitationResponse(
                invitation.getId(), invitation.getFamilyGroupId(),
                invitation.getInvitedPhone(), invitation.getInvitedEmail(),
                rawToken, frontend.inviteUrl(rawToken),
                invitation.getRelationship().name(),
                scopeNames(invitation.getScopes()),
                invitation.getStatus().name(),
                outcome.status().name(), outcome.sentAt(),
                invitation.getExpiresAt(), invitation.getCreatedAt());
    }

    private FamilyInvitationPreviewResponse toPreview(FamilyInvitationEntity invitation) {
        boolean byEmail = invitation.getInvitedEmail() != null;
        return new FamilyInvitationPreviewResponse(
                inviterName(invitation),
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

    private ReceivedFamilyInvitationResponse toReceived(FamilyInvitationEntity invitation,
                                                        String inviterDisplayName) {
        boolean byEmail = invitation.getInvitedEmail() != null;
        return new ReceivedFamilyInvitationResponse(
                invitation.getId(),
                inviterDisplayName,
                invitation.getRelationship().name(),
                FamilyScopeLabels.of(invitation.getRelationship()),
                scopeNames(invitation.getScopes()),
                FamilyScopeLabels.of(invitation.getScopes()),
                byEmail ? "EMAIL" : "PHONE",
                byEmail ? maskEmail(invitation.getInvitedEmail())
                        : maskPhone(invitation.getInvitedPhone()),
                effectiveStatus(invitation),
                invitation.getExpiresAt(),
                invitation.getCreatedAt());
    }

    /** Nhóm đã bị xoá thì lời mời vẫn xem trước được, chỉ là không biết ai mời. */
    private String inviterName(FamilyInvitationEntity invitation) {
        return groups.findById(invitation.getFamilyGroupId())
                .flatMap(group -> users.findById(group.getOwnerUserId()))
                .map(UserEntity::getDisplayName)
                .orElse(null);
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
