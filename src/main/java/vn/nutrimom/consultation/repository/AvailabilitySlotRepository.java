package vn.nutrimom.consultation.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;
import vn.nutrimom.consultation.domain.SlotStatus;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlotEntity, String> {

    List<AvailabilitySlotEntity> findByExpertUserIdAndSlotDateOrderByStartTimeAsc(
            String expertUserId, LocalDate slotDate);

    /** Lọc slot của chuyên gia theo khoảng ngày và/hoặc trạng thái (mọi tham số đều tùy chọn). */
    @Query("""
            select slot from AvailabilitySlotEntity slot
            where slot.expertUserId = :expertUserId
              and (:from is null or slot.slotDate >= :from)
              and (:to is null or slot.slotDate <= :to)
              and (:status is null or slot.status = :status)
            order by slot.slotDate asc, slot.startTime asc
            """)
    List<AvailabilitySlotEntity> search(
            @Param("expertUserId") String expertUserId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("status") SlotStatus status);

    Optional<AvailabilitySlotEntity> findByIdAndExpertUserId(String id, String expertUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select slot from AvailabilitySlotEntity slot where slot.id = :id")
    Optional<AvailabilitySlotEntity> findByIdForUpdate(@Param("id") String id);
}
