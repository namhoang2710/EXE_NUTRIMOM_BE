package vn.nutrimom.family.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyMemberStatus;

public interface FamilyMemberRepository extends JpaRepository<FamilyMemberEntity, String> {
    @org.springframework.data.jpa.repository.Query("select count(m) from FamilyMemberEntity m, FamilyGroupEntity g where g.id = m.familyGroupId and m.userId = :userId and m.status = :memberStatus and g.status = :groupStatus")
    long countActiveGroupsForAssistant(@org.springframework.data.repository.query.Param("userId") String userId,
            @org.springframework.data.repository.query.Param("memberStatus") FamilyMemberStatus memberStatus,
            @org.springframework.data.repository.query.Param("groupStatus") vn.nutrimom.family.domain.FamilyGroupStatus groupStatus);
    Optional<FamilyMemberEntity> findByFamilyGroupIdAndUserIdAndStatus(
            String familyGroupId, String userId, FamilyMemberStatus status);
    List<FamilyMemberEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
            String userId, FamilyMemberStatus status);
    List<FamilyMemberEntity> findByFamilyGroupIdInAndStatusOrderByCreatedAtAsc(
            Collection<String> familyGroupIds, FamilyMemberStatus status);
}
