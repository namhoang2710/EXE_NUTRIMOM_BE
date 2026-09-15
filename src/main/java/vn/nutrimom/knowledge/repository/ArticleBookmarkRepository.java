package vn.nutrimom.knowledge.repository;
import org.springframework.data.jpa.repository.*;
import vn.nutrimom.knowledge.domain.ArticleBookmark;
public interface ArticleBookmarkRepository extends JpaRepository<ArticleBookmark, String> { boolean existsByArticleIdAndUserId(String articleId, String userId);
 void deleteByArticleIdAndUserId(String articleId, String userId);
 void deleteByArticleId(String articleId); }
