CREATE TABLE app.knowledge_article_bookmarks (
 id NVARCHAR(36) NOT NULL CONSTRAINT pk_knowledge_bookmarks PRIMARY KEY,
 article_id NVARCHAR(36) NOT NULL,
 user_id NVARCHAR(36) NOT NULL,
 created_at DATETIMEOFFSET(7) NOT NULL,
 updated_at DATETIMEOFFSET(7) NOT NULL,
 CONSTRAINT fk_knowledge_bookmarks_article FOREIGN KEY (article_id) REFERENCES app.knowledge_articles(id) ON DELETE CASCADE,
 CONSTRAINT fk_knowledge_bookmarks_user FOREIGN KEY (user_id) REFERENCES app.users(id)
);
CREATE UNIQUE INDEX ux_knowledge_bookmarks_article_user ON app.knowledge_article_bookmarks(article_id, user_id);
CREATE INDEX ix_knowledge_bookmarks_user ON app.knowledge_article_bookmarks(user_id, article_id);
