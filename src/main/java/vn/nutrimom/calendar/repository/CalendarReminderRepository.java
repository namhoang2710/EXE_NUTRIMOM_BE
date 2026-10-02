package vn.nutrimom.calendar.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.calendar.domain.CalendarReminderEntity;
import vn.nutrimom.calendar.domain.ReminderStatus;

public interface CalendarReminderRepository extends JpaRepository<CalendarReminderEntity, String> {

    /** Mọi lượt đọc một dòng đều đi qua đây: owner nằm trong WHERE nên truy cập chéo thành 404. */
    Optional<CalendarReminderEntity> findByIdAndOwnerUserIdAndDeletedAtIsNull(String id, String ownerUserId);

    /**
     * Nhắc nhở của một người có thể rơi vào cửa sổ nửa mở {@code [from, to)}.
     *
     * <p>Mốc một lần thì so thẳng {@code startsAt}. Chuỗi lặp thì <strong>không</strong> so được
     * như vậy: một chuỗi "uống vitamin hằng ngày" tạo từ hai tháng trước vẫn phải hiện trong tháng
     * này, trong khi {@code startsAt} của nó chỉ là điểm neo nằm ngoài cửa sổ. Với chuỗi, điều kiện
     * là "đã bắt đầu trước khi cửa sổ kết thúc, và chưa kết thúc trước khi cửa sổ bắt đầu"; việc
     * chốt đúng từng mốc do {@code ReminderSchedule} làm sau khi đọc.</p>
     *
     * <p>Bỏ {@link ReminderStatus#CANCELLED} khỏi lịch: người dùng đã nói là không đi nữa, giữ lại
     * chỉ để tra lịch sử qua {@code GET /calendar/reminders?status=CANCELLED}.</p>
     */
    @Query("""
            select reminder from CalendarReminderEntity reminder
            where reminder.ownerUserId = :ownerUserId
              and reminder.deletedAt is null
              and reminder.status <> vn.nutrimom.calendar.domain.ReminderStatus.CANCELLED
              and reminder.startsAt < :to
              and (
                    (reminder.repeatRule is null and reminder.startsAt >= :from)
                 or (reminder.repeatRule is not null
                     and (reminder.repeatUntil is null or reminder.repeatUntil >= :fromDate))
              )
            order by reminder.startsAt asc, reminder.id asc
            """)
    List<CalendarReminderEntity> findWindow(@Param("ownerUserId") String ownerUserId,
                                            @Param("from") OffsetDateTime from,
                                            @Param("to") OffsetDateTime to,
                                            @Param("fromDate") LocalDate fromDate);

    /**
     * Danh sách đầy đủ cho {@code GET /calendar/reminders}; {@code status} null = mọi trạng thái.
     * Cùng cách lọc chuỗi lặp như {@link #findWindow}.
     */
    @Query("""
            select reminder from CalendarReminderEntity reminder
            where reminder.ownerUserId = :ownerUserId
              and reminder.deletedAt is null
              and (:status is null or reminder.status = :status)
              and reminder.startsAt < :to
              and (
                    (reminder.repeatRule is null and reminder.startsAt >= :from)
                 or (reminder.repeatRule is not null
                     and (reminder.repeatUntil is null or reminder.repeatUntil >= :fromDate))
              )
            order by reminder.startsAt asc, reminder.id asc
            """)
    List<CalendarReminderEntity> findForList(@Param("ownerUserId") String ownerUserId,
                                             @Param("status") ReminderStatus status,
                                             @Param("from") OffsetDateTime from,
                                             @Param("to") OffsetDateTime to,
                                             @Param("fromDate") LocalDate fromDate);

    /**
     * Hàng đợi của job nhắc lịch.
     *
     * <p>Trả về <strong>id</strong> chứ không phải entity: mỗi dòng sau đó được xử lý trong một
     * transaction riêng, nên entity load ở đây sẽ mang {@code version} cũ ngay khi
     * {@link #claim} chạy.</p>
     *
     * <p>So sánh phẳng trên cột dẫn xuất {@code remindAt} thay vì trừ interval theo cột — xem
     * javadoc của {@code CalendarReminderEntity}.</p>
     */
    @Query("""
            select reminder.id from CalendarReminderEntity reminder
            where reminder.deletedAt is null
              and reminder.status = vn.nutrimom.calendar.domain.ReminderStatus.SCHEDULED
              and reminder.remindAt is not null
              and reminder.remindAt <= :now
            order by reminder.remindAt asc
            """)
    List<String> findDueIds(@Param("now") OffsetDateTime now);

    /**
     * Giành quyền gửi MỘT lần lặp. Trả 1 nếu chưa ai gửi mốc đó, 0 nếu đã có người giành trước —
     * đây là thứ làm job idempotent và an toàn khi chạy nhiều instance.
     *
     * <p>Khoá chống trùng là mốc lặp ({@code lastFiredOccurrence}) chứ không phải một cờ boolean:
     * một chuỗi lặp bắn xong hôm nay vẫn phải bắn tiếp ngày mai, nên "đã gửi rồi" phải nói rõ là
     * gửi tới mốc nào. Cùng lúc đó {@code remindAt} được dời tới mốc nhắc của lần kế tiếp (hoặc
     * null khi chuỗi đã hết) để lượt quét sau tìm đúng việc.</p>
     *
     * <p>Cố ý <strong>không</strong> bump {@code version} và không chạy {@code @PreUpdate}: đây là
     * cờ vận hành của job, không phải thay đổi dữ liệu người dùng, nên nó không được biến một lần
     * PATCH hợp lệ của client thành {@code VERSION_CONFLICT}. Vì vậy claim phải là thao tác ghi
     * <em>duy nhất</em> job thực hiện trên dòng đó.</p>
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update CalendarReminderEntity reminder
            set reminder.lastFiredOccurrence = :occurrence,
                reminder.notifiedAt = :now,
                reminder.remindAt = :nextRemindAt
            where reminder.id = :id
              and (reminder.lastFiredOccurrence is null
                   or reminder.lastFiredOccurrence < :occurrence)
            """)
    int claim(@Param("id") String id,
              @Param("occurrence") OffsetDateTime occurrence,
              @Param("nextRemindAt") OffsetDateTime nextRemindAt,
              @Param("now") OffsetDateTime now);
}
