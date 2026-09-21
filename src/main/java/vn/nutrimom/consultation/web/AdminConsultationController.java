package vn.nutrimom.consultation.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.dto.RequestDtos.AdminConsultationResponse;
import vn.nutrimom.consultation.service.AdminConsultationService;

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
    @Operation(summary = "Danh sách tiếp nhận (mặc định đã hoàn thành) kèm đánh giá đầy đủ")
    public ApiResponse<List<AdminConsultationResponse>> list(
            @RequestParam(required = false) ConsultationStatus status) {
        return ApiResponses.success(service.list(status));
    }
}
