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

    Page<ConsultationRequestEntity> findByUserId(String userId,Pageable pageable);

    @Query("""
            select c from ConsultationRequestEntity c left join AvailabilitySlotEntity s on s.id=c.slotId
            where c.expertUserId=:expert and c.status=:status
              and (:fromDate is null or coalesce(s.slotDate,c.scheduledDate)>=:fromDate)
              and (:toDate is null or coalesce(s.slotDate,c.scheduledDate)<=:toDate)
              and (:needle='%%' or exists(select u.id from UserEntity u where u.id=c.userId and lower(u.displayName) like :needle escape '!'))
            order by case when coalesce(s.slotDate,c.scheduledDate) is null then 1 else 0 end,
              coalesce(s.slotDate,c.scheduledDate) desc,coalesce(s.startTime,c.scheduledStartTime) desc,c.id
            """)
    Page<ConsultationRequestEntity> assignedPage(@Param("expert") String expert,@Param("status") ConsultationStatus status,
            @Param("fromDate") java.time.LocalDate from,@Param("toDate") java.time.LocalDate to,
            @Param("needle") String needle,Pageable pageable);

    @Query("""
            select c from ConsultationRequestEntity c join AvailabilitySlotEntity s on s.id=c.slotId
            where c.expertUserId=:expert and c.status=vn.nutrimom.consultation.domain.ConsultationStatus.PENDING_CONSULTATION
              and coalesce(s.slotDate,c.scheduledDate) is not null and coalesce(s.startTime,c.scheduledStartTime) is not null
              and (:fromDate is null or coalesce(s.slotDate,c.scheduledDate)>=:fromDate)
              and (:toDate is null or coalesce(s.slotDate,c.scheduledDate)<=:toDate)
              and (:needle='%%' or exists(select u.id from UserEntity u where u.id=c.userId and lower(u.displayName) like :needle escape '!'))
              and (c.scheduledEndsAt>:now or (c.scheduledEndsAt is null and
                (coalesce(s.slotDate,c.scheduledDate)>:today or
                 (coalesce(s.slotDate,c.scheduledDate)=:today and (:beforeOpening=true or
                   (:afterClosing=false and coalesce(s.endTime,c.scheduledEndTime)>:localTime))))))
            order by coalesce(s.slotDate,c.scheduledDate),coalesce(s.startTime,c.scheduledStartTime),c.id
            """)
    List<ConsultationRequestEntity> nextAssigned(@Param("expert") String expert,@Param("fromDate") java.time.LocalDate from,
            @Param("toDate") java.time.LocalDate to,@Param("needle") String needle,@Param("now") java.time.OffsetDateTime now,
            @Param("today") java.time.LocalDate today,@Param("localTime") java.time.LocalTime localTime,
            @Param("beforeOpening") boolean beforeOpening,@Param("afterClosing") boolean afterClosing,Pageable pageable);

    @Query("""
            select c from ConsultationRequestEntity c where c.specialty=:specialty and c.assignmentType=vn.nutrimom.consultation.domain.AssignmentType.RANDOM
              and c.status=vn.nutrimom.consultation.domain.ConsultationStatus.PENDING_EXPERT and c.expertUserId is null and c.userId<>:expert
              and (:needle='%%' or exists(select u.id from UserEntity u where u.id=c.userId and lower(u.displayName) like :needle escape '!'))
            order by c.createdAt,c.id
            """)
    Page<ConsultationRequestEntity> poolPage(@Param("specialty") Specialty specialty,@Param("expert") String expert,
            @Param("needle") String needle,Pageable pageable);

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
