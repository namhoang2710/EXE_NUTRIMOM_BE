package vn.nutrimom.knowledge.repository;
import org.springframework.data.jpa.repository.*;
import vn.nutrimom.knowledge.domain.KnowledgeArticle;
public interface KnowledgeArticleRepository extends JpaRepository<KnowledgeArticle, String>, JpaSpecificationExecutor<KnowledgeArticle> { boolean existsBySlug(String slug);
 boolean existsBySlugAndIdNot(String slug, String id);
 java.util.Optional<KnowledgeArticle> findBySlug(String slug); }
