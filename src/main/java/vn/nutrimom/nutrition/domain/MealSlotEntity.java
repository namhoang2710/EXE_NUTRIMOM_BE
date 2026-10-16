package vn.nutrimom.nutrition.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Một vị trí bữa trong ngày (bảng {@code app.meal_slots}, migration V60).
 *
 * <p>Khoá duy nhất là <strong>bộ bốn</strong> {@code (plan, local_date, meal_type, sequence)} theo
 * QĐ-02. {@code sequence} chỉ lớn hơn 1 với {@link MealType#SNACK}; bữa chính thứ hai cùng loại là
 * lỗi dữ liệu, không phải một bữa nữa.</p>
 */
@Entity
@Table(name = "meal_slots", schema = "app")
public class MealSlotEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private MealPlanEntity plan;

    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 20)
    private MealType mealType;

    /** 1 với bữa chính; 1–3 với bữa phụ (QĐ-02). */
    @Column(name = "sequence", nullable = false)
    private short sequence = 1;

    /**
     * Cấu trúc đã chọn cho bữa, dạng {@code <loại bữa>:<chế độ>} — xem {@link #templateCode()}.
     *
     * <p>Lưu cả chế độ chứ không chỉ danh sách món vì BR06 cấm trộn hai cấu trúc: nhánh 6 phải biết
     * bữa đang là một món hoàn chỉnh hay một tập vai trò mới đổi món đúng vai trò được.</p>
     */
    @Column(name = "template_code", nullable = false, length = 40)
    private String templateCode;

    @OneToMany(mappedBy = "slot", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<MealPlanItemEntity> items = new ArrayList<>();

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** Mã cấu trúc của một cặp (loại bữa, chế độ) — một chỗ duy nhất dựng chuỗi này. */
    public static String templateCode(MealType mealType, TemplateMode mode) {
        return mealType.name() + ":" + mode.name();
    }

    /** Chế độ đọc ngược từ {@code template_code}; rỗng khi mã cũ không đọc được. */
    public TemplateMode mode() {
        if (templateCode == null) {
            return null;
        }
        int separator = templateCode.indexOf(':');
        if (separator < 0) {
            return null;
        }
        try {
            return TemplateMode.valueOf(templateCode.substring(separator + 1));
        } catch (IllegalArgumentException unknownMode) {
            return null;
        }
    }

    /** Thêm một món, giữ hai chiều quan hệ khớp nhau. */
    public void add(MealPlanItemEntity item) {
        item.setSlot(this);
        items.add(item);
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public MealPlanEntity getPlan() { return plan; }
    public void setPlan(MealPlanEntity value) { plan = value; }
    public LocalDate getLocalDate() { return localDate; }
    public void setLocalDate(LocalDate value) { localDate = value; }
    public MealType getMealType() { return mealType; }
    public void setMealType(MealType value) { mealType = value; }
    public short getSequence() { return sequence; }
    public void setSequence(short value) { sequence = value; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String value) { templateCode = value; }
    public List<MealPlanItemEntity> getItems() { return items; }
}
