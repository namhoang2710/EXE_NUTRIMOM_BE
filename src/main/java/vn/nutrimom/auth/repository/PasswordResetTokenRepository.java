package vn.nutrimom.auth.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.auth.domain.PasswordResetTokenEntity;
import vn.nutrimom.auth.domain.PasswordResetTokenStatus;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, String> {
    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetTokenEntity t where t.tokenHash = :tokenHash")
    Optional<PasswordResetTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<PasswordResetTokenEntity> findTopByUserIdOrderByCreatedAtDesc(String userId);

    long countByUserIdAndCreatedAtAfter(String userId, OffsetDateTime since);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetTokenEntity t set t.status = :revoked "
            + "where t.userId = :userId and t.status = :pending")
    int revokePending(@Param("userId") String userId,
                      @Param("pending") PasswordResetTokenStatus pending,
                      @Param("revoked") PasswordResetTokenStatus revoked);
}
