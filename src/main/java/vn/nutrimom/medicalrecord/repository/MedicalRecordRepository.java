package vn.nutrimom.medicalrecord.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.medicalrecord.domain.MedicalRecordEntity;

public interface MedicalRecordRepository extends JpaRepository<MedicalRecordEntity, String> {
    Optional<MedicalRecordEntity> findByIdAndOwnerUserIdAndDeletedAtIsNull(String id, String ownerUserId);
    List<MedicalRecordEntity> findByOwnerUserIdAndDeletedAtIsNullOrderByOccurredAtDescIdDesc(String ownerUserId);
}
