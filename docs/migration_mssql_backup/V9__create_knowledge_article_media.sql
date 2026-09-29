CREATE TABLE app.knowledge_article_media (
 id NVARCHAR(36) NOT NULL CONSTRAINT pk_knowledge_media PRIMARY KEY,
 image_key NVARCHAR(300) NOT NULL,
 image_url NVARCHAR(2048) NOT NULL,
 alt NVARCHAR(500) NULL,
 caption NVARCHAR(1000) NULL,
 content_type NVARCHAR(50) NOT NULL,
 width INT NOT NULL,
 height INT NOT NULL,
 size_bytes BIGINT NOT NULL,
 uploaded_by NVARCHAR(36) NOT NULL,
 created_at DATETIMEOFFSET(7) NOT NULL,
 updated_at DATETIMEOFFSET(7) NOT NULL,
 CONSTRAINT fk_knowledge_media_uploader FOREIGN KEY (uploaded_by) REFERENCES app.users(id),
 CONSTRAINT ck_knowledge_media_dimensions CHECK (width BETWEEN 1 AND 2400 AND height BETWEEN 1 AND 2400),
 CONSTRAINT ck_knowledge_media_size CHECK (size_bytes BETWEEN 1 AND 2097152)
);
CREATE UNIQUE INDEX ux_knowledge_media_key ON app.knowledge_article_media(image_key);
CREATE INDEX ix_knowledge_media_uploader ON app.knowledge_article_media(uploaded_by);
