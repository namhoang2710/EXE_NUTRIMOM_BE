package vn.nutrimom.knowledge.service;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.knowledge.domain.*;
import vn.nutrimom.knowledge.dto.ArticleDtos.*;

@Component
public class KnowledgeArticleMapper {
    private final ObjectMapper json;
    public KnowledgeArticleMapper(ObjectMapper json) { this.json = json; }
    public String encode(List<String> values) {
        return json.writeValueAsString(values == null ? List.of() : values);
    }
    private List<String> decode(String value) {
        return json.readValue(value, new TypeReference<List<String>>() {});
    }
    private Author author(KnowledgeArticle a) {
        return new Author(a.getAuthor().getId(), a.getAuthor().getDisplayName());
    }
    private Image cover(KnowledgeArticle a) {
        return a.getCoverImageUrl() == null ? null : new Image(a.getCoverImageUrl(), a.getCoverImageAlt(), a.getCoverImageCaption());
    }
    public Summary summary(KnowledgeArticle a) {
        return new Summary(a.getId(), a.getSlug(), a.getTitle(), a.getExcerpt(), a.getCategory(), a.getStage(),
                List.copyOf(a.getTopics()), a.getPublishedAt(), author(a), cover(a));
    }
    public Detail detail(KnowledgeArticle a) {
        return new Detail(a.getId(), a.getSlug(), a.getTitle(), a.getExcerpt(), a.getCategory(), a.getStage(),
                List.copyOf(a.getTopics()), a.getPublishedAt(), author(a), cover(a), a.getLead(),
                a.getSections().stream().map(s -> new Section(s.getId(), s.getHeading(), decode(s.getParagraphs()),
                        decode(s.getBullets()), s.getImageUrl() == null ? null
                        : new Image(s.getImageUrl(), s.getImageAlt(), s.getImageCaption()), s.getSortOrder())).toList(),
                a.getSourceHref() == null ? null : new Source(a.getSourceLabel(), a.getSourceHref()),
                a.getStatus(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
