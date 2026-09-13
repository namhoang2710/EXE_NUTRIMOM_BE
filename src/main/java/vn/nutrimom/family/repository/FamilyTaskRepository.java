package vn.nutrimom.family.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.family.domain.FamilyTaskEntity;

public interface FamilyTaskRepository extends JpaRepository<FamilyTaskEntity, String> {
    List<FamilyTaskEntity> findByFamilyGroupIdAndAssigneeIdAndDeletedAtIsNullOrderByDueAtAsc(
            String familyGroupId, String assigneeId);
}
