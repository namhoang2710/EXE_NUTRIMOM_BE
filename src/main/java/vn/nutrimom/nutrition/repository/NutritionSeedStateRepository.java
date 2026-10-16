package vn.nutrimom.nutrition.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.NutritionSeedStateEntity;

/** Dòng đơn ghi phiên bản seed đang nằm trong database. Khoá luôn là {@code "X"}. */
public interface NutritionSeedStateRepository
        extends JpaRepository<NutritionSeedStateEntity, String> {
}
