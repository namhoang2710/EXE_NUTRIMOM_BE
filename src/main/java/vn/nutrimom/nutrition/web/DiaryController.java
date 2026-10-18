package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.nutrition.dto.DiaryDtos.DiaryView;
import vn.nutrimom.nutrition.service.DiaryService;

/**
 * Nhật ký của một ngày.
 *
 * <p><strong>Cần đăng nhập.</strong> Chủ sở hữu suy từ {@code jwt.getSubject()} và nằm trong chính
 * câu truy vấn, nên nhật ký của người khác không trả về dòng nào (BR19, TC20).</p>
 */
@RestController
@RequestMapping("/api/v1/diary")
@Tag(name = "Diary", description = "Nhật ký món đã ăn và phần còn lại của ngày")
@SecurityRequirement(name = "bearerAuth")
public class DiaryController {

    private final DiaryService service;

    public DiaryController(DiaryService service) {
        this.service = service;
    }

    /**
     * Món đã ghi và <strong>hai tổng tách biệt</strong> của một ngày.
     *
     * <p>{@code planned_nutrition} và {@code logged_nutrition} là hai khối riêng, mỗi chất kèm
     * {@code coverage}. Không có trường nào là tổng của hai khối đó: dự kiến và đã ghi là hai tập
     * dữ liệu độc lập, và cộng chúng lại sẽ đếm hai lần những bữa đã ăn theo kế hoạch.</p>
     *
     * <p>Con số đã ghi luôn đi kèm mã {@code DIARY_LOGGED_TOTAL_ONLY} — nó là
     * <strong>"tổng của các món đã ghi"</strong>, không phải "tổng lượng ăn cả ngày". Hệ thống chỉ
     * biết những gì người dùng bấm vào.</p>
     *
     * @param date ngày theo lịch của người dùng. <strong>Bắt buộc</strong>: máy chủ không biết
     *             người dùng đang ở múi giờ nào, và đoán sai ngày sẽ hiện nhầm cả một nhật ký
     */
    @GetMapping
    @Operation(summary = "Nhật ký và hai tổng của một ngày")
    public ApiResponse<DiaryView> get(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponses.success(service.view(jwt.getSubject(), date));
    }
}
