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
import vn.nutrimom.family.dto.ReceivedFamilyInvitationResponse;
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
     * Xem trước lời mời theo token trong link email.
     *
     * <p><strong>Có bắt đăng nhập, nhưng cố ý KHÔNG kiểm người gọi có phải người được mời hay
     * không.</strong> Đăng nhập ở đây là lớp chống dò token — không phải kiểm sở hữu. Giữ token
     * <em>chính là</em> phân quyền trên đường này, nên bất kỳ tài khoản đã đăng nhập nào cầm token
     * hợp lệ đều xem trước được.</p>
     *
     * <p>Đó là điều kiện để link trong email dùng được thật: người được mời có thể đang đăng nhập
     * bằng tài khoản khác, hoặc địa chỉ được mời chưa gắn vào tài khoản nào của họ. Chặn ở bước
     * xem trước chỉ cho họ một trang lỗi trống mà không ngăn được gì — {@code accept} vẫn kiểm
     * khớp email/sđt, và đó mới là chỗ quyết định.</p>
     *
     * <p>Response đã che địa chỉ người được mời và không mang id nội bộ nào, nên một người lạ cầm
     * token cũng chỉ thấy đúng những gì cần để quyết định bấm tiếp hay không.</p>
     */
    @GetMapping("/preview")
    @Operation(summary = "Xem trước lời mời theo token trước khi chấp nhận")
    public ApiResponse<FamilyInvitationPreviewResponse> preview(
            @RequestParam @NotBlank @Size(max = 200) String token) {
        return ApiResponses.success(service.preview(token));
    }

    @GetMapping
    @Operation(summary = "Danh sách lời mời của nhóm mình sở hữu; không kèm token thô")
    public ApiResponse<List<FamilyInvitationSummaryResponse>> list(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.list(jwt.getSubject()));
    }

    /**
     * Hộp thư của người được mời: chỉ lời mời đang chờ, để mọi dòng đều bấm được.
     *
     * <p>Đặt trước {@code /{invitationId}/...} trong file cho dễ đọc; Spring vốn ưu tiên segment
     * literal nên {@code /received} không bị {@code /{invitationId}} nuốt.</p>
     */
    @GetMapping("/received")
    @Operation(summary = "Lời mời đang chờ gửi cho tài khoản đang đăng nhập")
    public ApiResponse<List<ReceivedFamilyInvitationResponse>> received(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.received(jwt.getSubject()));
    }

    /**
     * Xem trước theo id — lối vào từ thông báo in-app, không cần token.
     *
     * <p>Lời mời không gửi cho mình trả <strong>404</strong>, không phải 403: id không phải bí mật
     * — nó nằm trong deep link thông báo và trong danh sách của chủ nhóm — nên 403 sẽ thành chỗ dò
     * "id này có tồn tại không".</p>
     *
     * <p>Đường theo id là đường <em>duy nhất</em> kiểm người gọi có phải người được mời. Đường
     * token ở {@link #preview} cố ý không kiểm, vì ở đó việc giữ token đã là phân quyền. Hai lối
     * vào bất đối xứng là cố ý, không phải sót.</p>
     */
    @GetMapping("/{invitationId}/preview")
    @Operation(summary = "Xem trước lời mời theo id, dành cho chính người được mời")
    public ApiResponse<FamilyInvitationPreviewResponse> previewById(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String invitationId) {
        return ApiResponses.success(service.previewById(jwt.getSubject(), invitationId));
    }

    @PostMapping("/{invitationId}/accept")
    @Operation(summary = "Chấp nhận lời mời theo id, dành cho chính người được mời")
    public ApiResponse<FamilyMemberResponse> acceptById(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String invitationId) {
        return ApiResponses.success(service.acceptById(jwt.getSubject(), invitationId));
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
