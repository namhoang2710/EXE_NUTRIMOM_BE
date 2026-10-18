package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.PatchShoppingItemRequest;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.ShoppingListView;
import vn.nutrimom.nutrition.service.ShoppingListService;

/**
 * Một dòng trong danh sách mua sắm.
 *
 * <p>Đường dẫn <strong>không mang mã kế hoạch</strong>: giao diện cầm sẵn mã dòng từ danh sách vừa
 * đọc, và bắt nó ghép lại mã kế hoạch chỉ tạo thêm một chỗ gửi sai. Vì vậy chủ sở hữu được kiểm
 * ngay trong câu truy vấn dòng ({@code findByIdAndListUserId}) — tài khoản khác hỏi đúng mã dòng
 * thì nhận 404, không có đường nào quên bước kiểm (BR19, TC20).</p>
 *
 * <p>Cả hai thao tác trả về <strong>cả danh sách đã tính lại</strong>, không riêng dòng vừa chạm:
 * tick một dòng làm đổi tổng còn phải mua, và để giao diện tự cộng lại tổng là tạo nguồn sự thật
 * thứ hai cho con số tiền.</p>
 */
@RestController
@RequestMapping("/api/v1/shopping-list-items/{itemId}")
@Tag(name = "Shopping list", description = "Biến thực đơn thành danh sách đi chợ")
@SecurityRequirement(name = "bearerAuth")
public class ShoppingListItemController {

    private final ShoppingListService service;

    public ShoppingListItemController(ShoppingListService service) {
        this.service = service;
    }

    /**
     * Đánh dấu đã có / đã mua, hoặc sửa chữ của một dòng tự nhập.
     *
     * <p>Nhập lượng đã có để xem lượng còn cần. <strong>Chỉ nhận đơn vị quy đổi được</strong>: gửi
     * {@code "unit": "thìa"} bị từ chối kèm tên trường thay vì được đoán thành một số gram.</p>
     */
    @PatchMapping
    @Operation(summary = "Đánh dấu đã có / đã mua một dòng")
    public ApiResponse<ShoppingListView> patch(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable String itemId,
                                               @Valid @RequestBody PatchShoppingItemRequest request) {
        return ApiResponses.success(service.patch(jwt.getSubject(), itemId, request));
    }

    /**
     * Xóa một dòng <strong>tự nhập</strong>.
     *
     * <p>Dòng nguyên liệu không xóa được: nó là hệ quả của thực đơn và sẽ bị lượt đồng bộ kế tiếp
     * tạo lại. Cách bỏ nó khỏi danh sách là bỏ chọn món ({@code exclude_item_ids}) hoặc đổi món.</p>
     */
    @DeleteMapping
    @Operation(summary = "Xóa một dòng tự nhập")
    public ApiResponse<ShoppingListView> delete(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable String itemId) {
        return ApiResponses.success(service.deleteCustomLine(jwt.getSubject(), itemId));
    }
}
