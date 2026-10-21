package vn.nutrimom.community.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.domain.CommunityPost;
import vn.nutrimom.community.dto.CommunityDtos.LikeResult;
import vn.nutrimom.community.dto.CommunityDtos.SaveResult;
import vn.nutrimom.community.repository.CommunityPostLikeRepository;
import vn.nutrimom.community.repository.CommunityPostRepository;
import vn.nutrimom.community.repository.CommunityPostSaveRepository;

/**
 * Tim và lưu. Cả hai idempotent: PUT lặp lại hay hai tab bấm cùng lúc đều cho cùng kết quả, vì
 * khoá chính (post_id, user_id) quyết định và bộ đếm chỉ đổi khi số dòng thực sự thay đổi.
 */
@Service
public class CommunityInteractionService {
    private final CommunityPostService postService;
    private final CommunityPostRepository posts;
    private final CommunityPostLikeRepository likes;
    private final CommunityPostSaveRepository saves;
    private final CommunityMembers members;
    private final CommunityNotificationService notifications;

    public CommunityInteractionService(CommunityPostService postService, CommunityPostRepository posts,
            CommunityPostLikeRepository likes, CommunityPostSaveRepository saves, CommunityMembers members,
            CommunityNotificationService notifications) {
        this.postService = postService;
        this.posts = posts;
        this.likes = likes;
        this.saves = saves;
        this.members = members;
        this.notifications = notifications;
    }

    @Transactional
    public LikeResult like(String userId, String postId) {
        members.requireActive(userId);
        CommunityPost post = postService.requirePublic(postId);
        if (likes.insertIfAbsent(postId, userId, CommunityClock.now()) == 1) {
            posts.addLikes(postId, 1);
            notifications.postLiked(post, userId);
        }
        return new LikeResult(true, posts.likeCount(postId));
    }

    /** Bỏ tim được cả khi bài đã bị ẩn sau đó, để người dùng luôn gỡ được dấu vết của mình. */
    @Transactional
    public LikeResult unlike(String userId, String postId) {
        if (likes.remove(postId, userId) == 1) {
            posts.addLikes(postId, -1);
        }
        int count = posts.findById(postId).isPresent() ? posts.likeCount(postId) : 0;
        return new LikeResult(false, count);
    }

    @Transactional
    public SaveResult save(String userId, String postId) {
        members.requireActive(userId);
        postService.requirePublic(postId);
        saves.insertIfAbsent(postId, userId, CommunityClock.now());
        return new SaveResult(true);
    }

    @Transactional
    public SaveResult unsave(String userId, String postId) {
        saves.remove(postId, userId);
        return new SaveResult(false);
    }
}
