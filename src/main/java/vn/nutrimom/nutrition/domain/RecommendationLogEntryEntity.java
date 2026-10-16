package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Một món ứng viên trong một lượt gợi ý (bảng {@code app.recommendation_log_entries}, V59).
 *
 * <p>Ghi <strong>cả món bị loại</strong>. Chỉ ghi món thắng thì mất đúng nửa phần giải thích:
 * người dùng hỏi "sao không thấy món cá hồi" và không ai trả lời được.</p>
 *
 * <p>Điểm thành phần null nghĩa là tiêu chí đó <strong>không dùng được</strong> cho món này, không
 * phải 0 điểm. Món không {@link EligibilityStatus#ELIGIBLE} thì không có điểm tổng và không có
 * thứ hạng — bất biến 2, và database cũng khóa điều đó bằng một CHECK.</p>
 */
@Entity
@Table(name = "recommendation_log_entries", schema = "app")
@IdClass(RecommendationLogEntryEntity.Key.class)
public class RecommendationLogEntryEntity {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "log_id", nullable = false)
    private RecommendationLogEntity log;

    @Id
    @Column(name = "recipe_id", nullable = false, length = 64)
    private String recipeId;

    @Column(name = "recipe_version", nullable = false)
    private int recipeVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility", nullable = false, length = 20)
    private EligibilityStatus eligibility;

    @Column(name = "rank")
    private Short rank;

    @Column(name = "score_nutrition", precision = 6, scale = 3)
    private BigDecimal scoreNutrition;

    @Column(name = "score_preference", precision = 6, scale = 3)
    private BigDecimal scorePreference;

    @Column(name = "score_variety", precision = 6, scale = 3)
    private BigDecimal scoreVariety;

    @Column(name = "score_budget", precision = 6, scale = 3)
    private BigDecimal scoreBudget;

    @Column(name = "score_convenience", precision = 6, scale = 3)
    private BigDecimal scoreConvenience;

    @Column(name = "score_total", precision = 6, scale = 3)
    private BigDecimal scoreTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "nutrition_status", nullable = false, length = 20)
    private CoverageStatus nutritionStatus = CoverageStatus.PARTIAL;

    @Column(name = "reason_codes", nullable = false, length = 255)
    private String reasonCodes = "";

    public RecommendationLogEntity getLog() { return log; }
    public void setLog(RecommendationLogEntity value) { log = value; }
    public String getRecipeId() { return recipeId; }
    public void setRecipeId(String value) { recipeId = value; }
    public int getRecipeVersion() { return recipeVersion; }
    public void setRecipeVersion(int value) { recipeVersion = value; }
    public EligibilityStatus getEligibility() { return eligibility; }
    public void setEligibility(EligibilityStatus value) { eligibility = value; }
    public Short getRank() { return rank; }
    public void setRank(Short value) { rank = value; }
    public BigDecimal getScoreNutrition() { return scoreNutrition; }
    public void setScoreNutrition(BigDecimal value) { scoreNutrition = value; }
    public BigDecimal getScorePreference() { return scorePreference; }
    public void setScorePreference(BigDecimal value) { scorePreference = value; }
    public BigDecimal getScoreVariety() { return scoreVariety; }
    public void setScoreVariety(BigDecimal value) { scoreVariety = value; }
    public BigDecimal getScoreBudget() { return scoreBudget; }
    public void setScoreBudget(BigDecimal value) { scoreBudget = value; }
    public BigDecimal getScoreConvenience() { return scoreConvenience; }
    public void setScoreConvenience(BigDecimal value) { scoreConvenience = value; }
    public BigDecimal getScoreTotal() { return scoreTotal; }
    public void setScoreTotal(BigDecimal value) { scoreTotal = value; }
    public CoverageStatus getNutritionStatus() { return nutritionStatus; }
    public void setNutritionStatus(CoverageStatus value) { nutritionStatus = value; }
    public String getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(String value) { reasonCodes = value == null ? "" : value; }

    /** Khóa ghép (lượt gợi ý, món): một món chỉ xuất hiện đúng một lần trong một lượt. */
    public static class Key implements Serializable {
        private String log;
        private String recipeId;

        public Key() {
        }

        public Key(String log, String recipeId) {
            this.log = log;
            this.recipeId = recipeId;
        }

        public String getLog() { return log; }
        public void setLog(String value) { log = value; }
        public String getRecipeId() { return recipeId; }
        public void setRecipeId(String value) { recipeId = value; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key that)) return false;
            return Objects.equals(log, that.log) && Objects.equals(recipeId, that.recipeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(log, recipeId);
        }
    }
}
