package vn.nutrimom.community.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.community.domain.CommunityNotification;

public interface CommunityNotificationRepository extends JpaRepository<CommunityNotification, String> {

    /** Ghi nếu dedup_key chưa có; trả 0 khi là thông báo trùng (vd tim lại sau khi bỏ tim). */
    @Modifying
    @Query(value = """
            insert into app.community_notifications
                (id, recipient_id, type, post_id, actor_id, comment_id, dedup_key, created_at)
            values (:id, :recipientId, :type, :postId, :actorId, :commentId, :dedupKey, :now)
            on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("recipientId") String recipientId,
            @Param("type") String type, @Param("postId") String postId, @Param("actorId") String actorId,
            @Param("commentId") String commentId, @Param("dedupKey") String dedupKey,
            @Param("now") OffsetDateTime now);

    @Query("""
            select n from CommunityNotification n
            where n.recipientId = :recipientId
              and (:unreadOnly = false or n.readAt is null)
              and (n.createdAt < :at or (n.createdAt = :at and n.id < :id))
            order by n.createdAt desc, n.id desc
            """)
    List<CommunityNotification> findPage(@Param("recipientId") String recipientId,
            @Param("unreadOnly") boolean unreadOnly, @Param("at") OffsetDateTime at, @Param("id") String id,
            Pageable window);

    long countByRecipientIdAndReadAtIsNull(String recipientId);

    boolean existsByIdAndRecipientId(String id, String recipientId);

    @Modifying
    @Query("""
            update CommunityNotification n set n.readAt = :now
            where n.id = :id and n.recipientId = :recipientId and n.readAt is null
            """)
    int markRead(@Param("id") String id, @Param("recipientId") String recipientId, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("""
            update CommunityNotification n set n.readAt = :now
            where n.recipientId = :recipientId and n.readAt is null
            """)
    int markAllRead(@Param("recipientId") String recipientId, @Param("now") OffsetDateTime now);
}
