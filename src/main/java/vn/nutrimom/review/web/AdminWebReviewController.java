package vn.nutrimom.review.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.review.domain.WebReviewStatus;
import vn.nutrimom.review.dto.ReviewDtos.AdminReview;
import vn.nutrimom.review.dto.ReviewDtos.HideRequest;
import vn.nutrimom.review.dto.ReviewDtos.PendingCount;
import vn.nutrimom.review.dto.ReviewDtos.ReplyRequest;
import vn.nutrimom.review.dto.ReviewDtos.VersionRequest;
import vn.nutrimom.review.service.AdminWebReviewService;

@RestController
@RequestMapping("/api/v1/admin/reviews")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin web reviews", description = "Admin xem, trả lời, ẩn đánh giá web")
public class AdminWebReviewController {
    private final AdminWebReviewService service;

    public AdminWebReviewController(AdminWebReviewService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Danh sách đánh giá mọi trạng thái; lọc theo trạng thái, số sao, đã/chưa trả lời, từ khoá")
    public ApiResponse<CursorPage<AdminReview>> list(@RequestParam(required = false) WebReviewStatus status,
                                                     @RequestParam(required = false) Integer rating,
                                                     @RequestParam(name = "has_reply", required = false) Boolean hasReply,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(required = false) Integer limit) {
        return ApiResponses.success(service.page(status, rating, hasReply, q, cursor, limit));
    }

    @GetMapping("/pending-count")
    @Operation(summary = "Số đánh giá đang hiện công khai mà chưa được trả lời")
    public ApiResponse<PendingCount> pendingCount() {
        return ApiResponses.success(service.pendingCount());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết một đánh giá")
    public ApiResponse<AdminReview> get(@PathVariable String id) {
        return ApiResponses.success(service.get(id));
    }

    @PutMapping("/{id}/reply")
    @Operation(summary = "Tạo hoặc sửa câu trả lời của NutriMom; lần đầu sẽ báo cho người viết")
    public ApiResponse<AdminReview> reply(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                          @Valid @RequestBody ReplyRequest request) {
        return ApiResponses.success(service.reply(jwt.getSubject(), id, request));
    }

    @DeleteMapping("/{id}/reply")
    @Operation(summary = "Gỡ câu trả lời")
    public ApiResponse<AdminReview> deleteReply(@PathVariable String id, @RequestParam long version) {
        return ApiResponses.success(service.deleteReply(id, version));
    }

    @PostMapping("/{id}/hide")
    @Operation(summary = "Ẩn đánh giá khỏi trang công khai, kèm lý do; báo cho người viết")
    public ApiResponse<AdminReview> hide(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                         @Valid @RequestBody HideRequest request) {
        return ApiResponses.success(service.hide(jwt.getSubject(), id, request));
    }

    @PostMapping("/{id}/unhide")
    @Operation(summary = "Hiện lại đánh giá đã ẩn")
    public ApiResponse<AdminReview> unhide(@PathVariable String id, @Valid @RequestBody VersionRequest request) {
        return ApiResponses.success(service.unhide(id, request.version()));
    }
}
