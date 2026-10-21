package vn.nutrimom.community.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.community.domain.CommunityComment;

public interface CommunityCommentRepository extends JpaRepository<CommunityComment, String> {

    /** Cũ → mới, keyset theo (created_at asc, id asc). */
    @Query("""
            select c from CommunityComment c
            where c.postId = :postId and c.deletedAt is null
              and (c.createdAt > :at or (c.createdAt = :at and c.id > :id))
            order by c.createdAt asc, c.id asc
            """)
    List<CommunityComment> findPage(@Param("postId") String postId, @Param("at") OffsetDateTime at,
            @Param("id") String id, Pageable window);

    long countByAuthorIdAndCreatedAtGreaterThanEqual(String authorId, OffsetDateTime since);
}
