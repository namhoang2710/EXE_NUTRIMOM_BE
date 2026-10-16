package vn.nutrimom.nutrition.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Một nguyên liệu chuẩn hoá (bảng {@code app.ingredients}, migration V57).
 *
 * <p>Bốn tập con được ánh xạ bằng {@code @ElementCollection} vì chúng là giá trị thuộc về nguyên
 * liệu, không có vòng đời riêng và không ai tham chiếu tới từng dòng:</p>
 * <ul>
 *   <li>{@code aliases} — tên địa phương của cùng một thứ. Thiếu nó thì lọc dị ứng sẽ sót khi công
 *       thức viết theo cách gọi vùng miền khác với {@code canonicalName}.</li>
 *   <li>{@code allergenIds} — rỗng chỉ có nghĩa "đã rà và không ghi nhận" khi
 *       {@link #getAllergenStatus()} là {@link AllergenStatus#KNOWN}.</li>
 *   <li>{@code componentIngredientIds} — nguyên liệu con của gia vị và nước dùng hỗn hợp.
 *       EligibilityFilter (nhánh 4) phải duyệt <strong>đệ quy</strong> qua đây, nếu không món canh
 *       nấu bằng nước dùng có tôm khô sẽ lọt lưới với người dị ứng tôm (TC01).</li>
 *   <li>{@code nutrients} — giá trị trên 100g, có thể thiếu (xem {@link IngredientNutrientValue}).</li>
 * </ul>
 */
@Entity
@Table(name = "ingredients", schema = "app")
public class IngredientEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "canonical_name", nullable = false, length = 255)
    private String canonicalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "food_state", nullable = false, length = 20)
    private FoodState foodState;

    @Enumerated(EnumType.STRING)
    @Column(name = "food_group", nullable = false, length = 30)
    private FoodGroup foodGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "allergen_status", nullable = false, length = 10)
    private AllergenStatus allergenStatus;

    @Column(name = "source_id", length = 64)
    private String sourceId;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "ingredient_aliases", schema = "app",
            joinColumns = @JoinColumn(name = "ingredient_id"))
    @Column(name = "alias", nullable = false, length = 255)
    private Set<String> aliases = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "ingredient_allergens", schema = "app",
            joinColumns = @JoinColumn(name = "ingredient_id"))
    @Column(name = "allergen_id", nullable = false, length = 64)
    private Set<String> allergenIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "ingredient_components", schema = "app",
            joinColumns = @JoinColumn(name = "ingredient_id"))
    @Column(name = "child_ingredient_id", nullable = false, length = 64)
    private Set<String> componentIngredientIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "ingredient_nutrients", schema = "app",
            joinColumns = @JoinColumn(name = "ingredient_id"))
    private List<IngredientNutrientValue> nutrients = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String value) { id = value; }

    public String getCanonicalName() { return canonicalName; }
    public void setCanonicalName(String value) { canonicalName = value; }

    public FoodState getFoodState() { return foodState; }
    public void setFoodState(FoodState value) { foodState = value; }

    public FoodGroup getFoodGroup() { return foodGroup; }
    public void setFoodGroup(FoodGroup value) { foodGroup = value; }

    public AllergenStatus getAllergenStatus() { return allergenStatus; }
    public void setAllergenStatus(AllergenStatus value) { allergenStatus = value; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String value) { sourceId = value; }

    public Set<String> getAliases() { return aliases; }
    public void setAliases(Set<String> value) {
        aliases = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value);
    }

    public Set<String> getAllergenIds() { return allergenIds; }
    public void setAllergenIds(Set<String> value) {
        allergenIds = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value);
    }

    public Set<String> getComponentIngredientIds() { return componentIngredientIds; }
    public void setComponentIngredientIds(Set<String> value) {
        componentIngredientIds = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value);
    }

    public List<IngredientNutrientValue> getNutrients() { return nutrients; }
    public void setNutrients(List<IngredientNutrientValue> value) {
        nutrients = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
