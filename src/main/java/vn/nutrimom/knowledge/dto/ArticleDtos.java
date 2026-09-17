package vn.nutrimom.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import vn.nutrimom.knowledge.domain.ArticleStatus;

public final class ArticleDtos {
    private ArticleDtos() {}
    public record Author(String id, String name) {}
    public record Image(String url, String alt, String caption) {}
    public record Source(String label, String href) {}
    public record Section(String id, String heading, List<String> paragraphs, List<String> bullets,
                          Image image, @JsonProperty("sortOrder") int sortOrder) {}
    public record Summary(String id, String slug, String title, String excerpt, String category,
            String stage, List<String> topics, @JsonProperty("publishedAt") OffsetDateTime publishedAt,
            Author author, @JsonProperty("coverImage") Image coverImage) {}
    public record Detail(String id, String slug, String title, String excerpt, String category,
            String stage, List<String> topics, @JsonProperty("publishedAt") OffsetDateTime publishedAt,
            Author author, @JsonProperty("coverImage") Image coverImage, String lead,
            List<Section> sections, Source source, ArticleStatus status,
            @JsonProperty("youtubeVideoId") String youtubeVideoId,
            @JsonProperty("createdAt") OffsetDateTime createdAt,
            @JsonProperty("updatedAt") OffsetDateTime updatedAt) {}
    public record AdminListItem(String id, String slug, String title, String excerpt, String category,
            String stage, List<String> topics, @JsonProperty("publishedAt") OffsetDateTime publishedAt,
            Author author, @JsonProperty("coverImage") Image coverImage, String lead,
            List<Section> sections, Source source, ArticleStatus status,
            @JsonProperty("createdAt") OffsetDateTime createdAt,
            @JsonProperty("updatedAt") OffsetDateTime updatedAt) {}
    public record Pagination<T>(List<T> items, @JsonProperty("totalItems") long totalItems,
            @JsonProperty("totalPages") int totalPages, @JsonProperty("currentPage") int currentPage,
            @JsonProperty("pageSize") int pageSize) {}
    public record MediaUpload(String id, String imageUrl, String imageKey, String alt, String caption,
                              int width, int height, long sizeBytes) {}
}
