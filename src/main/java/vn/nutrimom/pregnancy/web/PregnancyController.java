package vn.nutrimom.pregnancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.pregnancy.dto.CalculatePregnancyRequest;
import vn.nutrimom.pregnancy.dto.CreatePregnancyRequest;
import vn.nutrimom.pregnancy.dto.PregnancyCalculationResponse;
import vn.nutrimom.pregnancy.dto.PregnancyResponse;
import vn.nutrimom.pregnancy.dto.UpdatePregnancyRequest;
import vn.nutrimom.pregnancy.service.PregnancyService;

@RestController
@RequestMapping("/api/v1/pregnancies")
@Tag(name = "Pregnancy profile", description = "Pregnancy context for the authenticated user")
@SecurityRequirement(name = "bearerAuth")
public class PregnancyController {
    private final PregnancyService service;

    public PregnancyController(PregnancyService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create the authenticated user's active pregnancy")
    public ResponseEntity<ApiResponse<PregnancyResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePregnancyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(service.create(jwt.getSubject(), request)));
    }

    @PostMapping("/calculate")
    @Operation(summary = "Preview pregnancy dates without persisting a pregnancy")
    public ApiResponse<PregnancyCalculationResponse> calculate(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CalculatePregnancyRequest request) {
        return ApiResponses.success(service.calculate(request));
    }

    @GetMapping("/current")
    @Operation(summary = "Get the authenticated user's current active pregnancy")
    public ApiResponse<PregnancyResponse> current(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.getCurrent(jwt.getSubject()));
    }

    @GetMapping("/{pregnancyId}")
    @Operation(summary = "Get an owned pregnancy by ID")
    public ApiResponse<PregnancyResponse> getById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String pregnancyId) {
        return ApiResponses.success(service.getById(jwt.getSubject(), pregnancyId));
    }

    @PatchMapping("/{pregnancyId}")
    @Operation(summary = "Partially update an owned pregnancy using optimistic locking")
    public ApiResponse<PregnancyResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String pregnancyId,
            @Valid @RequestBody UpdatePregnancyRequest request) {
        return ApiResponses.success(service.update(jwt.getSubject(), pregnancyId, request));
    }
}
