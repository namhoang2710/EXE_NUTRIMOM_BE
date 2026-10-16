package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Dòng đơn ghi phiên bản tập seed đang nằm trong database
 * (bảng {@code app.nutrition_seed_state}, migration V57).
 *
 * <p>Loader so sánh {@code rulesVersion} ở đây với giá trị trong {@code nutritionRules.json} để
 * biết có phải nạp lại hay không. Không có bảng này thì mỗi lần khởi động đều phải ghi đè toàn bộ
 * danh mục.</p>
 */
@Entity
@Table(name = "nutrition_seed_state", schema = "app")
public class NutritionSeedStateEntity {

    /** Luôn là {@value #SINGLE_ROW_ID} — ràng buộc CHECK ở DB chỉ cho phép đúng một dòng. */
    public static final String SINGLE_ROW_ID = "X";

    @Id
    @Column(name = "id", nullable = false, length = 1)
    private String id = SINGLE_ROW_ID;

    @Column(name = "rules_version", nullable = false, length = 64)
    private String rulesVersion;

    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }

    public String getRulesVersion() { return rulesVersion; }
    public void setRulesVersion(String value) { rulesVersion = value; }

    public OffsetDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(OffsetDateTime value) { appliedAt = value; }
}
