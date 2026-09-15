package vn.nutrimom.knowledge.domain;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import vn.nutrimom.auth.domain.UserEntity;
@Entity @Table(name="knowledge_article_bookmarks", schema="app",
 uniqueConstraints=@UniqueConstraint(name="ux_knowledge_bookmarks_article_user", columnNames={"article_id", "user_id"}))
public class ArticleBookmark {
 @Id @Column(name="id", length=36, nullable=false)
 private String id;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="article_id", nullable=false)
 private KnowledgeArticle article;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="user_id", nullable=false)
 private UserEntity user;
 @Column(name="created_at", nullable=false)
 private OffsetDateTime createdAt;
 @Column(name="updated_at", nullable=false)
 private OffsetDateTime updatedAt;
 @PrePersist void insert() {
  if(id==null) id=UUID.randomUUID().toString();
  createdAt=OffsetDateTime.now(ZoneOffset.UTC); updatedAt=createdAt;
 }
 @PreUpdate void update() { updatedAt=OffsetDateTime.now(ZoneOffset.UTC); }
 public String getId() { return id; }
 public void setId(String value) { id=value; }
 public KnowledgeArticle getArticle() { return article; }
 public void setArticle(KnowledgeArticle value) { article=value; }
 public UserEntity getUser() { return user; }
 public void setUser(UserEntity value) { user=value; }
 public OffsetDateTime getCreatedAt() { return createdAt; }
 public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
