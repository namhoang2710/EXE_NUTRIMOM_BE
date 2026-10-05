package vn.nutrimom.family.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.family.dto.AcceptFamilyInvitationRequest;
import vn.nutrimom.family.dto.CreateFamilyInvitationRequest;
import vn.nutrimom.family.dto.FamilyInvitationPreviewResponse;
import vn.nutrimom.family.dto.FamilyInvitationResponse;
import vn.nutrimom.family.dto.FamilyInvitationSummaryResponse;
import vn.nutrimom.family.dto.FamilyMemberResponse;
import vn.nutrimom.family.service.FamilyInvitationService;

@Validated
@RestController
@RequestMapping("/api/v1/family-invitations")
@Tag(name = "Family sharing", description = "Pregnancy family groups and scoped sharing")
@SecurityRequirement(name = "bearerAuth")
public class FamilyInvitationController {
    private final FamilyInvitationService service;

    public FamilyInvitationController(FamilyInvitationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create an expiring one-time invitation as pregnancy owner")
    public ResponseEntity<ApiResponse<FamilyInvitationResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateFamilyInvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(service.create(jwt.getSubject(), request)));
    }

    /**
     * Cần đăng nhập: người được mời dù sao cũng phải có tài khoản sẵn mới chấp nhận được, nên mở
     * endpoint này ra public chỉ tặng thêm một bề mặt để dò token mà không đổi lại được gì.
     */
    @GetMapping("/preview")
    @Operation(summary = "Xem trước lời mời theo token trước khi chấp nhận")
    public ApiResponse<FamilyInvitationPreviewResponse> preview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @NotBlank @Size(max = 200) String token) {
        return ApiResponses.success(service.preview(jwt.getSubject(), token));
    }

    @GetMapping
    @Operation(summary = "Danh sách lời mời của nhóm mình sở hữu; không kèm token thô")
    public ApiResponse<List<FamilyInvitationSummaryResponse>> list(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.list(jwt.getSubject()));
    }

    @DeleteMapping("/{invitationId}")
    @Operation(summary = "Thu hồi một lời mời chưa được dùng")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String invitationId) {
        service.revoke(jwt.getSubject(), invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/accept")
    @Operation(summary = "Accept an invitation as the authenticated invited account")
    public ApiResponse<FamilyMemberResponse> accept(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AcceptFamilyInvitationRequest request) {
        return ApiResponses.success(service.accept(jwt.getSubject(), request));
    }
}
