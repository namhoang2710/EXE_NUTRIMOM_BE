package vn.nutrimom.care.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.care.dto.BirthPlanResponse;
import vn.nutrimom.care.dto.CarePlanResponse;
import vn.nutrimom.care.dto.PreparationItemResponse;
import vn.nutrimom.care.dto.PutBirthPlanRequest;
import vn.nutrimom.care.dto.UpdatePreparationItemRequest;
import vn.nutrimom.care.dto.VerifiedGuidanceResponse;
import vn.nutrimom.care.service.CarePlanService;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Prenatal care", description = "Care plan, verified guidance and birth preferences")
@SecurityRequirement(name = "bearerAuth")
public class CarePlanController {
    private final CarePlanService service;

    public CarePlanController(CarePlanService service) { this.service = service; }

    @GetMapping("/care-plans/current")
    @Operation(summary = "Get the authenticated user's current care plan")
    public ApiResponse<CarePlanResponse> currentCarePlan(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.getCurrentCarePlan(jwt.getSubject()));
    }

    @GetMapping("/verified-guidance")
    @Operation(summary = "List reviewed clinical guidance")
    public ApiResponse<CursorPage<VerifiedGuidanceResponse>> guidance(
            @RequestParam(required = false) Integer week,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponses.success(service.getGuidance(week, topic, locale, cursor, limit));
    }

    @GetMapping("/preparation-items")
    @Operation(summary = "Get current pregnancy preparation items")
    public ApiResponse<List<PreparationItemResponse>> preparationItems(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.getPreparationItems(jwt.getSubject()));
    }

    @PatchMapping("/preparation-items/{id}")
    @Operation(summary = "Update a preparation item with optimistic locking")
    public ApiResponse<PreparationItemResponse> updatePreparationItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @Valid @RequestBody UpdatePreparationItemRequest request) {
        return ApiResponses.success(service.updatePreparationItem(jwt.getSubject(), id, request));
    }

    @GetMapping("/birth-plans/current")
    @Operation(summary = "Get the current pregnancy birth plan")
    public ApiResponse<BirthPlanResponse> birthPlan(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.getCurrentBirthPlan(jwt.getSubject()));
    }

    @PutMapping("/birth-plans/current")
    @Operation(summary = "Replace the current pregnancy birth plan")
    public ApiResponse<BirthPlanResponse> putBirthPlan(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PutBirthPlanRequest request) {
        return ApiResponses.success(service.putCurrentBirthPlan(jwt.getSubject(), request));
    }
}
