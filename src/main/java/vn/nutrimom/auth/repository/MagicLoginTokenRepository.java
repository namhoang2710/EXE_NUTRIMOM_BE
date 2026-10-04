package vn.nutrimom.auth.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.auth.domain.MagicLoginTokenEntity;
import vn.nutrimom.auth.domain.MagicLoginTokenStatus;

public interface MagicLoginTokenRepository extends JpaRepository<MagicLoginTokenEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MagicLoginTokenEntity t where t.tokenHash = :tokenHash")
    Optional<MagicLoginTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<MagicLoginTokenEntity> findByTokenHash(String tokenHash);

    Optional<MagicLoginTokenEntity> findTopByEmailAndStatusOrderByCreatedAtDesc(String email, MagicLoginTokenStatus status);
}
