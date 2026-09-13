package vn.nutrimom.family.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyMemberStatus;

public interface FamilyMemberRepository extends JpaRepository<FamilyMemberEntity, String> {
    Optional<FamilyMemberEntity> findByFamilyGroupIdAndUserIdAndStatus(
            String familyGroupId, String userId, FamilyMemberStatus status);
    List<FamilyMemberEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
            String userId, FamilyMemberStatus status);
    List<FamilyMemberEntity> findByFamilyGroupIdInAndStatusOrderByCreatedAtAsc(
            Collection<String> familyGroupIds, FamilyMemberStatus status);
}
