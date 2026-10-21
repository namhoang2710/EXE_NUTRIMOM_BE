package vn.nutrimom.community.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.community.domain.CommunityPostImage;

public interface CommunityPostImageRepository extends JpaRepository<CommunityPostImage, String> {

    @Query("""
            select i from CommunityPostImage i
            where i.postId in :postIds and i.status = vn.nutrimom.community.domain.ImageStatus.ATTACHED
            order by i.postId, i.sortOrder
            """)
    List<CommunityPostImage> findAttached(@Param("postIds") Collection<String> postIds);

    /**
     * Gắn một ảnh PENDING vào bài. Điều kiện nằm trong câu UPDATE nên hai request gắn cùng một ảnh
     * vào hai bài chỉ một bên thắng; bên thua nhận 0 dòng và rollback.
     */
    @Modifying
    @Query("""
            update CommunityPostImage i
            set i.postId = :postId, i.status = vn.nutrimom.community.domain.ImageStatus.ATTACHED,
                i.sortOrder = :sortOrder, i.deleteAfter = null, i.updatedAt = :now
            where i.id = :id and i.ownerId = :ownerId
              and i.status = vn.nutrimom.community.domain.ImageStatus.PENDING and i.deleteAfter > :now
            """)
    int attach(@Param("id") String id, @Param("ownerId") String ownerId, @Param("postId") String postId,
            @Param("sortOrder") int sortOrder, @Param("now") OffsetDateTime now);

    /** Ảnh đã thuộc đúng bài này: chỉ đổi thứ tự. */
    @Modifying
    @Query("""
            update CommunityPostImage i set i.sortOrder = :sortOrder, i.updatedAt = :now
            where i.id = :id and i.postId = :postId
              and i.status = vn.nutrimom.community.domain.ImageStatus.ATTACHED
            """)
    int reorder(@Param("id") String id, @Param("postId") String postId, @Param("sortOrder") int sortOrder,
            @Param("now") OffsetDateTime now);

    /** Chuyển mọi ảnh của bài sang ORPHANED (xoá bài / gỡ bài). Object R2 chỉ bị xoá sau deleteAfter. */
    @Modifying
    @Query("""
            update CommunityPostImage i
            set i.status = vn.nutrimom.community.domain.ImageStatus.ORPHANED, i.deleteAfter = :deleteAfter,
                i.updatedAt = :now
            where i.postId = :postId and i.status = vn.nutrimom.community.domain.ImageStatus.ATTACHED
            """)
    int orphanAll(@Param("postId") String postId, @Param("deleteAfter") OffsetDateTime deleteAfter,
            @Param("now") OffsetDateTime now);

    /** Như {@link #orphanAll} nhưng giữ lại các ảnh còn trong bài sau khi sửa ({@code keep} không rỗng). */
    @Modifying
    @Query("""
            update CommunityPostImage i
            set i.status = vn.nutrimom.community.domain.ImageStatus.ORPHANED, i.deleteAfter = :deleteAfter,
                i.updatedAt = :now
            where i.postId = :postId and i.status = vn.nutrimom.community.domain.ImageStatus.ATTACHED
              and i.id not in :keep
            """)
    int orphanExcept(@Param("postId") String postId, @Param("keep") Collection<String> keep,
            @Param("deleteAfter") OffsetDateTime deleteAfter, @Param("now") OffsetDateTime now);

    long countByOwnerIdAndCreatedAtGreaterThanEqual(String ownerId, OffsetDateTime since);

    @Query("""
            select i from CommunityPostImage i
            where i.status in (vn.nutrimom.community.domain.ImageStatus.PENDING,
                               vn.nutrimom.community.domain.ImageStatus.ORPHANED)
              and i.deleteAfter < :now
            order by i.deleteAfter
            """)
    List<CommunityPostImage> findDueForCleanup(@Param("now") OffsetDateTime now, Pageable window);
}
