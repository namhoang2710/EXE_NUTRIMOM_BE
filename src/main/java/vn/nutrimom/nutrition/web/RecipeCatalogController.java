package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.dto.NutritionDtos.RecipeDetail;
import vn.nutrimom.nutrition.dto.NutritionDtos.RecipeSummary;
import vn.nutrimom.nutrition.service.RecipeCatalogService;

/**
 * Tra cứu công thức của module dinh dưỡng.
 *
 * <p>Hai endpoint này <strong>công khai</strong>, xem được khi chưa đăng nhập — danh mục món là nội
 * dung giới thiệu, mẹ bầu phải xem được trước khi quyết định tạo tài khoản. Mọi endpoint cá nhân
 * khác của module (hồ sơ, gợi ý, thực đơn, nhật ký) đều cần token; đừng mở rộng {@code permitAll}
 * sang cả prefix.</p>
 *
 * <p>Không có {@code @SecurityRequirement} ở đây, khác với các controller còn lại, chính vì lý do
 * trên.</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/recipes")
@Tag(name = "Nutrition catalog", description = "Tra cứu công thức, công khai")
public class RecipeCatalogController {

    private final RecipeCatalogService catalog;

    public RecipeCatalogController(RecipeCatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * Danh sách món, phân trang bằng con trỏ.
     *
     * @param mealType   lọc theo loại bữa; bỏ trống = mọi loại
     * @param role       lọc theo vai trò trong bữa; bỏ trống = mọi vai trò
     * @param maxMinutes chỉ lấy món nấu trong tối đa chừng này phút
     * @param cursor     {@code next_cursor} của lượt gọi trước; bỏ trống = từ đầu
     */
    @GetMapping
    @Operation(summary = "Danh sách công thức")
    public ApiResponse<CursorPage<RecipeSummary>> list(
            @RequestParam(required = false) MealType mealType,
            @RequestParam(required = false) RecipeRole role,
            @RequestParam(required = false) @Min(1) @Max(600) Integer maxMinutes,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return ApiResponses.success(catalog.list(mealType, role, maxMinutes, cursor, limit));
    }

    /** Chi tiết một món: khẩu phần, nguyên liệu, các bước nấu và trạng thái dữ liệu. */
    @GetMapping("/{recipeId}")
    @Operation(summary = "Chi tiết công thức")
    public ApiResponse<RecipeDetail> detail(@PathVariable String recipeId) {
        return ApiResponses.success(catalog.detail(recipeId));
    }
}
