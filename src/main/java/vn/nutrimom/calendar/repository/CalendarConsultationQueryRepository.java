package vn.nutrimom.calendar.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.calendar.dto.ConfirmedConsultationRow;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;

/**
 * Cửa đọc buổi tư vấn đã có khung giờ, dành riêng cho lịch. Cùng lý do sở hữu như
 * {@link CalendarMedicalRecordQueryRepository}.
 *
 * <p>Join giữa hai entity không có association được viết tay bằng {@code join ... on}, đúng kiểu
 * {@code ContactRequestRepository#searchForAdmin} đang dùng.</p>
 */
public interface CalendarConsultationQueryRepository extends Repository<ConsultationRequestEntity, String> {

    /**
     * Buổi tư vấn của một người trong khoảng ngày (giờ Việt Nam).
     *
     * <p>Chỉ dòng có {@code slotId} mới join được, mà slot chỉ tồn tại khi chuyên gia đã xác nhận
     * (hoặc user đặt trực tiếp đúng khung giờ) — đúng yêu cầu "bác sĩ đồng ý thì mới lên lịch".
     * Yêu cầu đã huỷ cũng tự rụng vì lúc huỷ {@code slot_id} được trả về null.</p>
     *
     * <p><strong>Biên ngày phải được nới ±1 ngày khi gọi.</strong> {@code slotDate} là ngày theo
     * giờ VN, còn cửa sổ truy vấn là ngày địa phương của người xem; hai thứ đó lệch nhau nên lọc
     * khít ở đây sẽ rụng mất nửa ngày. Phép lọc chính xác làm trong
     * {@code CalendarQueryService} sau khi quy đổi về mốc tuyệt đối.</p>
     */
    @Query("""
            select new vn.nutrimom.calendar.dto.ConfirmedConsultationRow(
                request.id, request.status, slot.slotDate, slot.startTime, slot.endTime,
                expert.fullName)
            from ConsultationRequestEntity request
                join AvailabilitySlotEntity slot on slot.id = request.slotId
                left join ExpertProfileEntity expert on expert.userId = request.expertUserId
            where request.userId = :userId
              and request.status in :statuses
              and slot.slotDate >= :fromDate
              and slot.slotDate <= :toDate
            order by slot.slotDate asc, slot.startTime asc
            """)
    List<ConfirmedConsultationRow> findConfirmed(@Param("userId") String userId,
                                                 @Param("statuses") Collection<ConsultationStatus> statuses,
                                                 @Param("fromDate") LocalDate fromDate,
                                                 @Param("toDate") LocalDate toDate);
}
