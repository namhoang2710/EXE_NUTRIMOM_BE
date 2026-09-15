ALTER TABLE app.knowledge_articles ADD cover_image_caption NVARCHAR(1000) NULL;
GO

-- Previously saved articles can retain the caption from their uploaded media.
UPDATE article
SET cover_image_caption = media.caption
FROM app.knowledge_articles article
JOIN app.knowledge_article_media media ON media.id = article.cover_media_id
WHERE media.caption IS NOT NULL;
