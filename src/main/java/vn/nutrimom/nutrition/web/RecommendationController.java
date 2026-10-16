package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.nutrition.dto.RecommendationDtos.RecommendationRequest;
import vn.nutrimom.nutrition.dto.RecommendationDtos.RecommendationResponse;
import vn.nutrimom.nutrition.service.RecommendationService;

/**
 * Gợi ý món cho một bữa, hoặc cho phần còn lại của ngày.
 *
 * <p><strong>Cần đăng nhập</strong>, khác hai endpoint {@code /api/v1/recipes} công khai của nhánh
 * 1: gợi ý đọc hồ sơ ăn uống của người gọi, nên không có phiên bản ẩn danh nào của nó (QĐ-06).
 * Chủ sở hữu suy từ {@code jwt.getSubject()}.</p>
 *
 * <p>{@code POST} chứ không {@code GET} vì yêu cầu mang theo lịch sử gần đây và bộ đếm trong tuần
 * — những thứ nhánh 5 truyền vào từ kế hoạch <em>đang dựng dở</em>, dài hơn nhiều so với mức một
 * query string nên chở. Lượt gọi cũng ghi một dòng nhật ký gợi ý, nên nó không phải thao tác đọc
 * thuần.</p>
 */
@RestController
@RequestMapping("/api/v1/recommendations")
@Tag(name = "Nutrition recommendations", description = "Gợi ý món theo hồ sơ ăn uống")
@SecurityRequirement(name = "bearerAuth")
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    /**
     * Gợi ý món.
     *
     * <p>Không tìm được món nào vẫn trả <strong>HTTP 200</strong> kèm {@code status = NO_MATCH} và
     * lý do: hết món hợp lệ là kết quả nghiệp vụ, không phải lỗi hệ thống.</p>
     */
    @PostMapping
    @Operation(summary = "Gợi ý món cho một hoặc nhiều bữa")
    public ApiResponse<RecommendationResponse> recommend(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RecommendationRequest request) {
        return ApiResponses.success(service.recommend(jwt.getSubject(), request));
    }
}
