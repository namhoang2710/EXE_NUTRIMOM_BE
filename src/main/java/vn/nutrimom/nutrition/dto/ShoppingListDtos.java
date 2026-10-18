package vn.nutrimom.nutrition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.ShoppingItemState;
import vn.nutrimom.nutrition.dto.RecommendationDtos.ReasonView;

/**
 * Hợp đồng JSON của danh sách mua sắm (nhánh 7). snake_case như phần còn lại của API.
 *
 * <p>Hai con số lượng của một dòng trả lời hai câu khác nhau và <strong>không</strong> thay nhau
 * được: {@code quantity} là lượng cần (dẫn xuất từ thực đơn, hệ thống tính), còn
 * {@code have_quantity} / {@code purchased_quantity} là thứ người dùng khai. {@code remaining_quantity}
 * là hiệu của chúng — trả sẵn để giao diện không phải tự trừ, vì trừ ở hai nơi là hai kết quả.</p>
 */
public final class ShoppingListDtos {

    private ShoppingListDtos() {
    }

    // =========================================================================================
    // Yêu cầu
    // =========================================================================================

    /**
     * Thêm một dòng tự nhập.
     *
     * <p>Dòng tự nhập <strong>không trỏ tới nguyên liệu nào</strong> và vì vậy không ảnh hưởng
     * dinh dưỡng của thực đơn. Đây là chỗ cho "giấy ăn", "trái cây tráng miệng", "sữa cho bé" —
     * những thứ đi cùng lượt đi chợ nhưng không thuộc kế hoạch.</p>
     *
     * @param quantity số lượng, không bắt buộc: "mua ít trái cây" là một dòng hợp lệ
     * @param unit     đơn vị tự do của dòng này (hộp, bó, chai). Không quy đổi, không cộng với
     *                 dòng nào — nên ở đây danh sách đơn vị <em>không</em> bị giới hạn như ở
     *                 {@link PatchShoppingItemRequest}
     */
    public record CustomLineRequest(
            @NotBlank(message = "text không được rỗng")
            @Size(max = 255, message = "text tối đa 255 ký tự") String text,
            @DecimalMin(value = "0", message = "quantity không âm") BigDecimal quantity,
            @Size(max = 16, message = "unit tối đa 16 ký tự") String unit) {
    }

    /**
     * Đánh dấu đã có / đã mua, hoặc sửa chữ của một dòng tự nhập.
     *
     * <p>Trường nào bỏ trống thì <strong>giữ nguyên</strong>. Chuyển về {@link ShoppingItemState#NEEDED}
     * xóa cả hai con số đã khai, vì "chưa mua gì cả" mà vẫn còn "đã mua 200 g" là hai câu mâu
     * thuẫn nằm trên cùng một dòng.</p>
     *
     * @param unit đơn vị của {@code have_quantity} và {@code purchased_quantity} trong <em>yêu cầu
     *             này</em>; bỏ trống = đơn vị của dòng. <strong>Chỉ nhận đơn vị quy đổi được</strong>
     *             (xem {@code MassUnits}): gửi "thìa" bị từ chối kèm tên trường thay vì được đoán
     *             thành một số gram nào đó
     */
    public record PatchShoppingItemRequest(
            ShoppingItemState state,
            @DecimalMin(value = "0", message = "have_quantity không âm") BigDecimal haveQuantity,
            @DecimalMin(value = "0", message = "purchased_quantity không âm")
            BigDecimal purchasedQuantity,
            @Size(max = 16, message = "unit tối đa 16 ký tự") String unit,
            @Size(max = 255, message = "custom_text tối đa 255 ký tự") String customText) {

        public boolean changesNothing() {
            return state == null && haveQuantity == null && purchasedQuantity == null
                    && customText == null;
        }
    }

    // =========================================================================================
    // Phản hồi
    // =========================================================================================

    /**
     * Cả danh sách.
     *
     * @param shoppingListVersion bằng đúng {@code plan_version} của thực đơn: danh sách mua sắm là
     *                            hàm thuần của các mục kế hoạch, nên nó đổi đúng khi và chỉ khi kế
     *                            hoạch đổi. Nhánh 6 trả cùng con số này sau mỗi thao tác sửa
     * @param estimatedCostVnd    tổng của những dòng <strong>đã có giá</strong> quy đổi được; luôn
     *                            đọc kèm {@code cost_coverage}, không bao giờ đọc một mình
     * @param estimatedRemainingCostVnd phần còn phải mua, sau khi trừ lượng đã có và đã mua
     * @param costCoverage        {@link CoverageStatus#PARTIAL} = còn nguyên liệu chưa có giá, tổng
     *                            trên đây <em>chưa đầy đủ</em>. Thiếu giá không bao giờ được coi là
     *                            0 đồng
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShoppingListView(String listId, String planId, int shoppingListVersion,
                                   LocalDate startDate, LocalDate endDate,
                                   List<ShoppingGroupView> groups,
                                   BigDecimal estimatedCostVnd,
                                   BigDecimal estimatedRemainingCostVnd,
                                   CoverageStatus costCoverage,
                                   List<ReasonView> notes) {
    }

    /**
     * Một nhóm hàng trong danh sách.
     *
     * @param foodGroup null = nhóm các <strong>dòng tự nhập</strong>, luôn xếp cuối: chúng không
     *                  thuộc nhóm thực phẩm nào vì chúng không phải nguyên liệu của thực đơn
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShoppingGroupView(FoodGroup foodGroup, List<ShoppingItemView> items) {
    }

    /**
     * Một dòng đi chợ.
     *
     * @param quantity          lượng cần theo thực đơn; null ở dòng tự nhập không ghi số lượng
     * @param remainingQuantity còn phải mua = {@code quantity} − đã có − đã mua, không âm
     * @param estimatedCostVnd  chi phí ước tính của <em>lượng cần</em>; null = <strong>chưa có
     *                          giá</strong> quy đổi được, không phải 0 đồng
     * @param usedBy            món nào dùng nguyên liệu này và dùng bao nhiêu — "món sử dụng" của
     *                          màn hình mua sắm
     * @param notes             lý do nói về chính dòng này: cần thêm sau khi đổi món, chưa có giá,
     *                          nhắc đọc nhãn thành phần
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShoppingItemView(String itemId, String ingredientId, String name,
                                   FoodState foodState, FoodGroup foodGroup,
                                   ShoppingItemState state, BigDecimal quantity, String unit,
                                   BigDecimal haveQuantity, BigDecimal purchasedQuantity,
                                   BigDecimal remainingQuantity, BigDecimal estimatedCostVnd,
                                   String customText, List<ShoppingUsageView> usedBy,
                                   List<ReasonView> notes) {
    }

    /** Một món dùng nguyên liệu này, kèm phần của nó trong lượng cần. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShoppingUsageView(String planItemId, String recipeId, String name,
                                    BigDecimal quantity) {
    }
}
