package vn.nutrimom.knowledge.domain;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import vn.nutrimom.auth.domain.UserEntity;
@Entity @Table(name="knowledge_article_sections", schema="app")
public class ArticleSection {
 @Id @Column(name="id", length=36, nullable=false)
 private String id;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="article_id", nullable=false)
 private KnowledgeArticle article;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="media_id")
 private ArticleMedia media;
 @Column(name="heading", length=300, nullable=false)
 private String heading;
 @Column(name="paragraphs", columnDefinition="nvarchar(max)", nullable=false)
 private String paragraphs;
 @Column(name="bullets", columnDefinition="nvarchar(max)", nullable=false)
 private String bullets;
 @Column(name="image_url", length=2048)
 private String imageUrl;
 @Column(name="image_alt", length=500)
 private String imageAlt;
 @Column(name="image_caption", length=1000)
 private String imageCaption;
 @Column(name="sort_order", nullable=false)
 private int sortOrder;
 public String getId() { return id; }
 public void setId(String value) { id=value; }
 public KnowledgeArticle getArticle() { return article; }
 public void setArticle(KnowledgeArticle value) { article=value; }
 public ArticleMedia getMedia() { return media; }
 public void setMedia(ArticleMedia value) { media=value; }
 public String getHeading() { return heading; }
 public void setHeading(String value) { heading=value; }
 public String getParagraphs() { return paragraphs; }
 public void setParagraphs(String value) { paragraphs=value; }
 public String getBullets() { return bullets; }
 public void setBullets(String value) { bullets=value; }
 public String getImageUrl() { return imageUrl; }
 public void setImageUrl(String value) { imageUrl=value; }
 public String getImageAlt() { return imageAlt; }
 public void setImageAlt(String value) { imageAlt=value; }
 public String getImageCaption() { return imageCaption; }
 public void setImageCaption(String value) { imageCaption=value; }
 public int getSortOrder() { return sortOrder; }
 public void setSortOrder(int value) { sortOrder=value; }
}
