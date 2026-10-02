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
     * Hồ sơ y tế của chủ sở hữu trong cửa sổ nửa mở {@code [from, to)}.
     *
     * <p>{@code occurredAt} đã là mốc tuyệt đối nên lọc thẳng ở DB được, không cần nới biên như
     * phía buổi tư vấn.</p>
     */
    @Query("""
            select new vn.nutrimom.calendar.dto.MedicalRecordCalendarRow(
                record.id, record.title, record.occurredAt, record.category, record.facilityName)
            from MedicalRecordEntity record
            where record.ownerUserId = :ownerUserId
              and record.deletedAt is null
              and record.occurredAt >= :from
              and record.occurredAt < :to
            order by record.occurredAt asc, record.id asc
            """)
    List<MedicalRecordCalendarRow> findWindow(@Param("ownerUserId") String ownerUserId,
                                              @Param("from") OffsetDateTime from,
                                              @Param("to") OffsetDateTime to);
}
