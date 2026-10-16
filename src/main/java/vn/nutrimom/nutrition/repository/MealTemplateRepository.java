package vn.nutrimom.nutrition.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.MealTemplateEntity;
import vn.nutrimom.nutrition.domain.MealTemplateId;

/** Template bữa. Khoá là cặp (loại bữa, chế độ) — xem {@link MealTemplateId}. */
public interface MealTemplateRepository
        extends JpaRepository<MealTemplateEntity, MealTemplateId> {
}
