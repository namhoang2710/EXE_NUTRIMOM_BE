package vn.nutrimom.notification.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.notification.domain.NotificationEntity;

public interface NotificationRepository extends JpaRepository<NotificationEntity, String> {

    /** Row-level security: luôn lọc kèm chủ sở hữu, không sở hữu thì 404 qua {@code AccessGuard}. */
    Optional<NotificationEntity> findByIdAndUserId(String id, String userId);

    /** Số chưa đọc, dùng cho {@code unread_notification_count} của dashboard mẹ bầu (spec mục 05). */
    long countByUserIdAndReadAtIsNull(String userId);

    /**
     * Một trang keyset theo {@code (createdAt desc, id desc)} — khớp đúng index
     * {@code ix_notifications_user_created}. Khác offset, cursor kiểu này ổn định khi có bản ghi mới
     * chèn vào giữa hai lần gọi (spec mục 22 "Cursor ổn định khi có insert mới").
     *
     * <p>Trang đầu truyền mốc sentinel rất lớn (xem {@code NotificationCursor.start()}) để mọi dòng
     * đều thoả điều kiện, nhờ vậy chỉ cần một câu truy vấn cho cả trang đầu lẫn trang sau.</p>
     */
    @Query("""
            select notification from NotificationEntity notification
            where notification.userId = :userId
              and (notification.createdAt < :cursorAt
                   or (notification.createdAt = :cursorAt and notification.id < :cursorId))
            order by notification.createdAt desc, notification.id desc
            """)
    List<NotificationEntity> findPage(@Param("userId") String userId,
                                      @Param("cursorAt") OffsetDateTime cursorAt,
                                      @Param("cursorId") String cursorId,
                                      Pageable pageable);

    /** Như {@link #findPage} nhưng chỉ lấy thông báo chưa đọc ({@code ?unread_only=true}). */
    @Query("""
            select notification from NotificationEntity notification
            where notification.userId = :userId
              and notification.readAt is null
              and (notification.createdAt < :cursorAt
                   or (notification.createdAt = :cursorAt and notification.id < :cursorId))
            order by notification.createdAt desc, notification.id desc
            """)
    List<NotificationEntity> findUnreadPage(@Param("userId") String userId,
                                            @Param("cursorAt") OffsetDateTime cursorAt,
                                            @Param("cursorId") String cursorId,
                                            Pageable pageable);

    /**
     * {@code POST /notifications/read-all} — idempotent: chỉ chạm dòng còn {@code readAt is null}
     * nên gọi lại lần hai trả về 0 và không đổi mốc đã đọc cũ.
     *
     * <p>Bulk update bỏ qua {@code @PreUpdate} và không tự tăng {@code @Version}, nên set tay cả hai
     * để dòng sau khi cập nhật vẫn nhất quán với optimistic locking.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationEntity notification
            set notification.readAt = :readAt,
                notification.updatedAt = :readAt,
                notification.version = notification.version + 1
            where notification.userId = :userId and notification.readAt is null
            """)
    int markAllRead(@Param("userId") String userId, @Param("readAt") OffsetDateTime readAt);
}
