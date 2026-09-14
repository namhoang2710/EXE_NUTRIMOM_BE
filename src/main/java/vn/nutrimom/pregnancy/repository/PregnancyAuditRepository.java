package vn.nutrimom.pregnancy.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.pregnancy.domain.PregnancyAuditEntity;

public interface PregnancyAuditRepository extends JpaRepository<PregnancyAuditEntity, String> {
}