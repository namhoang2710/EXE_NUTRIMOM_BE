package vn.nutrimom.community.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.config.CommunityProperties;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.domain.CommunityNotificationType;
import vn.nutrimom.community.domain.CommunityPost;
import vn.nutrimom.community.domain.PostStatus;
import vn.nutrimom.community.dto.CommunityDtos.AdminCreatePostRequest;
import vn.nutrimom.community.dto.CommunityDtos.AdminPostItem;
import vn.nutrimom.community.dto.CommunityDtos.PostItem;
import vn.nutrimom.community.repository.CommunityPostImageRepository;
import vn.nutrimom.community.repository.CommunityPostRepository;
import vn.nutrimom.community.repository.CommunityReportRepository;

/**
 * Kiểm duyệt của admin. Quyền ADMIN do {@code SecurityConfig} ({@code /api/v1/admin/**}) và
 * {@code ActiveAdminFilter} kiểm tra trước khi tới đây.
 */
@Service
public class CommunityModerationService {
    private final CommunityPostRepository posts;
    private final CommunityPostImageRepository images;
    private final CommunityReportRepository reports;
    private final CommunityPostAssembler assembler;
    private final CommunityNotificationService notifications;
    private final CommunityPostService postService;
    private final CommunityProperties properties;

    public CommunityModerationService(CommunityPostRepository posts, CommunityPostImageRepository images,
            CommunityReportRepository reports, CommunityPostAssembler assembler,
            CommunityNotificationService notifications, CommunityPostService postService,
            CommunityProperties properties) {
        this.posts = posts;
        this.images = images;
        this.reports = reports;
        this.assembler = assembler;
        this.notifications = notifications;
        this.postService = postService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public CursorPage<AdminPostItem> list(String adminId, PostStatus status, boolean pinnedOnly, String cursor,
            int limit) {
        CommunityLimits.requirePageSize(limit, 50);
        CommunityCursor from = CommunityCursor.descending(cursor);
        var page = CommunityCursor.window(posts.findAdminPage(status, pinnedOnly, from.at(), from.id(),
                PageRequest.of(0, limit + 1)), limit, CommunityPost::getCreatedAt, CommunityPost::getId);
        return new CursorPage<>(toAdminItems(page.items(), adminId), page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true)
    public AdminPostItem detail(String adminId, String postId) {
        return toAdminItems(List.of(require(postId)), adminId).get(0);
    }

    @Transactional(readOnly = true)
    public vn.nutrimom.community.dto.CommunityDtos.PendingPostCount pendingCount() {
        return new vn.nutrimom.community.dto.CommunityDtos.PendingPostCount(
                posts.countByStatusAndDeletedAtIsNull(PostStatus.PENDING));
    }

    /**
     * Ban quản trị đăng bài: duyệt ngay, ghim tuỳ chọn. Không tính quota 10 bài/24 giờ của người dùng.
     * Ghim khi đã đủ số bài ghim → 409 và không tạo bài (kiểm tra trước khi ghi).
     */
    @Transactional
    public AdminPostItem createPost(String adminId, AdminCreatePostRequest request) {
        boolean pin = Boolean.TRUE.equals(request.pinned());
        if (pin && posts.countPinned() >= properties.maxPinnedPosts()) {
            throw new BusinessException(ErrorCode.COMMUNITY_PIN_LIMIT_REACHED);
        }
        OffsetDateTime now = CommunityClock.now();
        CommunityPost post = postService.insert(adminId, request.content(), request.topic(), request.imageIds(),
                PostStatus.APPROVED, now);
        moderatedBy(post, adminId, now);
        if (pin) {
            post.setPinnedAt(now);
            post.setPinnedBy(adminId);
        }
        posts.saveAndFlush(post);
        return detail(adminId, post.getId());
    }

    @Transactional
    public AdminPostItem approve(String adminId, String postId) {
        CommunityPost post = requireActive(postId);
        if (post.getStatus() == PostStatus.APPROVED) return detail(adminId, postId);
        OffsetDateTime now = CommunityClock.now();
        post.setStatus(PostStatus.APPROVED);
        post.setRejectReason(null);
        // Giữ mốc xuất bản lần đầu: sửa rồi duyệt lại không đẩy bài lên đầu feed.
        if (post.getPublishedAt() == null) post.setPublishedAt(now);
        moderatedBy(post, adminId, now);
        posts.saveAndFlush(post);
        notifyAuthor(post, adminId, CommunityNotificationType.POST_APPROVED);
        return detail(adminId, postId);
    }

    @Transactional
    public AdminPostItem reject(String adminId, String postId, String reason) {
        CommunityPost post = requireActive(postId);
        OffsetDateTime now = CommunityClock.now();
        post.setStatus(PostStatus.REJECTED);
        post.setRejectReason(CommunityText.required(reason, CommunityLimits.NOTE_MAX_CHARS, "reason"));
        post.setPinnedAt(null);
        post.setPinnedBy(null);
        moderatedBy(post, adminId, now);
        posts.saveAndFlush(post);
        notifyAuthor(post, adminId, CommunityNotificationType.POST_REJECTED);
        return detail(adminId, postId);
    }

    /**
     * Ghim tối đa {@code max-pinned-posts} bài. Hai admin ghim hai bài khác nhau đúng cùng lúc có thể
     * vượt giới hạn một bài (READ COMMITTED); chấp nhận vì chỉ admin thao tác và bỏ ghim là sửa được.
     */
    @Transactional
    public AdminPostItem pin(String adminId, String postId, boolean pinned) {
        CommunityPost post = requireActive(postId);
        if (pinned) {
            if (post.getStatus() != PostStatus.APPROVED) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Chỉ ghim được bài đã duyệt.");
            }
            if (post.getPinnedAt() == null) {
                if (posts.countPinned() >= properties.maxPinnedPosts()) {
                    throw new BusinessException(ErrorCode.COMMUNITY_PIN_LIMIT_REACHED);
                }
                post.setPinnedAt(CommunityClock.now());
                post.setPinnedBy(adminId);
            }
        } else {
            post.setPinnedAt(null);
            post.setPinnedBy(null);
        }
        posts.saveAndFlush(post);
        return detail(adminId, postId);
    }

    /** Gỡ bài (dùng chung cho nút xoá của admin và xử lý báo cáo). Lặp lại an toàn. */
    @Transactional
    public AdminPostItem remove(String adminId, String postId, String reason) {
        CommunityPost post = require(postId);
        if (post.getStatus() != PostStatus.REMOVED) {
            OffsetDateTime now = CommunityClock.now();
            String note = CommunityText.optional(reason, CommunityLimits.NOTE_MAX_CHARS, "reason");
            post.setStatus(PostStatus.REMOVED);
            post.setRejectReason(note);
            post.setPinnedAt(null);
            post.setPinnedBy(null);
            if (post.getDeletedAt() == null) post.setDeletedAt(now);
            moderatedBy(post, adminId, now);
            posts.saveAndFlush(post);
            images.orphanAll(postId, now.plus(properties.removedImageRetention()), now);
            reports.closeOpenForPost(postId, adminId, note, now);
            notifyAuthor(post, adminId, CommunityNotificationType.POST_REMOVED);
        }
        return detail(adminId, postId);
    }

    CommunityPost require(String postId) {
        return posts.findById(postId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, CommunityPostService.NOT_FOUND));
    }

