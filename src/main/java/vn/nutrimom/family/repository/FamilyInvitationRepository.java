package vn.nutrimom.family.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.family.domain.FamilyInvitationEntity;

public interface FamilyInvitationRepository extends JpaRepository<FamilyInvitationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from FamilyInvitationEntity invitation "
            + "where invitation.tokenHash = :tokenHash")
    Optional<FamilyInvitationEntity> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash);
}
