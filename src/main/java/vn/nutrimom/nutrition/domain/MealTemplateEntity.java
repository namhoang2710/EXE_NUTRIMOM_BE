package vn.nutrimom.nutrition.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Cấu trúc hợp lệ của một bữa (bảng {@code app.meal_templates}, migration V57).
 *
 * <p>PlanGenerator ở nhánh 5 tạo vị trí theo bảng này <strong>trước khi</strong> chọn món: mỗi bữa
 * là một món hoàn chỉnh ({@link TemplateMode#SINGLE_DISH}) hoặc một tập vai trò
 * ({@link TemplateMode#ROLE_SET}), không trộn ngẫu nhiên (BR06).</p>
 *
 * <p>{@code roleTargetShare} là tỉ lệ năng lượng tham khảo của bữa trong ngày (0.25 = 25%). Đây là
 * <strong>giả định kỹ thuật dùng để xếp hạng, không phải quy tắc y khoa</strong>.</p>
 */
@Entity
@Table(name = "meal_templates", schema = "app")
@IdClass(MealTemplateId.class)
public class MealTemplateEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 20)
    private MealType mealType;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private TemplateMode mode;

    @Column(name = "role_target_share", nullable = false, precision = 4, scale = 3)
    private BigDecimal roleTargetShare;

    @Column(name = "default_serving_options", nullable = false, length = 100)
    private String defaultServingOptions;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "meal_template_roles", schema = "app",
            joinColumns = {
                @JoinColumn(name = "meal_type", referencedColumnName = "meal_type"),
                @JoinColumn(name = "mode", referencedColumnName = "mode")
            })
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Set<RecipeRole> requiredRoles = EnumSet.noneOf(RecipeRole.class);

    public MealType getMealType() { return mealType; }
    public void setMealType(MealType value) { mealType = value; }

    public TemplateMode getMode() { return mode; }
    public void setMode(TemplateMode value) { mode = value; }

    public BigDecimal getRoleTargetShare() { return roleTargetShare; }
    public void setRoleTargetShare(BigDecimal value) { roleTargetShare = value; }

    public String getDefaultServingOptions() { return defaultServingOptions; }
    public void setDefaultServingOptions(String value) { defaultServingOptions = value; }

    public Set<RecipeRole> getRequiredRoles() { return requiredRoles; }
    public void setRequiredRoles(Set<RecipeRole> value) {
        requiredRoles = value == null || value.isEmpty()
                ? EnumSet.noneOf(RecipeRole.class) : EnumSet.copyOf(value);
    }
}
