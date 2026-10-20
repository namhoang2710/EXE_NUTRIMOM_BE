package vn.nutrimom.community.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.community.domain.PostStatus;
import vn.nutrimom.community.domain.PostTopic;
import vn.nutrimom.community.dto.CommunityDtos.CommentItem;
import vn.nutrimom.community.dto.CommunityDtos.CreateCommentRequest;
import vn.nutrimom.community.dto.CommunityDtos.CreatePostRequest;
import vn.nutrimom.community.dto.CommunityDtos.FeedPage;
import vn.nutrimom.community.dto.CommunityDtos.LikeResult;
import vn.nutrimom.community.dto.CommunityDtos.MediaUpload;
import vn.nutrimom.community.dto.CommunityDtos.PostItem;
import vn.nutrimom.community.dto.CommunityDtos.ReportRequest;
import vn.nutrimom.community.dto.CommunityDtos.ReportResult;
import vn.nutrimom.community.dto.CommunityDtos.SaveResult;
import vn.nutrimom.community.dto.CommunityDtos.UpdatePostRequest;
import vn.nutrimom.community.service.CommunityCommentService;
import vn.nutrimom.community.service.CommunityInteractionService;
import vn.nutrimom.community.service.CommunityMediaService;
import vn.nutrimom.community.service.CommunityPostService;
import vn.nutrimom.community.service.CommunityReportService;

/**
 * API cộng đồng cho người dùng đã đăng nhập ({@code anyRequest().authenticated()}). Quyền trên từng
 * bài/bình luận kiểm ở service; userId luôn lấy từ JWT, không bao giờ từ body.
 */
@RestController
@RequestMapping("/api/v1/community")
@SecurityRequirement(name = "bearerAuth")
public class CommunityController {
    private final CommunityPostService posts;
    private final CommunityInteractionService interactions;
    private final CommunityCommentService comments;
    private final CommunityReportService reports;
    private final CommunityMediaService media;

    public CommunityController(CommunityPostService posts, CommunityInteractionService interactions,
            CommunityCommentService comments, CommunityReportService reports, CommunityMediaService media) {
        this.posts = posts;
        this.interactions = interactions;
        this.comments = comments;
        this.reports = reports;
        this.media = media;
    }

    @GetMapping("/posts")
    public ApiResponse<FeedPage> feed(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int limit, @RequestParam(required = false) PostTopic topic,
            @RequestParam(defaultValue = "false") boolean hasImages, @RequestParam(defaultValue = "false") boolean saved,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(posts.feed(jwt.getSubject(), cursor, limit, topic, hasImages, saved));
    }

    @GetMapping("/posts/{postId}")
    public ApiResponse<PostItem> detail(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(posts.detail(jwt.getSubject(), postId));
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PostItem> create(@Valid @RequestBody CreatePostRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(posts.create(jwt.getSubject(), request));
    }

    @PatchMapping("/posts/{postId}")
    public ApiResponse<PostItem> update(@PathVariable String postId, @Valid @RequestBody UpdatePostRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(posts.update(jwt.getSubject(), postId, request));
    }

    @DeleteMapping("/posts/{postId}")
    public ApiResponse<Boolean> delete(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        posts.delete(jwt.getSubject(), postId);
        return ApiResponses.success(true);
    }

    @GetMapping("/me/posts")
    public ApiResponse<CursorPage<PostItem>> mine(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int limit, @RequestParam(required = false) PostStatus status,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(posts.mine(jwt.getSubject(), cursor, limit, status));
    }

    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MediaUpload> upload(@RequestPart("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(media.upload(file, jwt.getSubject()));
    }

    @PutMapping("/posts/{postId}/like")
    public ApiResponse<LikeResult> like(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(interactions.like(jwt.getSubject(), postId));
    }

    @DeleteMapping("/posts/{postId}/like")
    public ApiResponse<LikeResult> unlike(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(interactions.unlike(jwt.getSubject(), postId));
    }

    @PutMapping("/posts/{postId}/save")
    public ApiResponse<SaveResult> save(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(interactions.save(jwt.getSubject(), postId));
    }

    @DeleteMapping("/posts/{postId}/save")
    public ApiResponse<SaveResult> unsave(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(interactions.unsave(jwt.getSubject(), postId));
    }

    @GetMapping("/posts/{postId}/comments")
    public ApiResponse<CursorPage<CommentItem>> comments(@PathVariable String postId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(comments.list(jwt.getSubject(), postId, cursor, limit));
    }

    @PostMapping("/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentItem> comment(@PathVariable String postId, @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(comments.create(jwt.getSubject(), postId, request.content()));
    }

    @DeleteMapping("/comments/{commentId}")
    public ApiResponse<Boolean> deleteComment(@PathVariable String commentId, @AuthenticationPrincipal Jwt jwt) {
        comments.deleteOwn(jwt.getSubject(), commentId);
        return ApiResponses.success(true);
    }

    /** 201 khi báo cáo mới, 200 khi đã báo cáo bài này trước đó (không tạo bản ghi trùng). */
    @PostMapping("/posts/{postId}/reports")
    public ResponseEntity<ApiResponse<ReportResult>> report(@PathVariable String postId,
            @Valid @RequestBody ReportRequest request, @AuthenticationPrincipal Jwt jwt) {
        ReportResult result = reports.report(jwt.getSubject(), postId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponses.success(result));
    }
}
