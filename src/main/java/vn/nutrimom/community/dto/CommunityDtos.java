package vn.nutrimom.community.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import vn.nutrimom.community.domain.CommunityNotificationType;
import vn.nutrimom.community.domain.PostStatus;
import vn.nutrimom.community.domain.PostTopic;
import vn.nutrimom.community.domain.ReportReason;
import vn.nutrimom.community.domain.ReportStatus;

/**
 * Hợp đồng JSON của cộng đồng (snake_case do cấu hình Jackson chung).
 *
 * <p>Request chỉ chứa trường người dùng được phép gửi: không có author_id, status, like_count...
 * nên không thể mass-assign các trường đó.</p>
 */
public final class CommunityDtos {
    private CommunityDtos() {
    }

    public record Author(String id, String displayName, String avatarKey) { }

    public record Image(String id, String url, int width, int height) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PostItem(String id, Author author, String content, PostTopic topic, List<Image> images,
                           int likeCount, int commentCount, boolean likedByMe, boolean savedByMe, boolean pinned,
                           PostStatus status, String rejectReason, boolean canEdit, boolean canDelete,
                           long version, OffsetDateTime publishedAt, OffsetDateTime createdAt,
                           OffsetDateTime editedAt, boolean official) { }

    /** Trang feed. {@code pinned} chỉ có ở trang đầu (không gửi cursor). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FeedPage(List<PostItem> items, List<PostItem> pinned, String nextCursor, boolean hasMore) { }

    public record CreatePostRequest(
            @NotNull(message = "content là bắt buộc") @Size(max = 6000, message = "content quá dài") String content,
            @NotNull(message = "topic là bắt buộc") PostTopic topic,
            @Size(max = 4, message = "Tối đa 4 ảnh mỗi bài") List<@NotBlank String> imageIds) { }

    public record UpdatePostRequest(
            @NotNull(message = "content là bắt buộc") @Size(max = 6000, message = "content quá dài") String content,
            @NotNull(message = "topic là bắt buộc") PostTopic topic,
            @Size(max = 4, message = "Tối đa 4 ảnh mỗi bài") List<@NotBlank String> imageIds,
            @NotNull(message = "version là bắt buộc") Long version) { }

    public record MediaUpload(String id, String url, int width, int height) { }

    public record LikeResult(boolean liked, int likeCount) { }

    public record SaveResult(boolean saved) { }

    public record CommentItem(String id, String postId, Author author, String content, boolean canDelete,
                              OffsetDateTime createdAt) { }

    public record CreateCommentRequest(
            @NotNull(message = "content là bắt buộc") @Size(max = 1500, message = "content quá dài") String content) { }

    public record ReportRequest(
            @NotNull(message = "reason là bắt buộc") ReportReason reason,
            @Size(max = 500, message = "note tối đa 500 ký tự") String note) { }

    /** {@code created=false}: người dùng đã báo cáo bài này trước đó; không tạo bản ghi mới. */
    public record ReportResult(boolean created) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NotificationItem(String id, CommunityNotificationType type, String postId, String commentId,
                                   Author actor, String message, boolean read, OffsetDateTime createdAt) { }

    public record UnreadCount(long unreadCount) { }

    public record MarkAllReadResult(int updated) { }

    // --- Admin ---

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminPostItem(PostItem post, long openReportCount, String moderatedBy,
                                OffsetDateTime moderatedAt, OffsetDateTime deletedAt) { }

    /** Admin đăng bài: duyệt ngay, ghim tuỳ chọn. Không có trường tác giả/trạng thái — lấy từ JWT. */
    public record AdminCreatePostRequest(
            @NotNull(message = "content là bắt buộc") @Size(max = 6000, message = "content quá dài") String content,
            @NotNull(message = "topic là bắt buộc") PostTopic topic,
            @Size(max = 4, message = "Tối đa 4 ảnh mỗi bài") List<@NotBlank String> imageIds,
            Boolean pinned) { }

    public record RejectRequest(
            @NotBlank(message = "reason là bắt buộc") @Size(max = 500, message = "reason tối đa 500 ký tự") String reason) { }

    public record RemoveRequest(@Size(max = 500, message = "reason tối đa 500 ký tự") String reason) { }

    public enum ResolveAction { DISMISS, REMOVE_POST }

    public record ResolveReportRequest(
            @NotNull(message = "action là bắt buộc") ResolveAction action,
            @Size(max = 500, message = "note tối đa 500 ký tự") String note) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminReportItem(String id, String postId, String postExcerpt, PostStatus postStatus,
                                  boolean postDeleted, Author postAuthor, Author reporter, ReportReason reason,
                                  String note, ReportStatus status, long openReportCount, String handledBy,
                                  OffsetDateTime handledAt, String resolutionNote, OffsetDateTime createdAt) { }
}
