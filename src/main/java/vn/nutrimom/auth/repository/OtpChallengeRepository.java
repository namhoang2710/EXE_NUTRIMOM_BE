package vn.nutrimom.auth.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.auth.domain.OtpChallengeEntity;
import vn.nutrimom.auth.domain.OtpPurpose;

public interface OtpChallengeRepository extends JpaRepository<OtpChallengeEntity, String> {
    Optional<OtpChallengeEntity> findTopByPhoneAndPurposeOrderByCreatedAtDesc(String phone, OtpPurpose purpose);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from OtpChallengeEntity challenge where challenge.id = :id")
    Optional<OtpChallengeEntity> findByIdForUpdate(@Param("id") String id);
}
