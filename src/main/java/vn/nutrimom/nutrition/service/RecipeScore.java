package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.RankingCriterion;

/**
 * Điểm của một món, <strong>tách sẵn từng thành phần</strong>.
 *
 * <p>Giữ nguyên năm con số thay vì chỉ giữ tổng là điều kiện để nhật ký gợi ý tái lập được thứ
 * hạng — và để trả lời được câu "vì sao món này đứng trên món kia" bằng dữ liệu chứ bằng trí nhớ.</p>
 *
 * <p>Thành phần null nghĩa là tiêu chí đó <strong>không dùng được</strong> cho món này trong lượt
 * này; trọng số của nó đã bị loại khỏi {@link #total()}. Null không bao giờ là 0 điểm.</p>
 *
 * <p>Các con số ở đây <strong>không ra tới giao diện</strong>: điểm thuật toán giữ ở backend, nếu
 * không nó sẽ bị đọc thành "điểm sức khỏe" của món. Thứ hiển thị là <em>thứ tự</em> và
 * {@link #highlights()}.</p>
 *
 * @param criteriaUsed     đúng những tiêu chí đã tham gia phép tổng
 * @param nutritionStatus  độ đầy đủ của <strong>dữ liệu</strong> vi chất, không phải chất lượng món
 * @param highlights       lý do tích cực, tối đa hai cái sẽ được hiển thị (BR15)
 */
public record RecipeScore(String recipeId, int recipeVersion,
                          BigDecimal nutrition, BigDecimal preference, BigDecimal variety,
                          BigDecimal budget, BigDecimal convenience, BigDecimal total,
                          Set<RankingCriterion> criteriaUsed, CoverageStatus nutritionStatus,
                          List<ReasonNote> highlights) {

    public RecipeScore {
        criteriaUsed = criteriaUsed == null ? Set.of() : Set.copyOf(criteriaUsed);
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }

    /** Điểm của một tiêu chí, hoặc null khi tiêu chí đó không dùng được. */
    public BigDecimal of(RankingCriterion criterion) {
        return switch (criterion) {
            case NUTRITION -> nutrition;
            case PREFERENCE -> preference;
            case VARIETY -> variety;
            case BUDGET -> budget;
            case CONVENIENCE -> convenience;
        };
    }

    /**
     * Số tiêu chí dùng được — tiêu chí phụ để xếp hạng khi hai món bằng điểm.
     *
     * <p>"Ưu tiên dữ liệu đầy đủ hơn": giữa hai món cùng điểm, món được chấm trên nhiều tiêu chí
     * hơn là món hệ thống biết rõ hơn, và nó nên đứng trước.</p>
     */
    public int completeness() {
        return criteriaUsed.size();
    }
}
