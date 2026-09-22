package vn.nutrimom.consultation.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.consultation.domain.AssignmentType;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.Specialty;

public interface ConsultationRequestRepository
        extends JpaRepository<ConsultationRequestEntity, String> {

    List<ConsultationRequestEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<ConsultationRequestEntity> findByIdAndUserId(String id, String userId);

    List<ConsultationRequestEntity> findByExpertUserIdAndStatusOrderByCreatedAtDesc(
            String expertUserId, ConsultationStatus status);

    List<ConsultationRequestEntity> findBySpecialtyAndAssignmentTypeAndStatusOrderByCreatedAtAsc(
            Specialty specialty, AssignmentType assignmentType, ConsultationStatus status);

    List<ConsultationRequestEntity> findByStatusOrderByCreatedAtDesc(ConsultationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ConsultationRequestEntity request where request.id = :id")
    Optional<ConsultationRequestEntity> findByIdForUpdate(@Param("id") String id);
}
