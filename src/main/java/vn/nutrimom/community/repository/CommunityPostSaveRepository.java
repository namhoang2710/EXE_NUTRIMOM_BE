package vn.nutrimom.community.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.community.domain.CommunityPostSave;
import vn.nutrimom.community.domain.PostUserId;

public interface CommunityPostSaveRepository extends JpaRepository<CommunityPostSave, PostUserId> {

    /** 1 = vừa lưu, 0 = đã lưu từ trước. */
    @Modifying
    @Query(value = """
            insert into app.community_post_saves (post_id, user_id, created_at)
            values (:postId, :userId, :now) on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("postId") String postId, @Param("userId") String userId,
            @Param("now") OffsetDateTime now);

    @Modifying
    @Query("delete from CommunityPostSave s where s.postId = :postId and s.userId = :userId")
    int remove(@Param("postId") String postId, @Param("userId") String userId);

    @Query("select s.postId from CommunityPostSave s where s.userId = :userId and s.postId in :postIds")
    List<String> findSavedPostIds(@Param("userId") String userId, @Param("postIds") Collection<String> postIds);
}
