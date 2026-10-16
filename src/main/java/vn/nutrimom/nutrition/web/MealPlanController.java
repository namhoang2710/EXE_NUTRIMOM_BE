package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PlanView;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PreviewRequest;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PreviewResponse;
import vn.nutrimom.nutrition.dto.MealPlanDtos.SavePlanRequest;
import vn.nutrimom.nutrition.service.MealPlanService;

/**
 * Thực đơn một ngày: tạo bản xem trước, lưu, và đọc lại.
 *
 * <p><strong>Cần đăng nhập</strong> cho cả ba endpoint. Chủ sở hữu luôn suy từ
 * {@code jwt.getSubject()} và <strong>không bao giờ</strong> từ body — một {@code user_id} gửi lên
 * chỉ là một gợi ý của client (BR19, TC20).</p>
 */
@RestController
@RequestMapping("/api/v1/meal-plans")
@Tag(name = "Meal plans", description = "Lập và lưu thực đơn một ngày")
@SecurityRequirement(name = "bearerAuth")
public class MealPlanController {

    private final MealPlanService service;

    public MealPlanController(MealPlanService service) {
        this.service = service;
    }

    /**
     * Tạo bản xem trước có thời hạn 30 phút.
     *
     * <p>Không tìm được thực đơn vẫn trả <strong>HTTP 200</strong> kèm {@code status = NO_MATCH} và
     * lý do nêu rõ bữa nào, vai trò nào, bộ lọc nào gây giới hạn: hết món hợp lệ là kết quả nghiệp
     * vụ của một hồ sơ nhiều ràng buộc, không phải lỗi hệ thống.</p>
     *
     * <p>Giao diện chuyển nút sang trạng thái xử lý và chặn nhấn trùng; lỗi mạng thì thử lại với
     * <strong>cùng {@code request_id}</strong> và sẽ nhận lại đúng bản xem trước cũ.</p>
     */
    @PostMapping("/preview")
    @Operation(summary = "Tạo bản xem trước thực đơn một ngày")
    public ApiResponse<PreviewResponse> preview(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody PreviewRequest request) {
        return ApiResponses.success(service.preview(jwt.getSubject(), request));
    }

    /**
     * Lưu một bản xem trước thành kế hoạch.
     *
     * <p>Kiểm lại hạn dùng và {@code profile_version} ngay tại đây; lệch thì <strong>không
     * lưu</strong> và trả {@code VERSION_CONFLICT} để giao diện mời tạo lại thực đơn.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Lưu bản xem trước thành thực đơn")
    public ApiResponse<PlanView> save(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody SavePlanRequest request) {
        return ApiResponses.success(service.save(jwt.getSubject(), request));
    }

    /** Thực đơn đã lưu kèm tổng <strong>dự kiến</strong> của ngày. */
    @GetMapping("/{planId}")
    @Operation(summary = "Đọc một thực đơn đã lưu")
    public ApiResponse<PlanView> get(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable String planId) {
        return ApiResponses.success(service.get(jwt.getSubject(), planId));
    }
}
