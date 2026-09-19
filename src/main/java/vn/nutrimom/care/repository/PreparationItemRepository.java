package vn.nutrimom.care.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.care.domain.PreparationItemEntity;

public interface PreparationItemRepository extends JpaRepository<PreparationItemEntity, String> {
    List<PreparationItemEntity> findByPregnancyIdOrderBySortOrderAsc(String pregnancyId);
    Optional<PreparationItemEntity> findByIdAndPregnancyId(String id, String pregnancyId);
}
