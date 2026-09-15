package vn.nutrimom.knowledge.repository;
import org.springframework.data.jpa.repository.*;
import vn.nutrimom.knowledge.domain.ArticleSection;
public interface ArticleSectionRepository extends JpaRepository<ArticleSection, String> {  }
