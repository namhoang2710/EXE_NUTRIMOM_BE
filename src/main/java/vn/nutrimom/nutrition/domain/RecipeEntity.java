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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Một công thức món ăn (bảng {@code app.recipes}, migration V57).
 *
 * <p>{@code version} tăng khi seed sửa công thức. Nhật ký và mục kế hoạch ở các nhánh sau lưu kèm
 * số này trong snapshot, nhờ đó sửa seed <strong>không viết lại lịch sử đã ăn</strong> của người
 * dùng (BR17, TC21).</p>
 *
 * <p>{@code imageKey} là object key trong bucket public media, <strong>không phải URL đầy đủ</strong>:
 * đổi domain CDN thì không phải migrate dữ liệu. DTO tự dựng URL lúc trả về.</p>
 *
 * <p>{@code mainIngredientTag} phục vụ điểm đa dạng ở nhánh 4, kiểu "hôm qua đã ăn gà rồi". Nó
 * <strong>không</strong> dùng cho phát hiện dị ứng — việc đó đi qua {@code ingredient_allergens}
 * và đệ quy nguyên liệu con.</p>
 */
@Entity
@Table(name = "recipes", schema = "app")
public class RecipeEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private RecipeRole role;

    @Column(name = "servings_yield", nullable = false, precision = 6, scale = 2)
    private BigDecimal servingsYield;

    /** Một phần nặng bao nhiêu gram sau khi nấu; null khi chưa cân được. */
    @Column(name = "serving_gram", precision = 8, scale = 2)
    private BigDecimal servingGram;

    @Column(name = "minutes", nullable = false)
    private int minutes;

    @Column(name = "steps", nullable = false, columnDefinition = "TEXT")
    private String steps;

    @Column(name = "safety_notes", columnDefinition = "TEXT")
    private String safetyNotes;

    @Column(name = "image_key", length = 255)
    private String imageKey;

    @Column(name = "main_ingredient_tag", length = 64)
    private String mainIngredientTag;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "recipe_meal_types", schema = "app",
            joinColumns = @JoinColumn(name = "recipe_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 20)
    private Set<MealType> mealTypes = EnumSet.noneOf(MealType.class);

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "recipe_ingredients", schema = "app",
            joinColumns = @JoinColumn(name = "recipe_id"))
    private List<RecipeIngredientValue> ingredients = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String value) { id = value; }

    public String getName() { return name; }
    public void setName(String value) { name = value; }

    public int getVersion() { return version; }
    public void setVersion(int value) { version = value; }

    public RecipeRole getRole() { return role; }
    public void setRole(RecipeRole value) { role = value; }

    public BigDecimal getServingsYield() { return servingsYield; }
    public void setServingsYield(BigDecimal value) { servingsYield = value; }

    public BigDecimal getServingGram() { return servingGram; }
    public void setServingGram(BigDecimal value) { servingGram = value; }

    public int getMinutes() { return minutes; }
    public void setMinutes(int value) { minutes = value; }

    public String getSteps() { return steps; }
    public void setSteps(String value) { steps = value; }

    public String getSafetyNotes() { return safetyNotes; }
    public void setSafetyNotes(String value) { safetyNotes = value; }

    public String getImageKey() { return imageKey; }
    public void setImageKey(String value) { imageKey = value; }

    public String getMainIngredientTag() { return mainIngredientTag; }
    public void setMainIngredientTag(String value) { mainIngredientTag = value; }

    public Set<MealType> getMealTypes() { return mealTypes; }
    public void setMealTypes(Set<MealType> value) {
        mealTypes = value == null || value.isEmpty()
                ? EnumSet.noneOf(MealType.class) : EnumSet.copyOf(value);
    }

    public List<RecipeIngredientValue> getIngredients() { return ingredients; }
    public void setIngredients(List<RecipeIngredientValue> value) {
        ingredients = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
