package vn.nutrimom.family.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
import vn.nutrimom.family.domain.FamilyTaskStatus;
import vn.nutrimom.family.dto.CreateFamilyTaskRequest;
import vn.nutrimom.family.dto.FamilyTaskResponse;
import vn.nutrimom.family.dto.UpdateFamilyTaskRequest;
import vn.nutrimom.family.service.FamilyTaskService;

@RestController
@RequestMapping("/api/v1/family/tasks")
@Tag(name = "Family sharing", description = "Pregnancy family groups and scoped sharing")
@SecurityRequirement(name = "bearerAuth")
public class FamilyTaskController {
    private final FamilyTaskService service;

    public FamilyTaskController(FamilyTaskService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List family tasks in the caller's group, filtered by assignee/status")
    public ApiResponse<List<FamilyTaskResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "assignee_id", required = false) String assigneeId,
            @RequestParam(name = "status", required = false) FamilyTaskStatus status) {
        return ApiResponses.success(service.list(jwt.getSubject(), assigneeId, status));
    }

    @PostMapping
    @Operation(summary = "Create a family task")
    public ResponseEntity<ApiResponse<FamilyTaskResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateFamilyTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(service.create(jwt.getSubject(), request)));
    }

    @PatchMapping("/{taskId}")
    @Operation(summary = "Complete or reassign a family task with optimistic locking")
    public ApiResponse<FamilyTaskResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String taskId,
            @Valid @RequestBody UpdateFamilyTaskRequest request) {
        return ApiResponses.success(service.update(jwt.getSubject(), taskId, request));
    }

    @DeleteMapping("/{taskId}")
    @Operation(summary = "Soft delete a family task")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String taskId) {
        service.delete(jwt.getSubject(), taskId);
        return ResponseEntity.noContent().build();
    }
}
