CREATE TABLE app.knowledge_article_sections (
    id VARCHAR(36) NOT NULL CONSTRAINT pk_knowledge_sections PRIMARY KEY,
    article_id VARCHAR(36) NOT NULL,
    heading VARCHAR(300) NOT NULL,
    paragraphs TEXT NOT NULL,
    bullets TEXT NOT NULL,
    media_id VARCHAR(36) NULL,
    image_url VARCHAR(2048) NULL,
    image_alt VARCHAR(500) NULL,
    image_caption VARCHAR(1000) NULL,
    sort_order INT NOT NULL,
    CONSTRAINT fk_knowledge_sections_article FOREIGN KEY (article_id) REFERENCES app.knowledge_articles(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_sections_media FOREIGN KEY (media_id) REFERENCES app.knowledge_article_media(id),
    CONSTRAINT ck_knowledge_sections_order CHECK (sort_order >= 0)
);
CREATE INDEX ix_knowledge_sections_article_order ON app.knowledge_article_sections(article_id, sort_order);

