package vn.nutrimom.knowledge.repository;
import org.springframework.data.jpa.repository.*;
import vn.nutrimom.knowledge.domain.ArticleBookmark;
public interface ArticleBookmarkRepository extends JpaRepository<ArticleBookmark, String> { boolean existsByArticleIdAndUserId(String articleId, String userId);
 void deleteByArticleIdAndUserId(String articleId, String userId);
 void deleteByArticleId(String articleId);
 @Query("select b from ArticleBookmark b join fetch b.article a where b.user.id = :userId and a.status = :status and a.publishedAt <= :now order by b.createdAt desc")
 java.util.List<ArticleBookmark> findVisibleForAssistant(@org.springframework.data.repository.query.Param("userId") String userId,
         @org.springframework.data.repository.query.Param("status") vn.nutrimom.knowledge.domain.ArticleStatus status,
         @org.springframework.data.repository.query.Param("now") java.time.OffsetDateTime now, org.springframework.data.domain.Pageable pageable);
 @Query("select count(b) from ArticleBookmark b where b.user.id = :userId and b.article.status = :status and b.article.publishedAt <= :now")
 long countVisibleForAssistant(@org.springframework.data.repository.query.Param("userId") String userId,
         @org.springframework.data.repository.query.Param("status") vn.nutrimom.knowledge.domain.ArticleStatus status,
         @org.springframework.data.repository.query.Param("now") java.time.OffsetDateTime now);
}
