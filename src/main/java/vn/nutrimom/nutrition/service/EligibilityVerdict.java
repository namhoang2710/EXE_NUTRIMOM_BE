package vn.nutrimom.nutrition.service;

import java.util.List;
import vn.nutrimom.nutrition.domain.EligibilityStatus;

/**
 * Kết luận của bước lọc cho một món, kèm lý do đối chiếu được với dữ liệu.
 *
 * <p>Luôn có <strong>ít nhất một</strong> lý do khi không {@link EligibilityStatus#ELIGIBLE}: một
 * món biến mất khỏi danh sách mà không ai nói được vì sao là đúng thứ nhánh này sinh ra để tránh.</p>
 *
 * @param recipeVersion phiên bản công thức lúc được xét — ghi vào log để kết luận cũ tái lập được
 *                      kể cả sau khi seed sửa món (BR17)
 */
public record EligibilityVerdict(String recipeId, int recipeVersion, EligibilityStatus status,
                                 List<ReasonNote> reasons) {

    public EligibilityVerdict {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean eligible() {
        return status == EligibilityStatus.ELIGIBLE;
    }

    static EligibilityVerdict eligible(RecipeCandidate candidate) {
        return new EligibilityVerdict(candidate.recipeId(), candidate.recipeVersion(),
                EligibilityStatus.ELIGIBLE, List.of());
    }

    static EligibilityVerdict blocked(RecipeCandidate candidate, ReasonNote reason) {
        return new EligibilityVerdict(candidate.recipeId(), candidate.recipeVersion(),
                EligibilityStatus.BLOCKED, List.of(reason));
    }

    static EligibilityVerdict unknown(RecipeCandidate candidate, ReasonNote reason) {
        return new EligibilityVerdict(candidate.recipeId(), candidate.recipeVersion(),
                EligibilityStatus.UNKNOWN, List.of(reason));
    }
}
