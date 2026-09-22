package vn.nutrimom.consultation.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.SlotStatus;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.ExpertDtos.AdminExpertResponse;
import vn.nutrimom.consultation.dto.RequestDtos.AcceptConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.ConsultationRequestResponse;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;
import vn.nutrimom.consultation.dto.SlotDtos.CreateSlotRequest;
import vn.nutrimom.consultation.dto.SlotDtos.SlotResponse;
import vn.nutrimom.consultation.service.AvailabilityService;
import vn.nutrimom.consultation.service.ConsultationRequestService;
import vn.nutrimom.consultation.service.ConsultationReviewService;
import vn.nutrimom.consultation.service.ExpertAdminService;

@Validated
@RestController
@RequestMapping("/api/v1/expert")
@PreAuthorize("hasRole('EXPERT')")
@Tag(name = "Expert console", description = "Chuyên gia quản lý lịch trống, tiếp nhận và hoàn thành tư vấn")
@SecurityRequirement(name = "bearerAuth")
public class ExpertConsoleController {
    private final ExpertAdminService expertAdminService;
    private final AvailabilityService availabilityService;
    private final ConsultationRequestService requestService;
    private final ConsultationReviewService reviewService;

    public ExpertConsoleController(ExpertAdminService expertAdminService,
                                   AvailabilityService availabilityService,
                                   ConsultationRequestService requestService,
                                   ConsultationReviewService reviewService) {
        this.expertAdminService = expertAdminService;
        this.availabilityService = availabilityService;
        this.requestService = requestService;
        this.reviewService = reviewService;
    }

    @GetMapping("/me")
    @Operation(summary = "Hồ sơ chuyên gia của chính mình")
    public ApiResponse<AdminExpertResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(expertAdminService.detail(jwt.getSubject()));
    }

    @GetMapping("/slots")
    @Operation(summary = "Danh sách khung giờ của mình; lọc theo ngày (date), khoảng ngày (from/to) và trạng thái")
    public ApiResponse<List<SlotResponse>> slots(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) SlotStatus status) {
        LocalDate fromDate = date != null ? date : from;
        LocalDate toDate = date != null ? date : to;
        return ApiResponses.success(
                availabilityService.listOwn(jwt.getSubject(), fromDate, toDate, status));
    }

    @PostMapping("/slots")
    @Operation(summary = "Mở một khung giờ trống")
    public ResponseEntity<ApiResponse<SlotResponse>> createSlot(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateSlotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(availabilityService.create(jwt.getSubject(), request)));
    }

    @DeleteMapping("/slots/{slotId}")
    @Operation(summary = "Xóa một khung giờ trống (chưa có người đặt)")
    public ResponseEntity<Void> deleteSlot(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String slotId) {
        availabilityService.delete(jwt.getSubject(), slotId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/consultation-requests")
    @Operation(summary = "type=assigned: buổi tư vấn được giao (sắp theo giờ hẹn); type=pool: yêu cầu ngẫu nhiên chờ nhận. "
            + "Với assigned: lọc status (mặc định PENDING_CONSULTATION, dùng COMPLETED/CANCELLED để xem lịch sử), khoảng ngày, tên user.")
    public ApiResponse<PageResponse<ConsultationRequestResponse>> requests(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "assigned") String type,
            @RequestParam(required = false) ConsultationStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        PageResponse<ConsultationRequestResponse> data = "pool".equalsIgnoreCase(type)
                ? requestService.listPool(jwt.getSubject(), q, page, pageSize)
                : requestService.listAssigned(jwt.getSubject(), status, from, to, q, page, pageSize);
        return ApiResponses.success(data);
    }

    @PostMapping("/consultation-requests/{id}/accept")
    @Operation(summary = "Tiếp nhận yêu cầu ngẫu nhiên và xếp vào một khung giờ trống của mình")
    public ApiResponse<ConsultationRequestResponse> accept(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody AcceptConsultationRequest request) {
        return ApiResponses.success(requestService.accept(jwt.getSubject(), id, request));
    }

    @PostMapping("/consultation-requests/{id}/complete")
    @Operation(summary = "Đánh dấu buổi tư vấn đã hoàn thành")
    public ApiResponse<ConsultationRequestResponse> complete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ApiResponses.success(requestService.complete(jwt.getSubject(), id));
    }

    @GetMapping("/reviews")
    @Operation(summary = "Đánh giá dành cho mình (đầy đủ sao + nhận xét); lọc số sao/khoảng ngày/chỉ có nhận xét; "
            + "sort=newest|rating_desc|rating_asc; phân trang")
    public ApiResponse<PageResponse<ReviewResponse>> reviews(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Min(1) @Max(5) Integer rating,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(name = "has_comment", required = false) Boolean hasComment,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ApiResponses.success(reviewService.listForExpert(
                jwt.getSubject(), rating, from, to, sort, hasComment, page, pageSize));
    }
}
