package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Một dòng đi chợ (bảng {@code app.shopping_list_items}, migration V63).
 *
 * <h2>Hai dạng dòng, không có dạng thứ ba</h2>
 *
 * <ul>
 *   <li><strong>Dòng nguyên liệu</strong> — {@code ingredientId} và {@code foodState} đều có.
 *       Lượng cần được tính lại từ các mục kế hoạch mỗi lần đồng bộ.</li>
 *   <li><strong>Dòng tự nhập</strong> — {@code ingredientId} và {@code foodState} đều null,
 *       {@code customText} có. Người dùng thêm thứ cần mua cho nhu cầu khác, và dòng đó
 *       <strong>không ảnh hưởng dinh dưỡng</strong>: nó không trỏ tới nguyên liệu nào nên không có
 *       đường nào để lọt vào một phép tính của thực đơn.</li>
 * </ul>
 *
 * <h2>Danh tính của một dòng là cặp (nguyên liệu, trạng thái)</h2>
 *
 * <p>{@code foodState} là một nửa của khóa gộp, không phải nhãn mô tả: 100 g gạo {@link
 * FoodState#RAW} và 100 g cơm {@link FoodState#COOKED} là hai dòng khác nhau (TC10). Ràng buộc
 * duy nhất {@code uq_shopping_list_items_identity} ép đúng điều đó ở mức database.</p>
 *
 * <p>{@code quantity} là lượng cần của <strong>lần đồng bộ gần nhất</strong>, còn
 * {@code haveQuantity} và {@code purchasedQuantity} là thao tác của người dùng. Đổi món làm lượng
 * cần tăng thì dòng <em>giữ nguyên</em> trạng thái đã mua và phần dôi ra hiện thành "cần thêm" —
 * chứ không reset cả dòng (TC22).</p>
 */
@Entity
@Table(name = "shopping_list_items", schema = "app")
public class ShoppingListItemEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "list_id", nullable = false)
    private ShoppingListEntity list;

    /** null = dòng tự nhập. */
    @Column(name = "ingredient_id", length = 64)
    private String ingredientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "food_state", length = 20)
    private FoodState foodState;

    @Enumerated(EnumType.STRING)
    @Column(name = "food_group", length = 30)
    private FoodGroup foodGroup;

    /**
     * Tên hiển thị chụp lúc đồng bộ.
     *
     * <p>Giữ lại ở đây để một dòng đã mua vẫn đọc được sau khi món dùng nguyên liệu đó biến khỏi
     * thực đơn — lúc ấy không còn snapshot nào trong kế hoạch để tra tên.</p>
     */
    @Column(name = "display_name", length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private ShoppingItemState state = ShoppingItemState.NEEDED;

    @Column(name = "quantity", precision = 10, scale = 2)
    private BigDecimal quantity;

    /** Đơn vị của cả ba con số lượng. Dòng nguyên liệu luôn là {@code g}. */
    @Column(name = "unit", length = 16)
    private String unit;

    @Column(name = "have_quantity", precision = 10, scale = 2)
    private BigDecimal haveQuantity;

    @Column(name = "purchased_quantity", precision = 10, scale = 2)
    private BigDecimal purchasedQuantity;

    @Column(name = "custom_text", length = 255)
    private String customText;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Dòng tự nhập — không trỏ tới nguyên liệu nào, nên không tham gia phép tính dinh dưỡng nào. */
    public boolean isCustom() {
        return ingredientId == null;
    }

    /**
     * Lượng còn phải mua: lượng cần trừ phần đã có và đã mua, không bao giờ âm.
     *
     * <p>Trả về null khi dòng chưa khai lượng cần (dòng tự nhập không ghi số lượng) — ở đó
     * "còn cần bao nhiêu" là một câu hỏi không có dữ liệu để trả lời, và 0 là câu trả lời sai.</p>
     */
    public BigDecimal remainingQuantity() {
        if (quantity == null) {
            return null;
        }
        BigDecimal covered = zeroIfNull(haveQuantity).add(zeroIfNull(purchasedQuantity));
        BigDecimal remaining = quantity.subtract(covered);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public ShoppingListEntity getList() { return list; }
    public void setList(ShoppingListEntity value) { list = value; }
    public String getIngredientId() { return ingredientId; }
    public void setIngredientId(String value) { ingredientId = value; }
    public FoodState getFoodState() { return foodState; }
    public void setFoodState(FoodState value) { foodState = value; }
    public FoodGroup getFoodGroup() { return foodGroup; }
    public void setFoodGroup(FoodGroup value) { foodGroup = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public ShoppingItemState getState() { return state; }
    public void setState(ShoppingItemState value) { state = value; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal value) { quantity = value; }
    public String getUnit() { return unit; }
    public void setUnit(String value) { unit = value; }
    public BigDecimal getHaveQuantity() { return haveQuantity; }
    public void setHaveQuantity(BigDecimal value) { haveQuantity = value; }
    public BigDecimal getPurchasedQuantity() { return purchasedQuantity; }
    public void setPurchasedQuantity(BigDecimal value) { purchasedQuantity = value; }
    public String getCustomText() { return customText; }
    public void setCustomText(String value) { customText = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
