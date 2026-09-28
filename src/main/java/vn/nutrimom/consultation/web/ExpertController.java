package vn.nutrimom.consultation.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.dto.ExpertDtos.ExpertDetailResponse;
import vn.nutrimom.consultation.dto.ExpertDtos.ExpertSummaryResponse;
import vn.nutrimom.consultation.dto.SlotDtos.DayAvailabilityResponse;
import vn.nutrimom.consultation.service.ExpertDirectoryService;
import vn.nutrimom.consultation.service.ExpertScheduleService;

@RestController
@RequestMapping("/api/v1/experts")
@Tag(name = "Experts", description = "Tìm và xem chuyên gia tư vấn")
@SecurityRequirement(name = "bearerAuth")
public class ExpertController {
    private final ExpertDirectoryService service;
    private final ExpertScheduleService scheduleService;

    public ExpertController(ExpertDirectoryService service, ExpertScheduleService scheduleService) {
        this.service = service;
        this.scheduleService = scheduleService;
    }

    @GetMapping
    @Operation(summary = "Danh sách chuyên gia đang hoạt động, lọc theo chuyên khoa")
    public ApiResponse<List<ExpertSummaryResponse>> list(
            @RequestParam(required = false) Specialty specialty) {
        return ApiResponses.success(service.list(specialty));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Chi tiết một chuyên gia")
    public ApiResponse<ExpertDetailResponse> detail(@PathVariable String userId) {
        return ApiResponses.success(service.detail(userId));
    }

    @GetMapping("/{userId}/availability")
    @Operation(summary = "Lịch của chuyên gia trong một ngày. Luôn trả đủ các khung giờ "
            + "08:00–20:00; ô không đặt được kèm reason (PAST/DAY_OFF/BOOKED/CLOSED)")
    public ApiResponse<DayAvailabilityResponse> availability(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponses.success(scheduleService.availabilityForUser(userId, date));
    }
}
