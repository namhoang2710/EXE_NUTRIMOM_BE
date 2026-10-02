package vn.nutrimom.calendar.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.calendar.domain.CalendarReminderOccurrenceEntity;

public interface CalendarReminderOccurrenceRepository
        extends JpaRepository<CalendarReminderOccurrenceEntity, String> {

    Optional<CalendarReminderOccurrenceEntity> findByReminderIdAndOccurrenceAt(
            String reminderId, OffsetDateTime occurrenceAt);

    /**
     * Trạng thái đã đánh dấu của mọi lần lặp trong cửa sổ đang vẽ lịch.
     *
     * <p>Lấy một lượt cho tất cả nhắc nhở đang hiện thay vì hỏi từng mốc: một nhắc nhở hằng ngày
     * trong một tháng là 30 mốc, hỏi lẻ sẽ thành 30 lượt truy vấn cho mỗi dòng.</p>
     */
    @Query("""
            select occurrence from CalendarReminderOccurrenceEntity occurrence
            where occurrence.reminderId in :reminderIds
              and occurrence.occurrenceAt >= :from
              and occurrence.occurrenceAt < :to
            """)
    List<CalendarReminderOccurrenceEntity> findWindow(
            @Param("reminderIds") Collection<String> reminderIds,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}
