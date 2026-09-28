package vn.nutrimom.consultation.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlotEntity, String> {

    /** Các ô đã bị chiếm trong một ngày (BOOKED hoặc CLOSED). */
    List<AvailabilitySlotEntity> findByExpertUserIdAndSlotDateOrderByStartTimeAsc(
            String expertUserId, LocalDate slotDate);

    /** Các ô đã bị chiếm trong một khoảng ngày, dùng cho bảng tổng hợp theo ngày. */
    List<AvailabilitySlotEntity> findByExpertUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
            String expertUserId, LocalDate from, LocalDate to);

    /**
     * Khóa ghi mọi ô đã bị chiếm của chuyên gia trong một ngày.
     *
     * <p>Cố ý <strong>không</strong> lọc {@code startTime} trong mệnh đề WHERE: driver SQL
     * Server mặc định {@code sendTimeAsDatetime=true} nên tham số {@code LocalTime} được gửi
     * dưới dạng {@code datetime}, và SQL Server từ chối so sánh {@code time = datetime}
     * (lỗi 402). Lọc mốc giờ trong bộ nhớ ở {@link #findForUpdate} thay vì vậy — tối đa 24
     * dòng một ngày nên không đáng kể.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select slot from AvailabilitySlotEntity slot
            where slot.expertUserId = :expertUserId
              and slot.slotDate = :slotDate
            """)
    List<AvailabilitySlotEntity> lockDay(
            @Param("expertUserId") String expertUserId,
            @Param("slotDate") LocalDate slotDate);

    /**
     * Khóa ghi ngày đó rồi lấy đúng ô cần đặt/đóng. Lưu ý: khóa bi quan không giữ được dòng
     * chưa tồn tại, nên chốt chặn thật sự cho hai lượt đặt song song vẫn là unique index
     * {@code ux_consultation_slots_expert_date_start}.
     */
    default Optional<AvailabilitySlotEntity> findForUpdate(
            String expertUserId, LocalDate slotDate, LocalTime startTime) {
        return lockDay(expertUserId, slotDate).stream()
                .filter(slot -> startTime.equals(slot.getStartTime()))
                .findFirst();
    }
}