    /** Bài người dùng đã tự xoá hoặc đã bị gỡ thì không duyệt/ghim lại được. */
    private CommunityPost requireActive(String postId) {
        CommunityPost post = require(postId);
        if (post.isDeleted() || post.getStatus() == PostStatus.REMOVED) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, CommunityPostService.NOT_FOUND);
        }
        return post;
    }

    /** Admin xử lý bài của chính mình thì không tự gửi thông báo cho mình. */
    private void notifyAuthor(CommunityPost post, String adminId, CommunityNotificationType type) {
        if (!post.getAuthorId().equals(adminId)) notifications.moderated(post, type);
    }

    private static void moderatedBy(CommunityPost post, String adminId, OffsetDateTime now) {
        post.setModeratedBy(adminId);
        post.setModeratedAt(now);
    }

    private List<AdminPostItem> toAdminItems(List<CommunityPost> page, String adminId) {
        if (page.isEmpty()) return List.of();
        Map<String, Long> open = reports.openCountsByPost(page.stream().map(CommunityPost::getId).toList());
        List<PostItem> items = assembler.many(page, adminId, true);
        return IntStream.range(0, page.size()).mapToObj(i -> {
            CommunityPost post = page.get(i);
            return new AdminPostItem(items.get(i), open.getOrDefault(post.getId(), 0L), post.getModeratedBy(),
                    post.getModeratedAt(), post.getDeletedAt());
        }).toList();
    }
}
