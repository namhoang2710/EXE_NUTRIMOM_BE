CREATE TABLE app.knowledge_article_sections (
 id NVARCHAR(36) NOT NULL CONSTRAINT pk_knowledge_sections PRIMARY KEY,
 article_id NVARCHAR(36) NOT NULL,
 heading NVARCHAR(300) NOT NULL,
 paragraphs NVARCHAR(MAX) NOT NULL,
 bullets NVARCHAR(MAX) NOT NULL,
 media_id NVARCHAR(36) NULL,
 image_url NVARCHAR(2048) NULL,
 image_alt NVARCHAR(500) NULL,
 image_caption NVARCHAR(1000) NULL,
 sort_order INT NOT NULL,
 CONSTRAINT fk_knowledge_sections_article FOREIGN KEY (article_id) REFERENCES app.knowledge_articles(id) ON DELETE CASCADE,
 CONSTRAINT fk_knowledge_sections_media FOREIGN KEY (media_id) REFERENCES app.knowledge_article_media(id),
 CONSTRAINT ck_knowledge_sections_order CHECK (sort_order >= 0),
 CONSTRAINT ck_knowledge_sections_paragraphs CHECK (ISJSON(paragraphs) = 1),
 CONSTRAINT ck_knowledge_sections_bullets CHECK (ISJSON(bullets) = 1)
);
CREATE INDEX ix_knowledge_sections_article_order ON app.knowledge_article_sections(article_id, sort_order);
