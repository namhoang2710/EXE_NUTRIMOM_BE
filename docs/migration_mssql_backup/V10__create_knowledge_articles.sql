CREATE TABLE app.knowledge_articles (
 id NVARCHAR(36) NOT NULL CONSTRAINT pk_knowledge_articles PRIMARY KEY,
 slug NVARCHAR(180) NOT NULL,
 title NVARCHAR(300) NOT NULL,
 excerpt NVARCHAR(2000) NULL,
 category NVARCHAR(100) NOT NULL,
 stage NVARCHAR(100) NOT NULL,
 published_at DATETIMEOFFSET(7) NULL,
 status NVARCHAR(20) NOT NULL,
 author_id NVARCHAR(36) NOT NULL,
 cover_media_id NVARCHAR(36) NULL,
 cover_image_url NVARCHAR(2048) NULL,
 cover_image_alt NVARCHAR(500) NULL,
 source_label NVARCHAR(300) NULL,
 source_href NVARCHAR(2048) NULL,
 lead NVARCHAR(MAX) NULL,
 version BIGINT NOT NULL CONSTRAINT df_knowledge_articles_version DEFAULT 0,
 created_at DATETIMEOFFSET(7) NOT NULL,
 updated_at DATETIMEOFFSET(7) NOT NULL,
 CONSTRAINT fk_knowledge_articles_author FOREIGN KEY (author_id) REFERENCES app.users(id),
 CONSTRAINT fk_knowledge_articles_cover FOREIGN KEY (cover_media_id) REFERENCES app.knowledge_article_media(id),
 CONSTRAINT ck_knowledge_articles_status CHECK (status IN ('draft','published','archived')),
 CONSTRAINT ck_knowledge_articles_publication CHECK (
  (status = 'published' AND published_at IS NOT NULL) OR (status <> 'published' AND published_at IS NULL))
);
CREATE UNIQUE INDEX ux_knowledge_articles_slug ON app.knowledge_articles(slug);
CREATE INDEX ix_knowledge_articles_status_published ON app.knowledge_articles(status, published_at DESC);
CREATE INDEX ix_knowledge_articles_category ON app.knowledge_articles(category, status, published_at DESC);
CREATE INDEX ix_knowledge_articles_stage ON app.knowledge_articles(stage, status, published_at DESC);
CREATE INDEX ix_knowledge_articles_author ON app.knowledge_articles(author_id);
CREATE INDEX ix_knowledge_articles_published ON app.knowledge_articles(published_at DESC);
CREATE TABLE app.knowledge_article_topics (
 article_id NVARCHAR(36) NOT NULL,
 topic NVARCHAR(100) COLLATE Latin1_General_100_BIN2 NOT NULL,
 CONSTRAINT pk_knowledge_article_topics PRIMARY KEY (article_id, topic),
 CONSTRAINT fk_knowledge_topics_article FOREIGN KEY (article_id) REFERENCES app.knowledge_articles(id) ON DELETE CASCADE
);
CREATE INDEX ix_knowledge_topics_topic ON app.knowledge_article_topics(topic, article_id);
