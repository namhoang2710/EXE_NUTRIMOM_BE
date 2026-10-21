package vn.nutrimom.community.domain;

import java.io.Serializable;
import java.util.Objects;

/** Khoá ghép (post_id, user_id) của bảng tim và bảng lưu. */
public class PostUserId implements Serializable {
    private String postId;
    private String userId;

    public PostUserId() {
    }

    public PostUserId(String postId, String userId) {
        this.postId = postId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PostUserId id && Objects.equals(postId, id.postId) && Objects.equals(userId, id.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(postId, userId);
    }
}
