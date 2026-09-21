package vn.nutrimom.care.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.care.domain.BirthPlanEntity;

public interface BirthPlanRepository extends JpaRepository<BirthPlanEntity, String> {
    Optional<BirthPlanEntity> findByPregnancyId(String pregnancyId);
}
