package vn.nutrimom.consultation.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.Specialty;

public interface ExpertProfileRepository extends JpaRepository<ExpertProfileEntity, String> {

    List<ExpertProfileEntity> findByStatusOrderByFullNameAsc(ExpertStatus status);

    List<ExpertProfileEntity> findByStatusAndSpecialtyOrderByFullNameAsc(
            ExpertStatus status, Specialty specialty);

    Optional<ExpertProfileEntity> findByUserIdAndStatus(String userId, ExpertStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from ExpertProfileEntity profile where profile.userId = :userId")
    Optional<ExpertProfileEntity> findByIdForUpdate(@Param("userId") String userId);
}
