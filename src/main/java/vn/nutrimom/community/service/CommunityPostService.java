package vn.nutrimom.community.service;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.config.CommunityProperties;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.domain.CommunityPost;
import vn.nutrimom.community.domain.PostStatus;
import vn.nutrimom.community.domain.PostTopic;
import vn.nutrimom.community.dto.CommunityDtos.CreatePostRequest;
import vn.nutrimom.community.dto.CommunityDtos.FeedPage;
import vn.nutrimom.community.dto.CommunityDtos.PostItem;
import vn.nutrimom.community.dto.CommunityDtos.UpdatePostRequest;
import vn.nutrimom.community.repository.CommunityPostImageRepository;
import vn.nutrimom.community.repository.CommunityPostRepository;

/** Bài viết phía người dùng: feed, chi tiết, đăng, sửa, xoá. */
@Service
public class CommunityPostService {
    static final String NOT_FOUND = "Không tìm thấy bài viết.";

    private final CommunityPostRepository posts;
    private final CommunityPostImageRepository images;
    private final CommunityPostAssembler assembler;
    private final CommunityMembers members;
    private final CommunityProperties properties;

    public CommunityPostService(CommunityPostRepository posts, CommunityPostImageRepository images,
            CommunityPostAssembler assembler, CommunityMembers members, CommunityProperties properties) {
        this.posts = posts;
        this.images = images;
        this.assembler = assembler;
        this.members = members;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public FeedPage feed(String viewerId, String cursor, int limit, PostTopic topic, boolean hasImages,
            boolean savedOnly) {
        CommunityLimits.requirePageSize(limit, CommunityLimits.FEED_MAX_PAGE);
        String savedBy = savedOnly ? viewerId : null;
        CommunityCursor from = CommunityCursor.descending(cursor);
        var page = CommunityCursor.window(posts.findFeedPage(topic, hasImages, savedBy, from.at(), from.id(),
                PageRequest.of(0, limit + 1)), limit, CommunityPost::getPublishedAt, CommunityPost::getId);
        List<PostItem> pinned = null;
        if (CommunityCursor.blank(cursor)) {
            pinned = assembler.many(posts.findPinned(topic, hasImages, savedBy,
                    PageRequest.of(0, properties.maxPinnedPosts())), viewerId);
        }
        return new FeedPage(assembler.many(page.items(), viewerId), pinned, page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true)
    public CursorPage<PostItem> mine(String userId, String cursor, int limit, PostStatus status) {
        CommunityLimits.requirePageSize(limit, CommunityLimits.FEED_MAX_PAGE);
        CommunityCursor from = CommunityCursor.descending(cursor);
        var page = CommunityCursor.window(posts.findMinePage(userId, status, from.at(), from.id(),
                PageRequest.of(0, limit + 1)), limit, CommunityPost::getCreatedAt, CommunityPost::getId);
        return new CursorPage<>(assembler.many(page.items(), userId), page.nextCursor(), page.hasMore());
    }

    /** Bài công khai, hoặc bài chưa duyệt của chính mình. Mọi trường hợp khác là 404 (không lộ id). */
    @Transactional(readOnly = true)
    public PostItem detail(String viewerId, String postId) {
        return assembler.one(requireVisible(postId, viewerId), viewerId);
    }

    @Transactional
    public PostItem create(String userId, CreatePostRequest request) {
        members.requireActive(userId);
        OffsetDateTime now = CommunityClock.now();
        if (posts.countByAuthorIdAndCreatedAtGreaterThanEqual(userId, now.minusHours(24)) >= properties.dailyPostLimit()) {
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED, "Bạn đã đăng số bài tối đa trong 24 giờ.");
        }
        CommunityPost post = insert(userId, request.content(), request.topic(), request.imageIds(), PostStatus.PENDING, now);
        return assembler.one(post, userId);
    }

    /**
     * Tạo bài và gắn ảnh trong giao dịch của người gọi — dùng chung cho bài người dùng (PENDING) và bài
     * admin đăng (APPROVED ngay). Ảnh không thuộc tác giả / đã gắn / hết hạn thì cả giao dịch rollback.
     */
    CommunityPost insert(String authorId, String rawContent, PostTopic topic, List<String> rawImageIds,
            PostStatus status, OffsetDateTime now) {
        List<String> imageIds = imageIds(rawImageIds);
        CommunityPost post = new CommunityPost();
        post.setAuthorId(authorId);
        post.setContent(CommunityText.required(rawContent, CommunityLimits.POST_MAX_CHARS, "content"));
        post.setTopic(topic);
        post.setStatus(status);
        if (status == PostStatus.APPROVED) post.setPublishedAt(now);
        post.setImageCount(imageIds.size());
        posts.saveAndFlush(post);
        for (int i = 0; i < imageIds.size(); i++) {
            if (images.attach(imageIds.get(i), authorId, post.getId(), i, now) != 1) {
                throw new BusinessException(ErrorCode.COMMUNITY_MEDIA_INVALID);
            }
        }
        return post;
    }

    /**
     * Chủ bài sửa nội dung/ảnh. Bài đã duyệt quay về PENDING để admin xem lại (không thể duyệt xong rồi
     * sửa thành nội dung khác) — trừ khi người sửa là admin đang hoạt động: bài của ban quản trị không cần
     * tự duyệt lại. Ảnh bị bỏ chuyển ORPHANED, chỉ bị xoá khỏi R2 sau thời gian giữ lại.
     */
    @Transactional
    public PostItem update(String userId, String postId, UpdatePostRequest request) {
        members.requireActive(userId);
        CommunityPost post = requireOwnEditable(postId, userId);
        if (post.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        OffsetDateTime now = CommunityClock.now();
        List<String> imageIds = imageIds(request.imageIds());
        for (int i = 0; i < imageIds.size(); i++) {
            String imageId = imageIds.get(i);
            if (images.reorder(imageId, postId, i, now) == 0 && images.attach(imageId, userId, postId, i, now) != 1) {
                throw new BusinessException(ErrorCode.COMMUNITY_MEDIA_INVALID);
            }
        }
        OffsetDateTime deleteAfter = now.plus(properties.removedImageRetention());
        if (imageIds.isEmpty()) images.orphanAll(postId, deleteAfter, now);
        else images.orphanExcept(postId, imageIds, deleteAfter, now);

        post.setContent(CommunityText.required(request.content(), CommunityLimits.POST_MAX_CHARS, "content"));
        post.setTopic(request.topic());
        post.setImageCount(imageIds.size());
        post.setEditedAt(now);
        boolean adminKeepsApproval = post.getStatus() == PostStatus.APPROVED && members.isActiveAdmin(userId);
        if (post.getStatus() != PostStatus.PENDING && !adminKeepsApproval) {
            post.setStatus(PostStatus.PENDING);
            post.setRejectReason(null);
        }
        posts.saveAndFlush(post);
        return assembler.one(post, userId);
    }

    /** Xoá mềm; ảnh giữ thêm {@code removed-image-retention} rồi job mới xoá trên R2. */
    @Transactional
    public void delete(String userId, String postId) {
        CommunityPost post = requireOwnEditable(postId, userId);
        OffsetDateTime now = CommunityClock.now();
        post.setDeletedAt(now);
        post.setPinnedAt(null);
        post.setPinnedBy(null);
        images.orphanAll(postId, now.plus(properties.removedImageRetention()), now);
        posts.saveAndFlush(post);
    }

    CommunityPost requireVisible(String postId, String viewerId) {
        CommunityPost post = posts.findById(postId).orElse(null);
        if (post == null || post.isDeleted()
                || !(post.getStatus() == PostStatus.APPROVED || post.getAuthorId().equals(viewerId))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND);
        }
        return post;
    }

    /** Bài đang công khai — điều kiện để tim, lưu, bình luận, báo cáo. */
    CommunityPost requirePublic(String postId) {
        CommunityPost post = posts.findById(postId).orElse(null);
        if (post == null || !post.isPublic()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND);
        }
        return post;
    }

    private CommunityPost requireOwnEditable(String postId, String userId) {
        CommunityPost post = posts.findById(postId).orElse(null);
        if (post == null || post.isDeleted() || !post.getAuthorId().equals(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND);
        }
        if (post.getStatus() == PostStatus.REMOVED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Bài viết đã bị gỡ nên không thể chỉnh sửa.");
        }
        return post;
    }

    private List<String> imageIds(List<String> raw) {
        List<String> ids = raw == null ? List.of() : raw;
        if (ids.size() > properties.maxImagesPerPost()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Tối đa " + properties.maxImagesPerPost() + " ảnh mỗi bài.");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Danh sách ảnh bị trùng.");
        }
        return ids;
    }
}
