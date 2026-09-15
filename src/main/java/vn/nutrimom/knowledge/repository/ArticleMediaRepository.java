package vn.nutrimom.knowledge.repository;
import org.springframework.data.jpa.repository.*;
import vn.nutrimom.knowledge.domain.ArticleMedia;
public interface ArticleMediaRepository extends JpaRepository<ArticleMedia, String> { java.util.Optional<ArticleMedia> findByImageUrl(String url); }
