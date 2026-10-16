package vn.nutrimom.nutrition.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.NutrientEntity;

/** Danh mục seed, chỉ đọc sau khi loader nạp xong. */
public interface NutrientRepository extends JpaRepository<NutrientEntity, String> {
}
