package vn.nutrimom.community.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.nutrimom.community.domain.CommunityPost;
import vn.nutrimom.community.domain.CommunityPostImage;
import vn.nutrimom.community.domain.PostStatus;
import vn.nutrimom.community.dto.CommunityDtos.Author;
import vn.nutrimom.community.dto.CommunityDtos.Image;
import vn.nutrimom.community.dto.CommunityDtos.PostItem;
import vn.nutrimom.community.repository.CommunityPostImageRepository;
import vn.nutrimom.community.repository.CommunityPostLikeRepository;
import vn.nutrimom.community.repository.CommunityPostSaveRepository;

/**
 * Dựng {@link PostItem} cho một trang bài với số câu truy vấn CỐ ĐỊNH, không phụ thuộc số bài:
 * tác giả (1), ảnh (1), đã tim (1), đã lưu (1). Bộ đếm tim/bình luận lấy sẵn trên dòng bài.
 */
@Component
public class CommunityPostAssembler {
    private final CommunityMembers members;
    private final CommunityPostImageRepository images;
    private final CommunityPostLikeRepository likes;
    private final CommunityPostSaveRepository saves;

    public CommunityPostAssembler(CommunityMembers members, CommunityPostImageRepository images,
            CommunityPostLikeRepository likes, CommunityPostSaveRepository saves) {
        this.members = members;
        this.images = images;
        this.likes = likes;
        this.saves = saves;
    }

    public PostItem one(CommunityPost post, String viewerId) {
        return many(List.of(post), viewerId).get(0);
    }

    public List<PostItem> many(List<CommunityPost> posts, String viewerId) {
        return many(posts, viewerId, false);
    }

    /**
     * {@code moderatorView}: dùng cho API admin — luôn trả lý do từ chối/gỡ và trạng thái ghim thật,
     * không bật can_edit/can_delete (admin dùng endpoint kiểm duyệt riêng).
     */
    public List<PostItem> many(List<CommunityPost> posts, String viewerId, boolean moderatorView) {
        if (posts.isEmpty()) return List.of();
        List<String> ids = posts.stream().map(CommunityPost::getId).toList();
        Set<String> authorIds = new HashSet<>();
        posts.forEach(post -> authorIds.add(post.getAuthorId()));
        Map<String, Author> authors = members.authors(authorIds);

        Map<String, List<Image>> imagesByPost = new LinkedHashMap<>();
        if (posts.stream().anyMatch(post -> post.getImageCount() > 0)) {
            for (CommunityPostImage image : images.findAttached(ids)) {
                imagesByPost.computeIfAbsent(image.getPostId(), key -> new ArrayList<>())
                        .add(new Image(image.getId(), image.getImageUrl(), image.getWidth(), image.getHeight()));
            }
        }
        Set<String> liked = new HashSet<>(likes.findLikedPostIds(viewerId, ids));
        Set<String> saved = new HashSet<>(saves.findSavedPostIds(viewerId, ids));

        return posts.stream().map(post -> {
            boolean mine = post.getAuthorId().equals(viewerId);
            boolean editable = !moderatorView && mine && !post.isDeleted() && post.getStatus() != PostStatus.REMOVED;
            boolean pinned = post.getPinnedAt() != null && (moderatorView || post.isPublic());
            // Lý do từ chối chỉ chủ bài và admin thấy.
            String rejectReason = mine || moderatorView ? post.getRejectReason() : null;
            return new PostItem(post.getId(),
                    authors.getOrDefault(post.getAuthorId(), CommunityMembers.fallback(post.getAuthorId())),
                    post.getContent(), post.getTopic(), imagesByPost.getOrDefault(post.getId(), List.of()),
                    post.getLikeCount(), post.getCommentCount(), liked.contains(post.getId()),
                    saved.contains(post.getId()), pinned, post.getStatus(), rejectReason, editable, editable,
                    post.getVersion(), post.getPublishedAt(), post.getCreatedAt(), post.getEditedAt());
        }).toList();
    }
}
