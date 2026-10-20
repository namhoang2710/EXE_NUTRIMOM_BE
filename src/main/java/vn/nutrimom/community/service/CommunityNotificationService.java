package vn.nutrimom.community.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.domain.CommunityComment;
import vn.nutrimom.community.domain.CommunityNotification;
import vn.nutrimom.community.domain.CommunityNotificationType;
import vn.nutrimom.community.domain.CommunityPost;
import vn.nutrimom.community.dto.CommunityDtos.Author;
import vn.nutrimom.community.dto.CommunityDtos.MarkAllReadResult;
import vn.nutrimom.community.dto.CommunityDtos.NotificationItem;
import vn.nutrimom.community.dto.CommunityDtos.UnreadCount;
import vn.nutrimom.community.repository.CommunityNotificationRepository;

/**
 * Thông báo riêng của cộng đồng — không dùng {@code NotificationService}/bảng {@code notifications}
 * của chuông header, không đẩy push.
 *
 * <p>Mỗi sự kiện có một {@code dedup_key}; ghi bằng ON CONFLICT DO NOTHING nên retry, bấm tim/bỏ tim
 * liên tục hay hai request cùng lúc đều chỉ ra một thông báo. Không có loại thông báo "bị báo cáo":
 * chủ bài chỉ được báo khi admin gỡ bài, và không bao giờ biết ai đã báo cáo.</p>
 */
@Service
public class CommunityNotificationService {
    private final CommunityNotificationRepository notifications;
    private final CommunityMembers members;

    public CommunityNotificationService(CommunityNotificationRepository notifications, CommunityMembers members) {
        this.notifications = notifications;
        this.members = members;
    }

    void postLiked(CommunityPost post, String actorId) {
        if (post.getAuthorId().equals(actorId)) return;
        insert(post.getAuthorId(), CommunityNotificationType.POST_LIKED, post.getId(), actorId, null,
                "LIKE:" + post.getId() + ":" + actorId);
    }

    void postCommented(CommunityPost post, CommunityComment comment) {
        if (post.getAuthorId().equals(comment.getAuthorId())) return;
        insert(post.getAuthorId(), CommunityNotificationType.POST_COMMENTED, post.getId(), comment.getAuthorId(),
                comment.getId(), "COMMENT:" + comment.getId());
    }

    /** Duyệt/từ chối có thể lặp lại sau mỗi lần sửa bài, nên khoá gắn với version của bài. */
    void moderated(CommunityPost post, CommunityNotificationType type) {
        String key = type == CommunityNotificationType.POST_REMOVED
                ? "REMOVED:" + post.getId()
                : type.name() + ":" + post.getId() + ":" + post.getVersion();
        insert(post.getAuthorId(), type, post.getId(), null, null, key);
    }

    private void insert(String recipientId, CommunityNotificationType type, String postId, String actorId,
            String commentId, String dedupKey) {
        notifications.insertIfAbsent(UUID.randomUUID().toString(), recipientId, type.name(), postId, actorId,
                commentId, dedupKey, CommunityClock.now());
    }

    @Transactional(readOnly = true)
    public CursorPage<NotificationItem> list(String userId, String cursor, int limit, boolean unreadOnly) {
        CommunityLimits.requirePageSize(limit, 50);
        CommunityCursor from = CommunityCursor.descending(cursor);
        var page = CommunityCursor.window(notifications.findPage(userId, unreadOnly, from.at(), from.id(),
                PageRequest.of(0, limit + 1)), limit, CommunityNotification::getCreatedAt, CommunityNotification::getId);
        Set<String> actorIds = new HashSet<>();
        page.items().stream().map(CommunityNotification::getActorId).filter(Objects::nonNull).forEach(actorIds::add);
        Map<String, Author> actors = members.authors(actorIds);
        List<NotificationItem> items = page.items().stream().map(n -> {
            Author actor = n.getActorId() == null ? null
                    : actors.getOrDefault(n.getActorId(), CommunityMembers.fallback(n.getActorId()));
            return new NotificationItem(n.getId(), n.getType(), n.getPostId(), n.getCommentId(), actor,
                    message(n.getType(), actor), n.getReadAt() != null, n.getCreatedAt());
        }).toList();
        return new CursorPage<>(items, page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(String userId) {
        return new UnreadCount(notifications.countByRecipientIdAndReadAtIsNull(userId));
    }

    /** Idempotent; thông báo của người khác trả 404 như không tồn tại. */
    @Transactional
    public void markRead(String userId, String id) {
        if (notifications.markRead(id, userId, CommunityClock.now()) == 0
                && !notifications.existsByIdAndRecipientId(id, userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thông báo.");
        }
    }

    @Transactional
    public MarkAllReadResult markAllRead(String userId) {
        return new MarkAllReadResult(notifications.markAllRead(userId, CommunityClock.now()));
    }

    static String message(CommunityNotificationType type, Author actor) {
        String name = actor == null ? "Một thành viên" : actor.displayName();
        return switch (type) {
            case POST_LIKED -> name + " đã thả tim bài viết của bạn.";
            case POST_COMMENTED -> name + " đã bình luận bài viết của bạn.";
            case POST_APPROVED -> "Bài viết của bạn đã được duyệt và hiển thị trong cộng đồng.";
            case POST_REJECTED -> "Bài viết của bạn chưa được duyệt. Xem lý do trong mục Bài viết của tôi.";
            case POST_REMOVED -> "Bài viết của bạn đã bị gỡ do vi phạm quy định cộng đồng.";
        };
    }
}
