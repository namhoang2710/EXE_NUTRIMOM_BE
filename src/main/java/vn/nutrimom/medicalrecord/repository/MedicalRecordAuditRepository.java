package vn.nutrimom.medicalrecord.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.medicalrecord.domain.MedicalRecordAuditEntity;

public interface MedicalRecordAuditRepository extends JpaRepository<MedicalRecordAuditEntity, String> { }
