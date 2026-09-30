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
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.RequestDtos.AdminConsultationResponse;
import vn.nutrimom.consultation.service.AdminConsultationService;

@Validated
@RestController
@RequestMapping("/api/v1/admin/consultation-requests")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin consultations",
        description = "Read-only access to completed consultations and reviews")
@SecurityRequirement(name = "bearerAuth")
public class AdminConsultationController {
    private final AdminConsultationService service;

    public AdminConsultationController(AdminConsultationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
            summary = "List completed consultations",
            description = "Returns only consultations completed by an expert, with optional "
                    + "case-insensitive user/expert name search and one-based pagination. Admin only.")
    public ApiResponse<PageResponse<AdminConsultationResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ApiResponses.success(service.list(q, page, pageSize));
    }
}
