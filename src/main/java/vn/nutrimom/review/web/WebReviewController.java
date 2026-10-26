package vn.nutrimom.review.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.review.dto.ReviewDtos.MyReview;
import vn.nutrimom.review.dto.ReviewDtos.PublicReview;
import vn.nutrimom.review.dto.ReviewDtos.ReviewSummary;
import vn.nutrimom.review.dto.ReviewDtos.UpsertReviewRequest;
import vn.nutrimom.review.service.WebReviewService;

/**
 * Đánh giá web. Hai GET danh sách và tổng hợp là công khai (không cần đăng nhập, khai trong
 * {@code SecurityConfig}); mọi thứ dưới {@code /me} cần đăng nhập.
 */
@RestController
@RequestMapping("/api/v1/reviews")
@Tag(name = "Web reviews", description = "Đánh giá NutriMom của người dùng")
public class WebReviewController {
    private final WebReviewService service;

    public WebReviewController(WebReviewService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Danh sách đánh giá công khai, mới trước; tên người viết đã được che")
    public ApiResponse<CursorPage<PublicReview>> list(@RequestParam(required = false) Integer rating,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false) Integer limit) {
        return ApiResponses.success(service.publicPage(rating, cursor, limit));
    }

    @GetMapping("/summary")
    @Operation(summary = "Điểm trung bình và số đánh giá theo từng mức sao")
    public ApiResponse<ReviewSummary> summary() {
        return ApiResponses.success(service.summary());
    }

    @GetMapping("/me")
    @Operation(summary = "Đánh giá của tôi (kể cả đang bị ẩn)")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<MyReview> mine(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.mine(jwt.getSubject()));
    }

    @PutMapping("/me")
    @Operation(summary = "Viết hoặc sửa đánh giá của tôi (mỗi người một đánh giá); 201 khi tạo mới")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<MyReview>> upsert(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody UpsertReviewRequest request) {
        WebReviewService.Upserted result = service.upsert(jwt.getSubject(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponses.success(result.review()));
    }

    @DeleteMapping("/me")
    @Operation(summary = "Xoá đánh giá của tôi; sau đó có thể viết lại")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        service.delete(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
