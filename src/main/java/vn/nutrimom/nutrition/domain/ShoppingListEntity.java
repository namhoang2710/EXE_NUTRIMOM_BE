package vn.nutrimom.nutrition.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
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
 * Danh sách mua sắm của một thực đơn trong một phạm vi ngày (bảng {@code app.shopping_lists},
 * migration V63).
 *
 * <p><strong>Lượng cần không sống ở đây.</strong> Nó là hàm thuần của các mục kế hoạch và được
 * dựng lại mỗi lần {@link #getPlanVersion()} lệch với {@code plan_version} của kế hoạch. Thứ bảng
 * này giữ là phần <em>không</em> dựng lại được: người dùng đã tick những gì, đã mua bao nhiêu, đã
 * thêm dòng tự nhập nào.</p>
 *
 * <p>Vì vậy {@code plan_version} ở đây chính là "phiên bản danh sách mua sắm" mà nhánh 6 trả về
 * trong {@code shopping_list_version}: danh sách đổi đúng khi và chỉ khi các mục kế hoạch đổi.</p>
 */
@Entity
@Table(name = "shopping_lists", schema = "app")
public class ShoppingListEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "plan_id", nullable = false, length = 36)
    private String planId;

    @Column(name = "plan_version", nullable = false)
    private int planVersion;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ShoppingListItemEntity> items = new ArrayList<>();

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

    /** Thêm một dòng, giữ hai chiều quan hệ khớp nhau. */
    public void add(ShoppingListItemEntity item) {
        item.setList(this);
        items.add(item);
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getPlanId() { return planId; }
    public void setPlanId(String value) { planId = value; }
    public int getPlanVersion() { return planVersion; }
    public void setPlanVersion(int value) { planVersion = value; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate value) { startDate = value; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate value) { endDate = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public List<ShoppingListItemEntity> getItems() { return items; }
}
