package vn.nutrimom.nutrition.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.IngredientEntity;

/** Danh mục seed, chỉ đọc sau khi loader nạp xong. */
public interface IngredientRepository extends JpaRepository<IngredientEntity, String> {
}
