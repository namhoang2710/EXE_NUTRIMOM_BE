package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.NutritionProfileView;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.SaveNutritionProfileRequest;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.SaveNutritionProfileResponse;
import vn.nutrimom.nutrition.service.NutritionProfileService;

/**
 * Hồ sơ ăn uống của chính người đang đăng nhập.
 *
 * <p>Khác hai endpoint danh mục công khai, mọi thứ ở đây là <strong>dữ liệu cá nhân</strong>: cần
 * token, và chủ sở hữu suy từ {@code jwt.getSubject()}. Đường dẫn cố ý không mang mã người dùng —
 * không có {@code /users/{id}/nutrition-profile} để quên kiểm quyền (BR19).</p>
 */
@RestController
@RequestMapping("/api/v1/me/nutrition-profile")
@Tag(name = "Nutrition profile", description = "Hồ sơ ăn uống của người dùng đang đăng nhập")
@SecurityRequirement(name = "bearerAuth")
public class NutritionProfileController {

    private final NutritionProfileService service;

    public NutritionProfileController(NutritionProfileService service) {
        this.service = service;
    }

    /** Hồ sơ ăn uống kèm mốc thai kỳ tính tại ngày lịch hôm nay của người dùng. */
    @GetMapping
    @Operation(summary = "Hồ sơ ăn uống và mốc thai kỳ hiện tại")
    public ApiResponse<NutritionProfileView> get(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.get(jwt.getSubject()));
    }

    /**
     * Lưu hồ sơ ăn uống; trả về {@code profile_version} mới.
     *
     * <p>Hợp nhất chứ không thay thế: trường vắng mặt giữ nguyên giá trị cũ, mảng rỗng
     * {@code []} xóa danh sách tương ứng. Onboarding lưu theo từng bước và bước 3, 5 bỏ qua được,
     * nên ngữ nghĩa thay thế toàn bộ sẽ khiến bước sau xóa mất bước trước.</p>
     */
    @PutMapping
    @Operation(summary = "Lưu hồ sơ ăn uống")
    public ApiResponse<SaveNutritionProfileResponse> save(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SaveNutritionProfileRequest request) {
        return ApiResponses.success(service.save(jwt.getSubject(), request));
    }
}
