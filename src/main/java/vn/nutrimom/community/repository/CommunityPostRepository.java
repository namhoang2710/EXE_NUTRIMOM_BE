package vn.nutrimom.community.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.community.domain.CommunityPost;
import vn.nutrimom.community.domain.PostStatus;
import vn.nutrimom.community.domain.PostTopic;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, String> {

    /**
     * Feed công khai, keyset theo (published_at desc, id desc). Bài ghim không nằm ở đây mà trả riêng
     * ở trang đầu, nên một bài không bao giờ xuất hiện hai lần.
     */
    @Query("""
            select p from CommunityPost p
            where p.status = vn.nutrimom.community.domain.PostStatus.APPROVED and p.deletedAt is null
              and p.pinnedAt is null
              and (:topic is null or p.topic = :topic)
              and (:hasImages = false or p.imageCount > 0)
              and (:savedBy is null or exists (select 1 from CommunityPostSave s
                                                where s.postId = p.id and s.userId = :savedBy))
              and (p.publishedAt < :at or (p.publishedAt = :at and p.id < :id))
            order by p.publishedAt desc, p.id desc
            """)
    List<CommunityPost> findFeedPage(@Param("topic") PostTopic topic, @Param("hasImages") boolean hasImages,
            @Param("savedBy") String savedBy, @Param("at") OffsetDateTime at, @Param("id") String id,
            Pageable window);

    @Query("""
            select p from CommunityPost p
            where p.status = vn.nutrimom.community.domain.PostStatus.APPROVED and p.deletedAt is null
              and p.pinnedAt is not null
              and (:topic is null or p.topic = :topic)
              and (:hasImages = false or p.imageCount > 0)
              and (:savedBy is null or exists (select 1 from CommunityPostSave s
                                                where s.postId = p.id and s.userId = :savedBy))
            order by p.pinnedAt desc, p.id desc
            """)
    List<CommunityPost> findPinned(@Param("topic") PostTopic topic, @Param("hasImages") boolean hasImages,
            @Param("savedBy") String savedBy, Pageable window);

    @Query("select count(p) from CommunityPost p where p.pinnedAt is not null and p.deletedAt is null")
    long countPinned();

    /** Bài của chính người dùng, mọi trạng thái trừ bài đã tự xoá. */
    @Query("""
            select p from CommunityPost p
            where p.authorId = :authorId and p.deletedAt is null
              and (:status is null or p.status = :status)
              and (p.createdAt < :at or (p.createdAt = :at and p.id < :id))
            order by p.createdAt desc, p.id desc
            """)
    List<CommunityPost> findMinePage(@Param("authorId") String authorId, @Param("status") PostStatus status,
            @Param("at") OffsetDateTime at, @Param("id") String id, Pageable window);

    /** Hàng kiểm duyệt của admin. Bài người dùng tự xoá không hiện, bài admin gỡ (REMOVED) thì có. */
    @Query("""
            select p from CommunityPost p
            where (:status is null or p.status = :status)
              and (p.deletedAt is null or p.status = vn.nutrimom.community.domain.PostStatus.REMOVED)
              and (p.createdAt < :at or (p.createdAt = :at and p.id < :id))
            order by p.createdAt desc, p.id desc
            """)
    List<CommunityPost> findAdminPage(@Param("status") PostStatus status, @Param("at") OffsetDateTime at,
            @Param("id") String id, Pageable window);

    long countByAuthorIdAndCreatedAtGreaterThanEqual(String authorId, OffsetDateTime since);

    /** Cộng/trừ nguyên tử; điều kiện >= 0 chặn bộ đếm âm khi hai request bỏ tim chạy cùng lúc. */
    @Modifying
    @Query("""
            update CommunityPost p set p.likeCount = p.likeCount + :delta
            where p.id = :id and p.likeCount + :delta >= 0
            """)
    int addLikes(@Param("id") String id, @Param("delta") int delta);

    @Modifying
    @Query("""
            update CommunityPost p set p.commentCount = p.commentCount + :delta
            where p.id = :id and p.commentCount + :delta >= 0
            """)
    int addComments(@Param("id") String id, @Param("delta") int delta);

    @Query("select p.likeCount from CommunityPost p where p.id = :id")
    int likeCount(@Param("id") String id);
}
