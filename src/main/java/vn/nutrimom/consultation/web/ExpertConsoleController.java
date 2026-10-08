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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
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
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.ExpertDtos.AdminExpertResponse;
import vn.nutrimom.consultation.dto.RequestDtos.AcceptConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.ConsultationRequestResponse;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;
import vn.nutrimom.consultation.dto.SlotDtos.DayScheduleResponse;
import vn.nutrimom.consultation.dto.SlotDtos.DaySummary;
import vn.nutrimom.consultation.dto.SlotDtos.ScheduleSlot;
import vn.nutrimom.consultation.dto.SlotDtos.ToggleDayOffRequest;
import vn.nutrimom.consultation.dto.SlotDtos.ToggleSlotRequest;
import vn.nutrimom.consultation.service.ConsultationRequestService;
import vn.nutrimom.consultation.service.ConsultationReviewService;
import vn.nutrimom.consultation.service.ExpertAdminService;
import vn.nutrimom.consultation.service.ExpertScheduleService;

@Validated
@RestController
@RequestMapping("/api/v1/expert")
@PreAuthorize("hasRole('EXPERT')")
@Tag(name = "Expert console", description = "Chuyên gia quản lý lịch trống, tiếp nhận và hoàn thành tư vấn")
@SecurityRequirement(name = "bearerAuth")
public class ExpertConsoleController {
    private final ExpertAdminService expertAdminService;
    private final ExpertScheduleService scheduleService;
    private final ConsultationRequestService requestService;
    private final ConsultationReviewService reviewService;

    public ExpertConsoleController(ExpertAdminService expertAdminService,
                                   ExpertScheduleService scheduleService,
                                   ConsultationRequestService requestService,
                                   ConsultationReviewService reviewService) {
        this.expertAdminService = expertAdminService;
        this.scheduleService = scheduleService;
        this.requestService = requestService;
        this.reviewService = reviewService;
    }

    @GetMapping("/me")
    @Operation(summary = "Hồ sơ chuyên gia của chính mình")
    public ApiResponse<AdminExpertResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(expertAdminService.detail(jwt.getSubject()));
    }

    @GetMapping("/schedule")
    @Operation(summary = "Lịch làm việc một ngày của mình. Luôn trả đủ khung giờ 08:00–20:00 "
            + "kèm state (OPEN/BOOKED/CLOSED); ô BOOKED có tên khách")
    public ApiResponse<DayScheduleResponse> schedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponses.success(scheduleService.scheduleForExpert(jwt.getSubject(), date));
    }

    @PutMapping("/schedule/slot")
    @Operation(summary = "Đóng hoặc mở lại một khung giờ (idempotent). Khung giờ đã có người "
            + "đặt thì không đổi được")
    public ApiResponse<ScheduleSlot> toggleSlot(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ToggleSlotRequest request) {
        return ApiResponses.success(scheduleService.setSlotClosed(
                jwt.getSubject(), request.slotDate(), request.startTime(), request.closed()));
    }

    @PutMapping("/schedule/day-off")
    @Operation(summary = "Bật/tắt nghỉ cả ngày (idempotent). Tắt lại sẽ trả về đúng các khung "
            + "giờ đã đóng tay trước đó; buổi đã hẹn không bị ảnh hưởng")
    public ApiResponse<DayScheduleResponse> toggleDayOff(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ToggleDayOffRequest request) {
        return ApiResponses.success(scheduleService.setDayOff(
                jwt.getSubject(), request.slotDate(), request.dayOff()));
    }

    @GetMapping("/schedule/summary")
    @Operation(summary = "Tổng hợp theo ngày cho dải ngày điều hướng (tối đa 31 ngày)")
    public ApiResponse<List<DaySummary>> scheduleSummary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponses.success(scheduleService.summary(jwt.getSubject(), from, to));
    }

    @GetMapping("/consultation-requests")
    @Operation(summary = "type=assigned: buổi tư vấn được giao (giờ hẹn mới nhất trước); type=pool: yêu cầu ngẫu nhiên chờ nhận. "
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
    @Operation(summary = "Tiếp nhận yêu cầu ngẫu nhiên và xếp vào một khung giờ còn trống của mình (slot_date + start_time)")
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
