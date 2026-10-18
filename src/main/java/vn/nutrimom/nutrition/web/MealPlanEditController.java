package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.AlternativesView;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.PatchItemRequest;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.PlanEditView;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.RegenerateRequest;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.UndoRequest;
import vn.nutrimom.nutrition.service.MealPlanEditService;

/**
 * Sửa một thực đơn đã lưu: đổi món, khóa món, tạo lại, hoàn tác.
 *
 * <p>Mục tiêu của bốn endpoint này là mẹ bầu sửa được <strong>một</strong> lựa chọn mà không phải
 * tạo lại toàn bộ kế hoạch.</p>
 *
 * <p><strong>Cần đăng nhập</strong> cho cả bốn. Chủ sở hữu luôn suy từ {@code jwt.getSubject()} và
 * <strong>không bao giờ</strong> từ body hay từ đường dẫn: kế hoạch của người khác không trả về
 * dòng nào, nên không có đường nào quên mất bước kiểm (BR19, TC20).</p>
 */
@RestController
@RequestMapping("/api/v1/meal-plans/{planId}")
@Tag(name = "Meal plans", description = "Lập và lưu thực đơn một ngày")
@SecurityRequirement(name = "bearerAuth")
public class MealPlanEditController {

    private final MealPlanEditService service;

    public MealPlanEditController(MealPlanEditService service) {
        this.service = service;
    }

    /**
     * Các lựa chọn thay thế cho một mục, kèm chênh lệch chi phí và thời gian so với món đang có.
     *
     * <p>Danh sách dựng lại từ hồ sơ <strong>hiện tại</strong>, không tin kết quả lọc lúc lập thực
     * đơn. Rỗng vẫn là HTTP 200 kèm lý do nêu rõ bộ lọc nào đã chặn — hết món hợp lệ là kết quả
     * nghiệp vụ, không phải lỗi hệ thống.</p>
     */
    @GetMapping("/items/{itemId}/alternatives")
    @Operation(summary = "Lựa chọn thay thế cùng bữa, cùng vai trò")
    public ApiResponse<AlternativesView> alternatives(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable String planId,
                                                      @PathVariable String itemId) {
        return ApiResponses.success(service.alternatives(jwt.getSubject(), planId, itemId));
    }

    /**
     * Đổi món, đổi số phần, hoặc khóa / bỏ khóa một mục.
     *
     * <p>Phiên bản lệch thì <strong>không thay gì</strong> và trả {@code VERSION_CONFLICT} (409):
     * thiết bị kia đã sửa, nên giao diện phải tải lại kế hoạch và mời chọn lại thay vì ghi đè
     * (TC14). Món vi phạm thành phần bắt buộc trả {@code RECIPE_NOT_ELIGIBLE} (422) kèm đúng câu
     * nói thành phần nào đang xung đột.</p>
     */
    @PatchMapping("/items/{itemId}")
    @Operation(summary = "Đổi món, số phần hoặc trạng thái giữ của một mục")
    public ApiResponse<PlanEditView> patchItem(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable String planId,
                                               @PathVariable String itemId,
                                               @Valid @RequestBody PatchItemRequest request) {
        return ApiResponses.success(service.patch(jwt.getSubject(), planId, itemId, request));
    }

    /**
     * Tạo lại các mục chưa giữ, nguyên vẹn những mục đã giữ.
     *
     * <p>Không tìm được phương án quanh các mục đã giữ thì kế hoạch <strong>giữ nguyên</strong>,
     * {@code changed} là false, và {@code notes} liệt kê chính các mục đó để người dùng chọn mục
     * muốn bỏ giữ.</p>
     */
    @PostMapping("/regenerate")
    @Operation(summary = "Tạo lại các mục chưa giữ")
    public ApiResponse<PlanEditView> regenerate(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable String planId,
                                                @Valid @RequestBody RegenerateRequest request) {
        return ApiResponses.success(service.regenerate(jwt.getSubject(), planId, request));
    }

    /**
     * Khôi phục kế hoạch về ngay trước thao tác sửa gần nhất.
     *
     * <p>Kiểm lại điều kiện hiện tại trước khi khôi phục — hồ sơ có thể đã đổi — và
     * <strong>không</strong> ghi lại nhật ký. Một bước lùi: hoàn tác hai lần liên tiếp nhận 404.
     * </p>
     */
    @PostMapping("/undo")
    @Operation(summary = "Hoàn tác thao tác sửa gần nhất")
    public ApiResponse<PlanEditView> undo(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable String planId,
                                          @Valid @RequestBody UndoRequest request) {
        return ApiResponses.success(service.undo(jwt.getSubject(), planId, request));
    }
}
