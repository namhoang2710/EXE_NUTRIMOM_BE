package vn.nutrimom.nutrition.service;

import java.util.Set;
import vn.nutrimom.nutrition.domain.AllergyDeclarationStatus;
import vn.nutrimom.nutrition.domain.DietPattern;

/**
 * Những gì {@link EligibilityFilter} cần biết về hồ sơ — <strong>chỉ điều kiện cứng</strong>.
 *
 * <p>Cố ý không mang theo món yêu thích, nguyên liệu không thích hay bất kỳ ưu tiên mềm nào. Bước
 * lọc không được nhìn thấy chúng thì mới không có đường nào để một sở thích vượt qua một điều kiện
 * dị ứng (BR05, TC02). Ưu tiên mềm nằm ở {@link RankingInputs}, dùng sau, trên tập đã lọc.</p>
 *
 * @param allergenIds         thành phần gây dị ứng đã khai; rỗng khi chưa khai hoặc khai là không có
 * @param allergyStatus       để lớp trên biết có được gắn nhãn "đã kiểm tra dị ứng" hay không (BR01)
 * @param dietPattern         null = chưa khai, <strong>không</strong> mặc định thành ăn tạp
 * @param hardExclusionIds    nguyên liệu phải tránh vì lý do không phải dị ứng
 * @param blockedRecipeIds    món người dùng đã bấm "không gợi ý nữa" — loại khỏi ứng viên
 * @param strictMaxMinutes    giới hạn thời gian <strong>cứng</strong>; null = thời gian chỉ là ưu
 *                            tiên mềm, thuộc điểm C (BR13)
 * @param strictBudgetVnd     giới hạn ngân sách bữa <strong>cứng</strong>; null = ngân sách chỉ là
 *                            ưu tiên mềm, thuộc điểm B (BR13)
 */
public record ProfileConstraints(Set<String> allergenIds,
                                 AllergyDeclarationStatus allergyStatus,
                                 DietPattern dietPattern,
                                 Set<String> hardExclusionIds,
                                 Set<String> blockedRecipeIds,
                                 Integer strictMaxMinutes,
                                 java.math.BigDecimal strictBudgetVnd) {

    public ProfileConstraints {
        allergenIds = allergenIds == null ? Set.of() : Set.copyOf(allergenIds);
        hardExclusionIds = hardExclusionIds == null ? Set.of() : Set.copyOf(hardExclusionIds);
        blockedRecipeIds = blockedRecipeIds == null ? Set.of() : Set.copyOf(blockedRecipeIds);
        allergyStatus = allergyStatus == null ? AllergyDeclarationStatus.UNKNOWN : allergyStatus;
    }

    /**
     * Danh sách gợi ý có được gắn nhãn "đã kiểm tra theo dị ứng của bạn" hay không.
     *
     * <p>BR01: hồ sơ {@link AllergyDeclarationStatus#UNKNOWN} vẫn nhận được gợi ý bình thường —
     * không có gì để lọc thì cũng không lọc sai — nhưng nhãn kia là một lời khẳng định, và hệ
     * thống chưa có cơ sở nào để khẳng định.</p>
     */
    public boolean allergyChecked() {
        return allergyStatus != AllergyDeclarationStatus.UNKNOWN;
    }
}
