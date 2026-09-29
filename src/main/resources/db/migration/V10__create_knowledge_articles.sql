CREATE TABLE app.knowledge_articles (
    id VARCHAR(36) NOT NULL CONSTRAINT pk_knowledge_articles PRIMARY KEY,
    slug VARCHAR(180) NOT NULL,
    title VARCHAR(300) NOT NULL,
    excerpt VARCHAR(2000) NULL,
    category VARCHAR(100) NOT NULL,
    stage VARCHAR(100) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NULL,
    status VARCHAR(20) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    cover_media_id VARCHAR(36) NULL,
    cover_image_url VARCHAR(2048) NULL,
    cover_image_alt VARCHAR(500) NULL,
    source_label VARCHAR(300) NULL,
    source_href VARCHAR(2048) NULL,
    lead TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
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
    article_id VARCHAR(36) NOT NULL,
    topic VARCHAR(100) NOT NULL,
    CONSTRAINT pk_knowledge_article_topics PRIMARY KEY (article_id, topic),
    CONSTRAINT fk_knowledge_topics_article FOREIGN KEY (article_id) REFERENCES app.knowledge_articles(id) ON DELETE CASCADE
);
CREATE INDEX ix_knowledge_topics_topic ON app.knowledge_article_topics(topic, article_id);

