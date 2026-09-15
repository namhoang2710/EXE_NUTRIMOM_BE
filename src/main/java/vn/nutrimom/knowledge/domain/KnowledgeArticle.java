package vn.nutrimom.knowledge.domain;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import vn.nutrimom.auth.domain.UserEntity;
@Entity @Table(name="knowledge_articles", schema="app",
 uniqueConstraints=@UniqueConstraint(name="ux_knowledge_articles_slug", columnNames="slug"))
public class KnowledgeArticle {
 @Id @Column(name="id", length=36, nullable=false)
 private String id;
 @Column(name="slug", length=180, nullable=false)
 private String slug;
 @Column(name="title", length=300, nullable=false)
 private String title;
 @Column(name="excerpt", length=2000)
 private String excerpt;
 @Column(name="category", length=100, nullable=false)
 private String category;
 @Column(name="stage", length=100, nullable=false)
 private String stage;
 @Column(name="cover_image_url", length=2048)
 private String coverImageUrl;
 @Column(name="cover_image_alt", length=500)
 private String coverImageAlt;
 @Column(name="cover_image_caption", length=1000)
 private String coverImageCaption;
 @Column(name="source_label", length=300)
 private String sourceLabel;
 @Column(name="source_href", length=2048)
 private String sourceHref;
 @Column(name="lead", columnDefinition="nvarchar(max)")
 private String lead;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="cover_media_id")
 private ArticleMedia coverMedia;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="author_id", nullable=false)
 private UserEntity author;
 @Enumerated(EnumType.STRING) @Column(name="status", length=20, nullable=false)
 private ArticleStatus status;
 @Column(name="published_at")
 private OffsetDateTime publishedAt;
 @Version @Column(name="version", nullable=false)
 private long version;
 @ElementCollection @CollectionTable(name="knowledge_article_topics", schema="app", joinColumns=@JoinColumn(name="article_id")) @Column(name="topic", length=100, nullable=false)
 private Set<String> topics = new LinkedHashSet<>();
 @OneToMany(mappedBy="article", cascade=CascadeType.ALL, orphanRemoval=true) @OrderBy("sortOrder ASC")
 private List<ArticleSection> sections = new ArrayList<>();
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
 public String getSlug() { return slug; }
 public void setSlug(String value) { slug=value; }
 public String getTitle() { return title; }
 public void setTitle(String value) { title=value; }
 public String getExcerpt() { return excerpt; }
 public void setExcerpt(String value) { excerpt=value; }
 public String getCategory() { return category; }
 public void setCategory(String value) { category=value; }
 public String getStage() { return stage; }
 public void setStage(String value) { stage=value; }
 public String getCoverImageUrl() { return coverImageUrl; }
 public void setCoverImageUrl(String value) { coverImageUrl=value; }
 public String getCoverImageAlt() { return coverImageAlt; }
 public void setCoverImageAlt(String value) { coverImageAlt=value; }
 public String getCoverImageCaption() { return coverImageCaption; }
 public void setCoverImageCaption(String value) { coverImageCaption=value; }
 public String getSourceLabel() { return sourceLabel; }
 public void setSourceLabel(String value) { sourceLabel=value; }
 public String getSourceHref() { return sourceHref; }
 public void setSourceHref(String value) { sourceHref=value; }
 public String getLead() { return lead; }
 public void setLead(String value) { lead=value; }
 public ArticleMedia getCoverMedia() { return coverMedia; }
 public void setCoverMedia(ArticleMedia value) { coverMedia=value; }
 public UserEntity getAuthor() { return author; }
 public void setAuthor(UserEntity value) { author=value; }
 public ArticleStatus getStatus() { return status; }
 public void setStatus(ArticleStatus value) { status=value; }
 public OffsetDateTime getPublishedAt() { return publishedAt; }
 public void setPublishedAt(OffsetDateTime value) { publishedAt=value; }
 public long getVersion() { return version; }
 public Set<String> getTopics() { return topics; }
 public void setTopics(Set<String> value) { topics=value; }
 public List<ArticleSection> getSections() { return sections; }
 public void setSections(List<ArticleSection> value) { sections=value; }
 public OffsetDateTime getCreatedAt() { return createdAt; }
 public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
