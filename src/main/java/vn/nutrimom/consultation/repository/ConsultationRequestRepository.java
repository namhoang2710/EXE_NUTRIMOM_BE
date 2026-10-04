package vn.nutrimom.consultation.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    List<ConsultationRequestEntity> findTop5ByUserIdOrderByCreatedAtDesc(String userId);
    long countByUserId(String userId);

    Optional<ConsultationRequestEntity> findByIdAndUserId(String id, String userId);

    List<ConsultationRequestEntity> findByExpertUserIdAndStatusOrderByCreatedAtDesc(
            String expertUserId, ConsultationStatus status);

    List<ConsultationRequestEntity> findBySpecialtyAndAssignmentTypeAndStatusOrderByCreatedAtAsc(
            Specialty specialty, AssignmentType assignmentType, ConsultationStatus status);

    List<ConsultationRequestEntity> findByStatusOrderByCreatedAtDesc(ConsultationStatus status);

    @Query(value = """
            select request
            from ConsultationRequestEntity request
            where request.status = vn.nutrimom.consultation.domain.ConsultationStatus.COMPLETED
              and (:search = ''
                   or exists (select user.id from UserEntity user
                              where user.id = request.userId
                                and lower(user.displayName) like lower(concat('%', :search, '%')))
                   or exists (select expert.userId from ExpertProfileEntity expert
                              where expert.userId = request.expertUserId
                                and lower(expert.fullName) like lower(concat('%', :search, '%'))))
            """,
            countQuery = """
            select count(request.id)
            from ConsultationRequestEntity request
            where request.status = vn.nutrimom.consultation.domain.ConsultationStatus.COMPLETED
              and (:search = ''
                   or exists (select user.id from UserEntity user
                              where user.id = request.userId
                                and lower(user.displayName) like lower(concat('%', :search, '%')))
                   or exists (select expert.userId from ExpertProfileEntity expert
                              where expert.userId = request.expertUserId
                                and lower(expert.fullName) like lower(concat('%', :search, '%'))))
            """)
    Page<ConsultationRequestEntity> findCompletedForAdmin(
            @Param("search") String search, Pageable pageable);

    /** Yêu cầu gắn với các ô đã đặt, để hiện tên khách trên lịch làm việc của chuyên gia. */
    List<ConsultationRequestEntity> findBySlotIdIn(Collection<String> slotIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ConsultationRequestEntity request where request.id = :id")
    Optional<ConsultationRequestEntity> findByIdForUpdate(@Param("id") String id);
}
