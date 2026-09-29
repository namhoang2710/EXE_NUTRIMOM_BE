package vn.nutrimom.notification.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.notification.domain.ActivityEventEntity;
import vn.nutrimom.notification.domain.ActivityVisibility;

public interface ActivityEventRepository extends JpaRepository<ActivityEventEntity, String> {

    /**
     * Feed của chính chủ: mọi hoạt động có {@code subjectUserId} là caller, kể cả
     * {@link ActivityVisibility#OWNER_ONLY}.
     *
     * <p>Cursor keyset giống {@code NotificationRepository#findPage}; trang đầu truyền mốc sentinel.</p>
     */
    @Query("""
            select event from ActivityEventEntity event
            where event.subjectUserId = :userId
              and (event.createdAt < :cursorAt
                   or (event.createdAt = :cursorAt and event.id < :cursorId))
            order by event.createdAt desc, event.id desc
            """)
    List<ActivityEventEntity> findOwnFeed(@Param("userId") String userId,
                                          @Param("cursorAt") OffsetDateTime cursorAt,
                                          @Param("cursorId") String cursorId,
                                          Pageable pageable);

    /**
     * Feed của chính chủ cộng thêm hoạt động được chia sẻ từ các thai kỳ mà caller là thành viên gia
     * đình có scope {@code ACTIVITY_FEED}.
     *
     * <p>Chỉ hoạt động {@link ActivityVisibility#FAMILY} mới lọt qua nhánh chia sẻ — nhờ vậy việc
     * riêng tư/y tế ghi {@code OWNER_ONLY} không bao giờ hiện trên feed của người khác.
     * {@code pregnancyIds} phải khác rỗng; rỗng thì gọi {@link #findOwnFeed} thay vì gọi hàm này.</p>
     */
    @Query("""
            select event from ActivityEventEntity event
            where (event.subjectUserId = :userId
                   or (event.visibility = :familyVisibility and event.pregnancyId in :pregnancyIds))
              and (event.createdAt < :cursorAt
                   or (event.createdAt = :cursorAt and event.id < :cursorId))
            order by event.createdAt desc, event.id desc
            """)
    List<ActivityEventEntity> findSharedFeed(@Param("userId") String userId,
                                             @Param("pregnancyIds") Collection<String> pregnancyIds,
                                             @Param("familyVisibility") ActivityVisibility familyVisibility,
                                             @Param("cursorAt") OffsetDateTime cursorAt,
                                             @Param("cursorId") String cursorId,
                                             Pageable pageable);

    /**
     * Vài hoạt động gần nhất của một thai kỳ, dùng cho block {@code activity_feed} của dashboard
     * partner (spec mục 05). Không phân trang vì dashboard chỉ hiển thị một đoạn ngắn.
     */
    List<ActivityEventEntity> findByPregnancyIdAndVisibilityOrderByCreatedAtDescIdDesc(
            String pregnancyId, ActivityVisibility visibility, Pageable pageable);
}
