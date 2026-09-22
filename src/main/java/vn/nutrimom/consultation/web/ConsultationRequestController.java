package vn.nutrimom.consultation.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.RequestDtos.ConsultationRequestResponse;
import vn.nutrimom.consultation.dto.RequestDtos.CreateConsultationRequest;
import vn.nutrimom.consultation.dto.ReviewDtos.CreateReviewRequest;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;
import vn.nutrimom.consultation.service.ConsultationRequestService;
import vn.nutrimom.consultation.service.ConsultationReviewService;

@Validated
@RestController
@RequestMapping("/api/v1/consultation-requests")
@Tag(name = "Consultation requests", description = "User đặt lịch, theo dõi và đánh giá buổi tư vấn")
@SecurityRequirement(name = "bearerAuth")
public class ConsultationRequestController {
    private final ConsultationRequestService requestService;
    private final ConsultationReviewService reviewService;

    public ConsultationRequestController(ConsultationRequestService requestService,
                                         ConsultationReviewService reviewService) {
        this.requestService = requestService;
        this.reviewService = reviewService;
    }

    @PostMapping
    @Operation(summary = "Tạo yêu cầu tư vấn (DIRECT: chọn chuyên gia + slot; RANDOM: chọn chuyên khoa)")
    public ResponseEntity<ApiResponse<ConsultationRequestResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateConsultationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(requestService.create(jwt.getSubject(), request)));
    }

    @GetMapping
    @Operation(summary = "Danh sách yêu cầu tư vấn của chính mình (phân trang)")
    public ApiResponse<PageResponse<ConsultationRequestResponse>> listOwn(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ApiResponses.success(requestService.listOwn(jwt.getSubject(), page, pageSize));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết một yêu cầu tư vấn của mình")
    public ApiResponse<ConsultationRequestResponse> detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ApiResponses.success(requestService.detail(jwt.getSubject(), id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Hủy yêu cầu (khi chưa hoàn thành)")
    public ApiResponse<ConsultationRequestResponse> cancel(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ApiResponses.success(requestService.cancel(jwt.getSubject(), id));
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "Đánh giá chuyên gia sau khi buổi tư vấn hoàn thành")
    public ResponseEntity<ApiResponse<ReviewResponse>> review(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody CreateReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(reviewService.create(jwt.getSubject(), id, request)));
    }
}
