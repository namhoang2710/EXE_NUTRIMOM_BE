CREATE TABLE app.knowledge_article_media (
    id VARCHAR(36) NOT NULL CONSTRAINT pk_knowledge_media PRIMARY KEY,
    image_key VARCHAR(300) NOT NULL,
    image_url VARCHAR(2048) NOT NULL,
    alt VARCHAR(500) NULL,
    caption VARCHAR(1000) NULL,
    content_type VARCHAR(50) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_knowledge_media_uploader FOREIGN KEY (uploaded_by) REFERENCES app.users(id),
    CONSTRAINT ck_knowledge_media_dimensions CHECK (width BETWEEN 1 AND 2400 AND height BETWEEN 1 AND 2400),
    CONSTRAINT ck_knowledge_media_size CHECK (size_bytes BETWEEN 1 AND 2097152)
);
CREATE UNIQUE INDEX ux_knowledge_media_key ON app.knowledge_article_media(image_key);
CREATE INDEX ix_knowledge_media_uploader ON app.knowledge_article_media(uploaded_by);

