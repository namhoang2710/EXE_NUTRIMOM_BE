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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Hồ sơ ăn uống của một thai kỳ (bảng {@code app.eating_preferences}, migration V58).
 *
 * <p>{@link #getProfileId()} <strong>chính là</strong> {@code app.pregnancies.id}: nhánh 3 không
 * tạo lại hồ sơ thai kỳ mà gắn phần ăn uống vào hồ sơ đã có từ V4. Mốc thai kỳ (ngày dự sinh, tuổi
 * thai tại ngày ghi nhận) vẫn đọc từ bảng kia, entity này không nhân bản chúng.</p>
 *
 * <p><strong>Gần như mọi trường đều cho phép null</strong> vì bước 3, 4 và 5 của onboarding bỏ qua
 * được. Null ở đây nghĩa là <em>chưa khai</em>, không phải "không giới hạn" và càng không phải 0 —
 * nơi dùng phải tự chọn mặc định của mình thay vì đọc một con số ra khỏi cột trống (bất biến 1).
 * Ngoại lệ là {@link #getAllergyStatus()}: luôn có giá trị, mặc định
 * {@link AllergyDeclarationStatus#UNKNOWN}, vì "chưa khai dị ứng" là một trạng thái cần nói ra chứ
 * không phải một chỗ trống.</p>
 *
 * <p>Năm tập con dùng {@code @ElementCollection}: chúng là giá trị thuộc về hồ sơ, không có vòng
 * đời riêng và không ai tham chiếu tới từng dòng. Cùng lý do như {@code IngredientEntity} ở nhánh
 * 1.</p>
 *
 * <p>{@link #getProfileVersion()} <strong>không</strong> phải {@code @Version} của JPA. Nó không
 * phải optimistic lock mà là con số nghiệp vụ: kế hoạch lưu kèm nó để nhánh 10 biết kế hoạch cũ
 * còn hợp lệ không. Nếu để Hibernate quản thì mọi lần flush đều tăng, kể cả lần chỉ sửa một trường
 * không ảnh hưởng gợi ý, và nhánh 10 sẽ bắt người dùng xem lại thực đơn vì những thay đổi vô
 * hại.</p>
 */
@Entity
@Table(name = "eating_preferences", schema = "app")
public class EatingPreferenceEntity {

    /** Bằng {@code app.pregnancies.id} — hồ sơ ăn uống của thai kỳ nào. */
    @Id
    @Column(name = "profile_id", nullable = false, length = 36)
    private String profileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "allergy_status", nullable = false, length = 20)
    private AllergyDeclarationStatus allergyStatus = AllergyDeclarationStatus.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "diet_pattern", length = 30)
    private DietPattern dietPattern;

    @Column(name = "daily_budget_vnd")
    private Long dailyBudgetVnd;

    @Column(name = "max_minutes_per_meal")
    private Integer maxMinutesPerMeal;

    @Column(name = "snack_count")
    private Short snackCount;

    @Column(name = "age_years")
    private Integer ageYears;

    @Column(name = "height_cm", precision = 5, scale = 2)
    private BigDecimal heightCm;

    @Column(name = "pre_pregnancy_weight_kg", precision = 6, scale = 2)
    private BigDecimal prePregnancyWeightKg;

    @Column(name = "current_weight_kg", precision = 6, scale = 2)
    private BigDecimal currentWeightKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_category", length = 20)
    private ActivityCategory activityCategory;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Thành phần gây dị ứng người dùng đã khai.
     *
     * <p>Rỗng <strong>không</strong> đủ để kết luận "không dị ứng": cả
     * {@link AllergyDeclarationStatus#KNOWN_NONE} lẫn {@link AllergyDeclarationStatus#UNKNOWN} đều
     * đi với tập rỗng. Luôn đọc kèm {@link #getAllergyStatus()}.</p>
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_allergens", schema = "app",
            joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "allergen_id", nullable = false, length = 64)
    private Set<String> allergenIds = new LinkedHashSet<>();

    /**
     * Nguyên liệu phải tránh vì lý do không phải dị ứng — điều kiện <strong>cứng</strong>, không
     * bao giờ nới ra để có kết quả (bất biến 5).
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "eating_preference_hard_exclusions", schema = "app",
            joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "ingredient_id", nullable = false, length = 64)
    private Set<String> hardExclusionIngredientIds = new LinkedHashSet<>();

    /** Nguyên liệu không thích — ưu tiên <strong>mềm</strong>, hạ điểm chứ không loại thẳng. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "eating_preference_disliked_ingredients", schema = "app",
            joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "ingredient_id", nullable = false, length = 64)
    private Set<String> dislikedIngredientIds = new LinkedHashSet<>();

    /** Dung nạp mùi và kết cấu, do người dùng tự mô tả — chuỗi tự do, không có danh mục chuẩn. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "eating_preference_tolerance_tags", schema = "app",
            joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "tag", nullable = false, length = 64)
    private Set<String> toleranceTags = new LinkedHashSet<>();

    /** Loại bữa mặc định cần lập. Rỗng = chưa khai, không phải "không cần bữa nào". */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "eating_preference_meal_types", schema = "app",
            joinColumns = @JoinColumn(name = "profile_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 20)
    private Set<MealType> mealTypes = new LinkedHashSet<>();

    @PrePersist
    void beforeInsert() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getProfileId() { return profileId; }
    public void setProfileId(String value) { profileId = value; }
    public AllergyDeclarationStatus getAllergyStatus() { return allergyStatus; }
    public void setAllergyStatus(AllergyDeclarationStatus value) { allergyStatus = value; }
    public DietPattern getDietPattern() { return dietPattern; }
    public void setDietPattern(DietPattern value) { dietPattern = value; }
    public Long getDailyBudgetVnd() { return dailyBudgetVnd; }
    public void setDailyBudgetVnd(Long value) { dailyBudgetVnd = value; }
    public Integer getMaxMinutesPerMeal() { return maxMinutesPerMeal; }
    public void setMaxMinutesPerMeal(Integer value) { maxMinutesPerMeal = value; }
    public Short getSnackCount() { return snackCount; }
    public void setSnackCount(Short value) { snackCount = value; }
    public Integer getAgeYears() { return ageYears; }
    public void setAgeYears(Integer value) { ageYears = value; }
    public BigDecimal getHeightCm() { return heightCm; }
    public void setHeightCm(BigDecimal value) { heightCm = value; }
    public BigDecimal getPrePregnancyWeightKg() { return prePregnancyWeightKg; }
    public void setPrePregnancyWeightKg(BigDecimal value) { prePregnancyWeightKg = value; }
    public BigDecimal getCurrentWeightKg() { return currentWeightKg; }
    public void setCurrentWeightKg(BigDecimal value) { currentWeightKg = value; }
    public ActivityCategory getActivityCategory() { return activityCategory; }
    public void setActivityCategory(ActivityCategory value) { activityCategory = value; }
    public int getProfileVersion() { return profileVersion; }
    public void setProfileVersion(int value) { profileVersion = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public Set<String> getAllergenIds() { return allergenIds; }
    public Set<String> getHardExclusionIngredientIds() { return hardExclusionIngredientIds; }
    public Set<String> getDislikedIngredientIds() { return dislikedIngredientIds; }
    public Set<String> getToleranceTags() { return toleranceTags; }
    public Set<MealType> getMealTypes() { return mealTypes; }
}
