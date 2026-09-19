package vn.nutrimom.medicalrecord.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.medicalrecord.domain.MedicalRecordCategory;
import vn.nutrimom.medicalrecord.dto.CreateMedicalRecordRequest;
import vn.nutrimom.medicalrecord.dto.MedicalRecordResponse;
import vn.nutrimom.medicalrecord.dto.UpdateMedicalRecordRequest;
import vn.nutrimom.medicalrecord.service.MedicalRecordService;

@RestController
@RequestMapping("/api/v1/medical-records")
@Tag(name = "Medical records", description = "Private medical records and attachments")
@SecurityRequirement(name = "bearerAuth")
public class MedicalRecordController {
    private final MedicalRecordService service;

    public MedicalRecordController(MedicalRecordService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "List owned medical records")
    public ApiResponse<CursorPage<MedicalRecordResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "pregnancy_id", required = false) String pregnancyId,
            @RequestParam(required = false) MedicalRecordCategory category,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponses.success(service.list(jwt.getSubject(), pregnancyId, category, from, to, cursor, limit));
    }

    @PostMapping
    @Operation(summary = "Create a medical record")
    public ResponseEntity<ApiResponse<MedicalRecordResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateMedicalRecordRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(service.create(jwt.getSubject(), request)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an owned medical record")
    public ApiResponse<MedicalRecordResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ApiResponses.success(service.get(jwt.getSubject(), id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Partially update an owned medical record")
    public ApiResponse<MedicalRecordResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @Valid @RequestBody UpdateMedicalRecordRequest request) {
        return ApiResponses.success(service.update(jwt.getSubject(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft delete an owned medical record")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        service.delete(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }
}
