package vn.nutrimom.consultation.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.RequestDtos.AdminConsultationResponse;
import vn.nutrimom.consultation.service.AdminConsultationService;

@Validated
@RestController
@RequestMapping("/api/v1/admin/consultation-requests")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin consultations", description = "Admin xem tiếp nhận và đánh giá")
@SecurityRequirement(name = "bearerAuth")
public class AdminConsultationController {
    private final AdminConsultationService service;

    public AdminConsultationController(AdminConsultationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Danh sách tiếp nhận (mặc định đã hoàn thành) kèm đánh giá; tìm theo tên user/chuyên gia; phân trang")
    public ApiResponse<PageResponse<AdminConsultationResponse>> list(
            @RequestParam(required = false) ConsultationStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ApiResponses.success(service.list(status, q, page, pageSize));
    }
}
