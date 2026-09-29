ALTER TABLE app.knowledge_articles ADD COLUMN cover_image_caption VARCHAR(1000) NULL;

-- Previously saved articles can retain the caption from their uploaded media.
UPDATE app.knowledge_articles AS article
SET cover_image_caption = media.caption
FROM app.knowledge_article_media AS media
WHERE media.id = article.cover_media_id AND media.caption IS NOT NULL;

