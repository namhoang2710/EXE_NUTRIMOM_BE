package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.nutrition.domain.AllergenEntity;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.dto.NutritionLookupDtos.AllergenOption;
import vn.nutrimom.nutrition.dto.NutritionLookupDtos.IngredientOption;
import vn.nutrimom.nutrition.repository.AllergenRepository;
import vn.nutrimom.nutrition.repository.IngredientRepository;

/**
 * Tra cứu danh mục để điền hồ sơ ăn uống: chip thành phần gây dị ứng và ô tìm nguyên liệu.
 *
 * <p><strong>Vì sao nằm ở nhánh 3 chứ không phải nhánh 1:</strong> hai bảng bên dưới là của nhánh
 * 1, nhưng nhánh 1 chỉ mở endpoint cho <em>công thức</em>. Bước 2 và bước 3 của onboarding cần
 * chọn thành phần gây dị ứng và tìm nguyên liệu, và nếu không có hai endpoint này thì giao diện
 * phải tự nhúng cứng danh sách — đúng thứ sẽ lệch khỏi seed ngay lần cập nhật đầu tiên.</p>
 *
 * <p><strong>Công khai</strong>, cùng lý do với {@link RecipeCatalogController}: đây là danh mục
 * dùng chung, không mang dữ liệu của ai. Hồ sơ của người dùng ở
 * {@link NutritionProfileController} và cần token.</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Nutrition catalog", description = "Tra cứu công thức, công khai")
public class NutritionLookupController {

    /** Đủ rộng cho một ô gợi ý, đủ hẹp để không ai dùng endpoint này tải cả danh mục về. */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final AllergenRepository allergens;
    private final IngredientRepository ingredients;

    public NutritionLookupController(AllergenRepository allergens,
                                     IngredientRepository ingredients) {
        this.allergens = allergens;
        this.ingredients = ingredients;
    }

    /**
     * Toàn bộ thành phần gây dị ứng trong danh mục, để vẽ chip ở bước 2.
     *
     * <p>Không phân trang: danh sách này là một tập đóng vài chục dòng và giao diện cần cả tập
     * cùng lúc để người dùng thấy hết lựa chọn, chứ không cuộn dần.</p>
     */
    @GetMapping("/allergens")
    @Operation(summary = "Danh mục thành phần gây dị ứng")
    public ApiResponse<List<AllergenOption>> allergens() {
        return ApiResponses.success(allergens.findAllByOrderByNameAsc().stream()
                .map(this::toOption)
                .toList());
    }

    /**
     * Tìm nguyên liệu theo tên, dùng cho ô tìm ở bước 2 và bước 3.
     *
     * @param q bỏ trống = vài nguyên liệu đầu danh mục, để ô tìm không trống trơn lúc mới mở
     */
    @GetMapping("/ingredients")
    @Operation(summary = "Tìm nguyên liệu theo tên")
    public ApiResponse<List<IngredientOption>> ingredients(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Min(1) @Max(MAX_LIMIT) Integer limit) {
        PageRequest page = PageRequest.ofSize(limit == null ? DEFAULT_LIMIT : limit);
        List<IngredientEntity> found = q == null || q.isBlank()
                ? ingredients.findAllByOrderByCanonicalNameAsc(page)
                : ingredients.search(q.trim(), page);
        return ApiResponses.success(found.stream().map(this::toOption).toList());
    }

    private AllergenOption toOption(AllergenEntity allergen) {
        return new AllergenOption(allergen.getId(), allergen.getName());
    }

    private IngredientOption toOption(IngredientEntity ingredient) {
        return new IngredientOption(ingredient.getId(), ingredient.getCanonicalName(),
                ingredient.getFoodGroup(), ingredient.getAllergenStatus());
    }
}
