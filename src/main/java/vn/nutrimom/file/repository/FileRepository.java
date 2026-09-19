package vn.nutrimom.file.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.file.domain.FileEntity;

public interface FileRepository extends JpaRepository<FileEntity, String> {
    Optional<FileEntity> findByIdAndOwnerUserId(String id, String ownerUserId);
    List<FileEntity> findByMedicalRecordIdAndOwnerUserId(String medicalRecordId, String ownerUserId);
}
