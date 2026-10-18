package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.nutrition.dto.DiaryDtos.MealFeedbackRequest;
import vn.nutrimom.nutrition.dto.DiaryDtos.MealFeedbackView;
import vn.nutrimom.nutrition.service.MealFeedbackService;

/**
 * Phản hồi sau bữa — một lần chạm.
 *
 * <p>Ba lựa chọn, đúng như màn hình: <strong>Hợp khẩu vị</strong> ({@code TASTY}),
 * <strong>Không thích</strong> ({@code DISLIKE}), <strong>Khó ăn lúc này</strong>
 * ({@code HARD_NOW}). Chỉ cái cuối có hạn — bảy ngày — vì chỉ nó là trạng thái tạm thời của thai
 * kỳ (QĐ-03).</p>
 *
 * <p><strong>Không lựa chọn nào ở đây trở thành dị ứng.</strong> Chúng cộng trừ điểm khẩu vị ở
 * bước xếp hạng; món vẫn qua đúng bộ lọc an toàn như mọi món khác. Muốn chặn hẳn một món thì đó là
 * {@code BLOCKED} trong hồ sơ ăn uống — một lựa chọn chủ động, không hạn, và nó loại món khỏi ứng
 * viên chứ không trừ điểm.</p>
 */
@RestController
@RequestMapping("/api/v1/meal-feedback")
@Tag(name = "Diary", description = "Nhật ký món đã ăn và phần còn lại của ngày")
@SecurityRequirement(name = "bearerAuth")
public class MealFeedbackController {

    private final MealFeedbackService service;

    public MealFeedbackController(MealFeedbackService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Yêu thích, không thích, hoặc khó ăn tạm thời")
    public ApiResponse<MealFeedbackView> submit(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody MealFeedbackRequest request) {
        return ApiResponses.success(service.submit(jwt.getSubject(), request));
    }
}
