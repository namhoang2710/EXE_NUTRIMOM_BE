package vn.nutrimom.community.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.community.domain.CommunityPostLike;
import vn.nutrimom.community.domain.PostUserId;

public interface CommunityPostLikeRepository extends JpaRepository<CommunityPostLike, PostUserId> {

    /** 1 = vừa tim, 0 = đã tim từ trước. Khoá chính chặn trùng kể cả khi hai request tới cùng lúc. */
    @Modifying
    @Query(value = """
            insert into app.community_post_likes (post_id, user_id, created_at)
            values (:postId, :userId, :now) on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("postId") String postId, @Param("userId") String userId,
            @Param("now") OffsetDateTime now);

    @Modifying
    @Query("delete from CommunityPostLike l where l.postId = :postId and l.userId = :userId")
    int remove(@Param("postId") String postId, @Param("userId") String userId);

    @Query("select l.postId from CommunityPostLike l where l.userId = :userId and l.postId in :postIds")
    List<String> findLikedPostIds(@Param("userId") String userId, @Param("postIds") Collection<String> postIds);
}
