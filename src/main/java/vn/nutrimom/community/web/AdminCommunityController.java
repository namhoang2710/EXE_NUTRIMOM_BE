package vn.nutrimom.community.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.community.domain.PostStatus;
import vn.nutrimom.community.domain.ReportStatus;
import vn.nutrimom.community.dto.CommunityDtos.AdminCreatePostRequest;
import vn.nutrimom.community.dto.CommunityDtos.AdminPostItem;
import vn.nutrimom.community.dto.CommunityDtos.AdminReportItem;
import vn.nutrimom.community.dto.CommunityDtos.RejectRequest;
import vn.nutrimom.community.dto.CommunityDtos.RemoveRequest;
import vn.nutrimom.community.dto.CommunityDtos.ResolveReportRequest;
import vn.nutrimom.community.service.CommunityCommentService;
import vn.nutrimom.community.service.CommunityModerationService;
import vn.nutrimom.community.service.CommunityReportService;

/** Kiểm duyệt cộng đồng. Đã nằm dưới {@code /api/v1/admin/**} (ADMIN + ActiveAdminFilter). */
@RestController
@RequestMapping("/api/v1/admin/community")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class AdminCommunityController {
    private final CommunityModerationService moderation;
    private final CommunityReportService reports;
    private final CommunityCommentService comments;

    public AdminCommunityController(CommunityModerationService moderation, CommunityReportService reports,
            CommunityCommentService comments) {
        this.moderation = moderation;
        this.reports = reports;
        this.comments = comments;
    }

    @GetMapping("/posts")
    public ApiResponse<CursorPage<AdminPostItem>> posts(@RequestParam(required = false) PostStatus status,
            @RequestParam(defaultValue = "false") boolean pinned,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(moderation.list(jwt.getSubject(), status, pinned, cursor, limit));
    }

    /** Ban quản trị đăng bài: duyệt ngay, {@code pinned=true} thì ghim luôn. */
    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminPostItem> create(@Valid @RequestBody AdminCreatePostRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(moderation.createPost(jwt.getSubject(), request));
    }

    @GetMapping("/posts/pending-count")
    @io.swagger.v3.oas.annotations.Operation(summary = "Tổng số bài đang chờ duyệt, không phụ thuộc phân trang hoặc trạng thái đã xem")
    public ApiResponse<vn.nutrimom.community.dto.CommunityDtos.PendingPostCount> pendingCount() {
        return ApiResponses.success(moderation.pendingCount());
    }

    @GetMapping("/posts/{postId}")
    public ApiResponse<AdminPostItem> post(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(moderation.detail(jwt.getSubject(), postId));
    }

    @PostMapping("/posts/{postId}/approve")
    public ApiResponse<AdminPostItem> approve(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(moderation.approve(jwt.getSubject(), postId));
    }

    @PostMapping("/posts/{postId}/reject")
    public ApiResponse<AdminPostItem> reject(@PathVariable String postId, @Valid @RequestBody RejectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(moderation.reject(jwt.getSubject(), postId, request.reason()));
    }

    @PutMapping("/posts/{postId}/pin")
    public ApiResponse<AdminPostItem> pin(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(moderation.pin(jwt.getSubject(), postId, true));
    }

    @DeleteMapping("/posts/{postId}/pin")
    public ApiResponse<AdminPostItem> unpin(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(moderation.pin(jwt.getSubject(), postId, false));
    }

    @DeleteMapping("/posts/{postId}")
    public ApiResponse<AdminPostItem> remove(@PathVariable String postId,
            @Valid @RequestBody(required = false) RemoveRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(moderation.remove(jwt.getSubject(), postId, request == null ? null : request.reason()));
    }

    @DeleteMapping("/comments/{commentId}")
    public ApiResponse<Boolean> deleteComment(@PathVariable String commentId, @AuthenticationPrincipal Jwt jwt) {
        comments.deleteAsAdmin(jwt.getSubject(), commentId);
        return ApiResponses.success(true);
    }

    @GetMapping("/reports")
    public ApiResponse<CursorPage<AdminReportItem>> reports(@RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponses.success(reports.list(status, cursor, limit));
    }

    @PostMapping("/reports/{reportId}/resolve")
    public ApiResponse<AdminReportItem> resolve(@PathVariable String reportId,
            @Valid @RequestBody ResolveReportRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(reports.resolve(jwt.getSubject(), reportId, request.action(), request.note()));
    }
}
