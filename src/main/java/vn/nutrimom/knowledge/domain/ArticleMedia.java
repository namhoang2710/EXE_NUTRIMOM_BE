package vn.nutrimom.knowledge.domain;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import vn.nutrimom.auth.domain.UserEntity;
@Entity @Table(name="knowledge_article_media", schema="app",
 uniqueConstraints=@UniqueConstraint(name="ux_knowledge_media_key", columnNames="image_key"))
public class ArticleMedia {
 @Id @Column(name="id", length=36, nullable=false)
 private String id;
 @Column(name="image_key", length=300, nullable=false)
 private String imageKey;
 @Column(name="image_url", length=2048, nullable=false)
 private String imageUrl;
 @Column(name="alt", length=500)
 private String alt;
 @Column(name="caption", length=1000)
 private String caption;
 @Column(name="content_type", length=50, nullable=false)
 private String contentType;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="uploaded_by", nullable=false)
 private UserEntity uploadedBy;
 @Column(name="width", nullable=false)
 private int width;
 @Column(name="height", nullable=false)
 private int height;
 @Column(name="size_bytes", nullable=false)
 private long sizeBytes;
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
 public String getImageKey() { return imageKey; }
 public void setImageKey(String value) { imageKey=value; }
 public String getImageUrl() { return imageUrl; }
 public void setImageUrl(String value) { imageUrl=value; }
 public String getAlt() { return alt; }
 public void setAlt(String value) { alt=value; }
 public String getCaption() { return caption; }
 public void setCaption(String value) { caption=value; }
 public String getContentType() { return contentType; }
 public void setContentType(String value) { contentType=value; }
 public UserEntity getUploadedBy() { return uploadedBy; }
 public void setUploadedBy(UserEntity value) { uploadedBy=value; }
 public int getWidth() { return width; }
 public void setWidth(int value) { width=value; }
 public int getHeight() { return height; }
 public void setHeight(int value) { height=value; }
 public long getSizeBytes() { return sizeBytes; }
 public void setSizeBytes(long value) { sizeBytes=value; }
 public OffsetDateTime getCreatedAt() { return createdAt; }
 public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
