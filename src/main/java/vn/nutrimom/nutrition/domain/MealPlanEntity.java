package vn.nutrimom.nutrition.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Một thực đơn <strong>đã lưu</strong> (bảng {@code app.meal_plans}, migration V60).
 *
 * <p>Nhánh 5 chỉ lập một ngày, nên {@code endDate} luôn bằng {@code startDate}. Khoảng ngày vẫn là
 * hai cột để nhánh 9 lập ba và bảy ngày trên đúng bảng này.</p>
 *
 * <p>Bốn con số phiên bản ở đây phục vụ bốn việc khác nhau, đừng lẫn: {@code planVersion} là
 * optimistic lock của thao tác sửa (nhánh 6), {@code profileVersion} và {@code rulesVersion} để
 * nhánh 10 biết kế hoạch còn hợp lệ không, còn {@code recipeVersion} nằm ở từng
 * {@link MealPlanItemEntity} để seed sửa món không viết lại lịch sử.</p>
 */
@Entity
@Table(name = "meal_plans", schema = "app")
public class MealPlanEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_status", nullable = false, length = 20)
    private GenerationStatus generationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private PlanLifecycleState lifecycleState = PlanLifecycleState.SAVED;

    @Column(name = "plan_version", nullable = false)
    private int planVersion = 1;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Column(name = "rules_version", nullable = false, length = 64)
    private String rulesVersion;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Các bữa của kế hoạch.
     *
     * <p>Sắp theo {@code local_date}, rồi {@code sequence}: thứ tự ổn định để response và test
     * không phụ thuộc vào thứ tự database trả về. Thứ tự <em>hiển thị</em> theo buổi trong ngày do
     * lớp DTO sắp, vì {@link MealType} xếp theo tên chứ không theo giờ ăn.</p>
     */
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("localDate asc, sequence asc")
    private List<MealSlotEntity> slots = new ArrayList<>();

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

    /** Thêm một bữa, giữ hai chiều quan hệ khớp nhau. */
    public void add(MealSlotEntity slot) {
        slot.setPlan(this);
        slots.add(slot);
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate value) { startDate = value; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate value) { endDate = value; }
    public GenerationStatus getGenerationStatus() { return generationStatus; }
    public void setGenerationStatus(GenerationStatus value) { generationStatus = value; }
    public PlanLifecycleState getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(PlanLifecycleState value) { lifecycleState = value; }
    public int getPlanVersion() { return planVersion; }
    public void setPlanVersion(int value) { planVersion = value; }
    public int getProfileVersion() { return profileVersion; }
    public void setProfileVersion(int value) { profileVersion = value; }
    public String getRulesVersion() { return rulesVersion; }
    public void setRulesVersion(String value) { rulesVersion = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public List<MealSlotEntity> getSlots() { return slots; }
}
