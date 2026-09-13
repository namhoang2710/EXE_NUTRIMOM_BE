package vn.nutrimom.pregnancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.pregnancy.dto.PregnancyWeekContentResponse;
import vn.nutrimom.pregnancy.service.PregnancyWeekContentService;

@RestController
@Validated
@RequestMapping("/api/v1/pregnancy-content/weeks")
@Tag(name = "Pregnancy weekly content", description = "Curated content by gestational week")
@SecurityRequirement(name = "bearerAuth")
public class PregnancyWeekContentController {
    private final PregnancyWeekContentService service;

    public PregnancyWeekContentController(PregnancyWeekContentService service) {
        this.service = service;
    }

    @GetMapping("/{week}")
    @Operation(summary = "Get pregnancy content for a gestational week")
    public ApiResponse<PregnancyWeekContentResponse> getWeek(
            @PathVariable @Min(0) @Max(42) int week) {
        return ApiResponses.success(service.getWeek(week));
    }
}
