package vn.nutrimom.nutrition.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Một lượt gợi ý đã chạy (bảng {@code app.recommendation_logs}, migration V59).
 *
 * <p>Tồn tại để <strong>trả lời được câu "vì sao món này đứng trên món kia"</strong>. Cùng
 * {@code profileVersion}, {@code rulesVersion} và {@code seed} trên cùng tập seed sẽ dựng lại đúng
 * thứ hạng cũ — không có bộ ba này thì một lời giải thích đã hiển thị cho người dùng là thứ không
 * ai kiểm chứng được nữa.</p>
 *
 * <p>{@code requestId} chỉ là mã tương quan để tra ngược khi đi tìm lỗi, <strong>không phải
 * idempotency key</strong>: gợi ý là thao tác đọc, gọi lại hai lần không sinh ra thứ gì cần chống
 * trùng.</p>
 */
@Entity
@Table(name = "recommendation_logs", schema = "app")
public class RecommendationLogEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Column(name = "rules_version", nullable = false, length = 64)
    private String rulesVersion;

    @Column(name = "seed", nullable = false)
    private long seed;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 20)
    private MealType mealType;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Mã lý do mức cả lượt, nối bằng dấu phẩy — xem {@link RecommendationReason}. */
    @Column(name = "reason_codes", nullable = false, length = 255)
    private String reasonCodes = "";

    /** Ký hiệu các tiêu chí đã tham gia phép tổng, vd {@code "N,P,V,C"}. */
    @Column(name = "criteria_used", nullable = false, length = 64)
    private String criteriaUsed = "";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "log", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<RecommendationLogEntryEntity> entries = new ArrayList<>();

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Thêm một món ứng viên, giữ hai chiều quan hệ khớp nhau. */
    public void add(RecommendationLogEntryEntity entry) {
        entry.setLog(this);
        entries.add(entry);
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public int getProfileVersion() { return profileVersion; }
    public void setProfileVersion(int value) { profileVersion = value; }
    public String getRulesVersion() { return rulesVersion; }
    public void setRulesVersion(String value) { rulesVersion = value; }
    public long getSeed() { return seed; }
    public void setSeed(long value) { seed = value; }
    public MealType getMealType() { return mealType; }
    public void setMealType(MealType value) { mealType = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(String value) { reasonCodes = value == null ? "" : value; }
    public String getCriteriaUsed() { return criteriaUsed; }
    public void setCriteriaUsed(String value) { criteriaUsed = value == null ? "" : value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public List<RecommendationLogEntryEntity> getEntries() { return entries; }
}
