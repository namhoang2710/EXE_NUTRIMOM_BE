package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Bối cảnh của một lượt chấm điểm: mục tiêu của <em>bữa đang xét</em> và lịch sử gần đây.
 *
 * <p>Mọi trường cho phép rỗng hoặc null, và null ở đây luôn nghĩa là <strong>tiêu chí tương ứng
 * không dùng được</strong> — trọng số của nó bị loại khỏi phép tổng, không phải cho 0 điểm.</p>
 *
 * @param energyTargetPerSlot        {@code e_slot}: phần năng lượng dự kiến của bữa. null khi
 *                                   người dùng chưa khai đủ thông tin ước tính năng lượng (TC05) —
 *                                   lúc đó nhánh năng lượng của điểm N bị bỏ, không mượn 2.000 kcal
 * @param micronutrientTargetsPerSlot {@code t_k}: phần vi chất tham chiếu của bữa, theo mã chất.
 *                                   Đây là <strong>giả định kỹ thuật để xếp hạng</strong>, không
 *                                   phải mức phân bổ y khoa cho một bữa
 * @param budgetPerSlotVnd           {@code budget_slot}. null = người dùng bỏ qua ngân sách → bỏ
 *                                   hẳn điểm B (TC06)
 * @param maxMinutes                 giới hạn thời gian nấu <em>mềm</em>. null = bỏ hẳn điểm C
 * @param favoriteRecipeIds          món đã đánh dấu yêu thích
 * @param dislikedIngredientIds      nguyên liệu không thích — ưu tiên <strong>mềm</strong>, hạ
 *                                   điểm P chứ không loại món
 * @param preferredCuisineTags       <strong>chưa có nguồn dữ liệu</strong>; xem ghi chú ở
 *                                   {@link RecipeRanker}
 * @param toleranceTags              mùi và kết cấu người dùng dung nạp; cũng chưa đối chiếu được
 *                                   vì công thức trong danh mục chưa mang nhãn nào
 * @param recentRecipeIds            món đã có trong <strong>hai ngày gần đây</strong>
 * @param yesterdayMainIngredientTags nguyên liệu chính của <strong>ngày hôm trước</strong>
 * @param weekRecipeCounts           số lần món đã xuất hiện trong tuần đang tạo,
 *                                   <strong>không tính lần đang xét</strong>
 * @param seed                       seed của nút "Tạo lại"; 0 = thứ tự chuẩn. Xem
 *                                   {@link RecipeRanker} về cách seed chỉ chạm tới các món bằng điểm
 */
public record RankingInputs(BigDecimal energyTargetPerSlot,
                            Map<String, BigDecimal> micronutrientTargetsPerSlot,
                            BigDecimal budgetPerSlotVnd,
                            Integer maxMinutes,
                            Set<String> favoriteRecipeIds,
                            Set<String> dislikedIngredientIds,
                            Set<String> preferredCuisineTags,
                            Set<String> toleranceTags,
                            Set<String> recentRecipeIds,
                            Set<String> yesterdayMainIngredientTags,
                            Map<String, Integer> weekRecipeCounts,
                            long seed) {

    public RankingInputs {
        micronutrientTargetsPerSlot = micronutrientTargetsPerSlot == null
                ? Map.of() : Map.copyOf(micronutrientTargetsPerSlot);
        favoriteRecipeIds = favoriteRecipeIds == null ? Set.of() : Set.copyOf(favoriteRecipeIds);
        dislikedIngredientIds = dislikedIngredientIds == null
                ? Set.of() : Set.copyOf(dislikedIngredientIds);
        preferredCuisineTags = preferredCuisineTags == null
                ? Set.of() : Set.copyOf(preferredCuisineTags);
        toleranceTags = toleranceTags == null ? Set.of() : Set.copyOf(toleranceTags);
        recentRecipeIds = recentRecipeIds == null ? Set.of() : Set.copyOf(recentRecipeIds);
        yesterdayMainIngredientTags = yesterdayMainIngredientTags == null
                ? Set.of() : Set.copyOf(yesterdayMainIngredientTags);
        weekRecipeCounts = weekRecipeCounts == null ? Map.of() : Map.copyOf(weekRecipeCounts);
    }
}
