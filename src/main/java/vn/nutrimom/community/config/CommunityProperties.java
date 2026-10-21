package vn.nutrimom.community.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Giới hạn nghiệp vụ của cộng đồng (application.yml {@code app.community.*}). */
@ConfigurationProperties("app.community")
public record CommunityProperties(
        @DefaultValue("4") int maxImagesPerPost,
        @DefaultValue("3") int maxPinnedPosts,
        @DefaultValue("10") int dailyPostLimit,
        @DefaultValue("100") int dailyCommentLimit,
        @DefaultValue("40") int dailyImageLimit,
        @DefaultValue("PT24H") Duration pendingImageTtl,
        @DefaultValue("P7D") Duration removedImageRetention,
        @DefaultValue("true") boolean mediaCleanupEnabled,
        @DefaultValue("50") int mediaCleanupBatchSize) {
}
