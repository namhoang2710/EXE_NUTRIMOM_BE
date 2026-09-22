package vn.nutrimom.consultation.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.consultation.dto.ExpertDtos.AdminExpertResponse;
import vn.nutrimom.consultation.dto.ExpertDtos.CreateExpertRequest;
import vn.nutrimom.consultation.dto.ExpertDtos.UpdateExpertRequest;
import vn.nutrimom.consultation.service.ExpertAdminService;
import vn.nutrimom.consultation.service.ExpertAvatarService;

@RestController
@RequestMapping("/api/v1/admin/experts")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin experts", description = "Admin CRUD chuyên gia và cấp tài khoản")
@SecurityRequirement(name = "bearerAuth")
public class AdminExpertController {
    private final ExpertAdminService service;
    private final ExpertAvatarService avatarService;

    public AdminExpertController(ExpertAdminService service, ExpertAvatarService avatarService) {
        this.service = service;
        this.avatarService = avatarService;
    }

    @PostMapping
    @Operation(summary = "Tạo chuyên gia + cấp tài khoản (role EXPERT)")
    public ResponseEntity<ApiResponse<AdminExpertResponse>> create(
            @Valid @RequestBody CreateExpertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(service.create(request)));
    }

    @PostMapping(value = "/{userId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload avatar chuyên gia")
    public ApiResponse<AdminExpertResponse> uploadAvatar(
            @PathVariable String userId, @RequestPart("file") MultipartFile file) {
        return ApiResponses.success(avatarService.uploadAvatar(userId, file));
    }

    @GetMapping
    @Operation(summary = "Danh sách chuyên gia")
    public ApiResponse<List<AdminExpertResponse>> list() {
        return ApiResponses.success(service.list());
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Chi tiết chuyên gia")
    public ApiResponse<AdminExpertResponse> detail(@PathVariable String userId) {
        return ApiResponses.success(service.detail(userId));
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "Cập nhật hồ sơ chuyên gia (optimistic lock)")
    public ApiResponse<AdminExpertResponse> update(
            @PathVariable String userId, @Valid @RequestBody UpdateExpertRequest request) {
        return ApiResponses.success(service.update(userId, request));
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Ngừng hoạt động chuyên gia (xóa mềm)")
    public ResponseEntity<Void> deactivate(@PathVariable String userId) {
        service.deactivate(userId);
        return ResponseEntity.noContent().build();
    }
}
