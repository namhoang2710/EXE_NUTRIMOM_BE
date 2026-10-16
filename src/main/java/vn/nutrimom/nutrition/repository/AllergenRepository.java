package vn.nutrimom.nutrition.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.AllergenEntity;

/** Danh mục seed, chỉ đọc sau khi loader nạp xong. */
public interface AllergenRepository extends JpaRepository<AllergenEntity, String> {
}
