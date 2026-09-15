package vn.nutrimom.knowledge.dto;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import vn.nutrimom.knowledge.domain.ArticleStatus;

public record ArticleRequest(
        @NotBlank @Size(max=180) @Pattern(regexp="[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @NotBlank @Size(max=300) String title,
        @Size(max=2000) String excerpt,
        @NotBlank @Size(max=100) String category,
        @NotBlank @Size(max=100) String stage,
        @Size(max=30) List<@NotBlank @Size(max=100) String> topics,
        @JsonProperty("publishedAt") @JsonAlias("published_at") OffsetDateTime publishedAt,
        @NotNull ArticleStatus status,
        @JsonProperty("authorId") @JsonAlias("author_id") @Size(max=36) String authorId,
        @JsonProperty("coverImage") @JsonAlias("cover_image") @Valid ImageInput coverImage,
        @Size(max=10000) String lead,
        @Size(max=100) List<@NotNull @Valid SectionInput> sections,
        @Valid SourceInput source) {
    public record ImageInput(@Size(max=36) String id, @Size(max=2048) String url,
                             @Size(max=500) String alt, @Size(max=1000) String caption) {}
    public record SectionInput(@NotBlank @Size(max=300) String heading,
            @NotNull @Size(max=100) List<@NotBlank @Size(max=10000) String> paragraphs,
            @Size(max=100) List<@NotBlank @Size(max=2000) String> bullets, @Valid ImageInput image) {}
    public record SourceInput(@NotBlank @Size(max=300) String label,
                              @NotBlank @Size(max=2048) String href) {}
}
