package vn.nutrimom.community.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.config.CommunityProperties;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.domain.CommunityComment;
import vn.nutrimom.community.domain.CommunityPost;
import vn.nutrimom.community.dto.CommunityDtos.Author;
import vn.nutrimom.community.dto.CommunityDtos.CommentItem;
import vn.nutrimom.community.repository.CommunityCommentRepository;
import vn.nutrimom.community.repository.CommunityPostRepository;

/** Bình luận: hiện ngay (không chờ duyệt); người viết hoặc admin xoá (xoá mềm). */
@Service
public class CommunityCommentService {
    private static final String NOT_FOUND = "Không tìm thấy bình luận.";

    private final CommunityCommentRepository comments;
    private final CommunityPostRepository posts;
    private final CommunityPostService postService;
    private final CommunityMembers members;
    private final CommunityNotificationService notifications;
    private final CommunityProperties properties;

    public CommunityCommentService(CommunityCommentRepository comments, CommunityPostRepository posts,
            CommunityPostService postService, CommunityMembers members, CommunityNotificationService notifications,
            CommunityProperties properties) {
        this.comments = comments;
        this.posts = posts;
        this.postService = postService;
        this.members = members;
        this.notifications = notifications;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public CursorPage<CommentItem> list(String viewerId, String postId, String cursor, int limit) {
        CommunityLimits.requirePageSize(limit, 50);
        postService.requireVisible(postId, viewerId);
        CommunityCursor from = CommunityCursor.ascending(cursor);
        var page = CommunityCursor.window(comments.findPage(postId, from.at(), from.id(),
                PageRequest.of(0, limit + 1)), limit, CommunityComment::getCreatedAt, CommunityComment::getId);
        Map<String, Author> authors = members.authors(
                page.items().stream().map(CommunityComment::getAuthorId).collect(Collectors.toSet()));
        List<CommentItem> items = page.items().stream().map(c -> toItem(c, authors, viewerId)).toList();
        return new CursorPage<>(items, page.nextCursor(), page.hasMore());
    }

    @Transactional
    public CommentItem create(String userId, String postId, String content) {
        members.requireActive(userId);
        CommunityPost post = postService.requirePublic(postId);
        OffsetDateTime now = CommunityClock.now();
        if (comments.countByAuthorIdAndCreatedAtGreaterThanEqual(userId, now.minusHours(24))
                >= properties.dailyCommentLimit()) {
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED, "Bạn đã bình luận số lần tối đa trong 24 giờ.");
        }
        CommunityComment comment = new CommunityComment();
        comment.setPostId(postId);
        comment.setAuthorId(userId);
        comment.setContent(CommunityText.required(content, CommunityLimits.COMMENT_MAX_CHARS, "content"));
        comments.saveAndFlush(comment);
        posts.addComments(postId, 1);
        notifications.postCommented(post, comment);
        return toItem(comment, members.authors(List.of(userId)), userId);
    }

    /** Người viết xoá bình luận của mình; bình luận của người khác trả 404. */
    @Transactional
    public void deleteOwn(String userId, String commentId) {
        CommunityComment comment = comments.findById(commentId)
                .filter(c -> c.getDeletedAt() == null && c.getAuthorId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND));
        softDelete(comment, userId);
    }

    @Transactional
    public void deleteAsAdmin(String adminId, String commentId) {
        CommunityComment comment = comments.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND));
        if (comment.getDeletedAt() == null) softDelete(comment, adminId);
    }

    private void softDelete(CommunityComment comment, String deletedBy) {
        comment.setDeletedAt(CommunityClock.now());
        comment.setDeletedBy(deletedBy);
        comments.saveAndFlush(comment);
        posts.addComments(comment.getPostId(), -1);
    }

    private static CommentItem toItem(CommunityComment c, Map<String, Author> authors, String viewerId) {
        return new CommentItem(c.getId(), c.getPostId(),
                authors.getOrDefault(c.getAuthorId(), CommunityMembers.fallback(c.getAuthorId())), c.getContent(),
                c.getAuthorId().equals(viewerId), c.getCreatedAt());
    }
}
