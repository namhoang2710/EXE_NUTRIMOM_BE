package vn.nutrimom.calendar.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.calendar.dto.MedicalRecordCalendarRow;
import vn.nutrimom.medicalrecord.domain.MedicalRecordEntity;

/**
 * Cửa đọc hồ sơ y tế dành riêng cho lịch.
 *
 * <p>Repository này do module calendar sở hữu dù entity thuộc module medicalrecord: nhờ vậy hướng
 * phụ thuộc là calendar → medicalrecord, và module hồ sơ y tế không phải thêm method cho một màn
 * hình nó không biết tới. Spring Data cho phép nhiều repository trên cùng một entity.</p>
 *
 * <p>Chỉ khai báo {@link Repository} (không phải {@code JpaRepository}) để bề mặt đúng bằng một
 * câu đọc — không vô tình mở ra save/delete trên bảng của module khác.</p>
 */
public interface CalendarMedicalRecordQueryRepository extends Repository<MedicalRecordEntity, String> {

    /**
     * Hồ sơ y tế của <strong>thai kỳ đang ACTIVE</strong> trong cửa sổ nửa mở {@code [from, to)}.
     *
     * <p>Lịch là màn hình theo dõi thai kỳ đang diễn ra, nên hồ sơ của thai kỳ đã đóng
     * (COMPLETED/LOSS_REPORTED/ARCHIVED) cố ý không được vẽ lên đây — nếu vẽ thì người dùng bấm vào
     * chấm sẽ rơi xuống một bản ghi mà màn hình quản lý hồ sơ (vốn lọc theo thai kỳ) không liệt kê,
     * và lịch của thai kỳ lần 2 sẽ lẫn mốc khám của lần 1. Lịch sử các thai kỳ cũ thuộc về màn hình
     * quản lý hồ sơ, không thuộc về lịch.</p>
     *
     * <p>Mỗi người tối đa một thai kỳ ACTIVE, nên phép nối không nhân dòng. Người chưa có thai kỳ
     * ACTIVE nào thì nguồn này rỗng — lịch vẫn vẽ bình thường với hai nguồn còn lại.</p>
     *
     * <p>Điều kiện {@code pregnancy.ownerUserId} là thừa về mặt dữ liệu sạch nhưng giữ lại để một
     * hồ sơ bị gán nhầm thai kỳ của người khác không lọt qua cửa này.</p>
     *
     * <p>{@code occurredAt} đã là mốc tuyệt đối nên lọc thẳng ở DB được, không cần nới biên như
     * phía buổi tư vấn.</p>
     */
    @Query("""
            select new vn.nutrimom.calendar.dto.MedicalRecordCalendarRow(
                record.id, record.title, record.occurredAt, record.category, record.facilityName)
            from MedicalRecordEntity record
            join vn.nutrimom.pregnancy.domain.PregnancyEntity pregnancy
              on pregnancy.id = record.pregnancyId
            where record.ownerUserId = :ownerUserId
              and pregnancy.ownerUserId = :ownerUserId
              and pregnancy.status = vn.nutrimom.pregnancy.domain.PregnancyStatus.ACTIVE
              and record.deletedAt is null
              and record.occurredAt >= :from
              and record.occurredAt < :to
            order by record.occurredAt asc, record.id asc
            """)
    List<MedicalRecordCalendarRow> findActivePregnancyWindow(@Param("ownerUserId") String ownerUserId,
                                                             @Param("from") OffsetDateTime from,
                                                             @Param("to") OffsetDateTime to);
}
