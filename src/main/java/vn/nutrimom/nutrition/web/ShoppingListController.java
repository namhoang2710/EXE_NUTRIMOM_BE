package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.CustomLineRequest;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.ShoppingListView;
import vn.nutrimom.nutrition.service.ShoppingListService;

/**
 * Danh sách mua sắm của một thực đơn.
 *
 * <p><strong>Cần đăng nhập.</strong> Chủ sở hữu suy từ {@code jwt.getSubject()} và nằm trong chính
 * câu truy vấn kế hoạch, nên thực đơn của người khác không trả về dòng nào (BR19, TC20).</p>
 *
 * <p>Tham số truy vấn viết snake_case như các trường trong body: {@code start_date} ở đây và
 * {@code start_date} trong phản hồi là cùng một thứ, nên đặt hai cách viết khác nhau cho chúng chỉ
 * tạo ra một chỗ để gõ nhầm.</p>
 */
@RestController
@RequestMapping("/api/v1/meal-plans/{planId}/shopping-list")
@Tag(name = "Shopping list", description = "Biến thực đơn thành danh sách đi chợ")
@SecurityRequirement(name = "bearerAuth")
public class ShoppingListController {

    private final ShoppingListService service;

    public ShoppingListController(ShoppingListService service) {
        this.service = service;
    }

    /**
     * Tổng hợp nguyên liệu theo phạm vi ngày.
     *
     * <p>Nguyên liệu gộp theo <strong>danh tính và trạng thái chế biến</strong>: gạo sống và cơm
     * chín không bao giờ cộng vào nhau (TC10). Mỗi dòng kèm món sử dụng, lượng cần và trạng thái đã
     * có / đã mua.</p>
     *
     * <p>Lần gọi này cũng là lúc danh sách được <strong>đồng bộ</strong> với thực đơn: lượng cần
     * được tính lại theo {@code plan_version} mới nhất, còn những ô người dùng đã tick thì giữ
     * nguyên. Phép đồng bộ idempotent nên gọi lại không sinh thêm gì.</p>
     *
     * @param excludeItemIds các mục kế hoạch được <strong>bỏ chọn</strong> ở lần xem này — "món
     *                       này tôi không cần mua". Đây là lựa chọn của lần xem, không phải một
     *                       thay đổi của thực đơn, nên nó lọc dòng trả về chứ không xóa dòng nào
     */
    @GetMapping
    @Operation(summary = "Danh sách mua sắm của thực đơn theo phạm vi ngày")
    public ApiResponse<ShoppingListView> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String planId,
            @RequestParam(name = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "exclude_item_ids", required = false) List<String> excludeItemIds) {
        return ApiResponses.success(service.view(jwt.getSubject(), planId, startDate, endDate,
                excludeItemIds == null ? Set.of() : Set.copyOf(excludeItemIds)));
    }

    /**
     * Thêm một dòng tự nhập vào danh sách.
     *
     * <p>Dòng tự nhập dành cho nhu cầu khác của cùng lượt đi chợ. Nó <strong>không ảnh hưởng dinh
     * dưỡng</strong>: không trỏ tới nguyên liệu nào nên không có đường nào lọt vào tổng của thực
     * đơn, và không lượt đồng bộ nào xóa nó đi.</p>
     */
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm một dòng tự nhập")
    public ApiResponse<ShoppingListView> addCustomLine(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String planId,
            @RequestParam(name = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Valid @RequestBody CustomLineRequest request) {
        return ApiResponses.success(
                service.addCustomLine(jwt.getSubject(), planId, startDate, endDate, request));
    }
}
