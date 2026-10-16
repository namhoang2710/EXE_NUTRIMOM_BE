package vn.nutrimom.nutrition.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.UserRecipePreferenceEntity;
import vn.nutrimom.nutrition.domain.UserRecipePreferenceId;

/** Sở thích món, khoá theo {@code (user_id, recipe_id)}. */
public interface UserRecipePreferenceRepository
        extends JpaRepository<UserRecipePreferenceEntity, UserRecipePreferenceId> {

    List<UserRecipePreferenceEntity> findByUserIdOrderByRecipeId(String userId);
}
