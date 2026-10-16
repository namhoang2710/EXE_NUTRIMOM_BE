package vn.nutrimom.nutrition.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Khoá của {@link UserRecipePreferenceEntity}: đúng một thái độ cho mỗi cặp (người dùng, món).
 *
 * <p>Cặp này là khoá chính chứ không phải unique index phụ, nên không thể tồn tại hai dòng nói
 * ngược nhau về cùng một món — đổi {@code FAVORITE} thành {@code BLOCKED} là cập nhật tại chỗ.</p>
 */
public class UserRecipePreferenceId implements Serializable {

    private String userId;
    private String recipeId;

    public UserRecipePreferenceId() {
    }

    public UserRecipePreferenceId(String userId, String recipeId) {
        this.userId = userId;
        this.recipeId = recipeId;
    }

    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getRecipeId() { return recipeId; }
    public void setRecipeId(String value) { recipeId = value; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserRecipePreferenceId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(recipeId, that.recipeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, recipeId);
    }
}
