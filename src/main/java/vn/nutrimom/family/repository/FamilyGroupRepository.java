package vn.nutrimom.family.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyGroupStatus;

public interface FamilyGroupRepository extends JpaRepository<FamilyGroupEntity, String> {
    Optional<FamilyGroupEntity> findByIdAndStatus(String id, FamilyGroupStatus status);
    Optional<FamilyGroupEntity> findByPregnancyIdAndStatus(
            String pregnancyId, FamilyGroupStatus status);
    List<FamilyGroupEntity> findByOwnerUserIdAndStatusOrderByCreatedAtDesc(
            String ownerUserId, FamilyGroupStatus status);
    boolean existsByIdAndStatus(String id, FamilyGroupStatus status);
}
