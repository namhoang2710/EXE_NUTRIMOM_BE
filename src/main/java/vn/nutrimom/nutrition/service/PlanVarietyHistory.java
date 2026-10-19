package vn.nutrimom.nutrition.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.nutrimom.nutrition.service.MealPlanSearch.Choice;

/**
 * Những gì các ngày <strong>đã lập xong</strong> của một kế hoạch để lại cho ngày đang lập
 * (nhánh 9).
 *
 * <p>Ba thành phần liên ngày của điểm đa dạng đã có sẵn trong công thức {@code V} từ nhánh 4:</p>
 *
 * <pre>
 * V = max(0, 100 − 40·I_recipe − 20·I_mainIngredient − 10·n_week)
 * </pre>
 *
 * <p>Với kế hoạch một ngày cả ba đều rỗng, nên chúng chưa làm gì. Lớp này là chỗ chúng bắt đầu có
 * dữ liệu: {@code I_recipe} đọc món của <strong>hai ngày gần đây</strong>,
 * {@code I_mainIngredient} đọc nguyên liệu chính của <strong>ngày hôm trước</strong>, còn
 * {@code n_week} đếm số lần món đã có trong <strong>tuần đang tạo</strong>.</p>
 *
 * <p>Cập nhật sau <strong>từng ngày</strong>, không phải một bảng tính sẵn cho cả tuần: một danh
 * sách điểm cố định dùng chung cho bảy ngày sẽ cho ra bảy bản sao của cùng một ngày tốt nhất.
 * Trong phạm vi một ngày, {@code MealPlanGenerator} cập nhật tiếp sau từng lựa chọn.</p>
 *
 * <p>Món người dùng <strong>chủ động khóa</strong> không vào lịch sử này: khóa một món ở bữa trưa
 * nghĩa là họ muốn ăn nó mỗi ngày, nên hạ điểm những món còn lại chỉ vì món khóa lặp lại là phạt
 * họ vì chính lựa chọn họ vừa nêu. Nó cũng không sinh ghi chú lặp món.</p>
 */
public record PlanVarietyHistory(List<Set<String>> recipeIdsByDay, Set<String> lastDayTags,
                                 Map<String, Integer> weekRecipeCounts) {

    /** Số ngày gần đây mà một món lặp lại bị trừ trọn 40 điểm. */
    public static final int RECENT_DAYS = 2;

    public PlanVarietyHistory {
        recipeIdsByDay = recipeIdsByDay == null ? List.of() : List.copyOf(recipeIdsByDay);
        lastDayTags = lastDayTags == null ? Set.of() : Set.copyOf(lastDayTags);
        weekRecipeCounts = weekRecipeCounts == null ? Map.of() : Map.copyOf(weekRecipeCounts);
    }

    public static PlanVarietyHistory empty() {
        return new PlanVarietyHistory(List.of(), Set.of(), Map.of());
    }

    /** Món đã có trong hai ngày gần đây — {@code I_recipe} của công thức V. */
    public Set<String> recentRecipeIds() {
        Set<String> recent = new LinkedHashSet<>();
        for (int day = Math.max(0, recipeIdsByDay.size() - RECENT_DAYS);
                day < recipeIdsByDay.size(); day++) {
            recent.addAll(recipeIdsByDay.get(day));
        }
        return recent;
    }

    /** Nguyên liệu chính của ngày hôm trước — {@code I_mainIngredient}. */
    public Set<String> yesterdayMainIngredientTags() {
        return lastDayTags;
    }

    /**
     * Cùng bối cảnh chấm điểm, thêm lịch sử của các ngày trước.
     *
     * <p>Chỉ thay ba trường liên ngày: mọi thứ khác của lượt lập — yêu thích, phản hồi sau bữa,
     * nguyên liệu không thích, seed — phải giữ nguyên, nếu không ngày thứ hai sẽ được chấm theo
     * một hồ sơ khác ngày thứ nhất.</p>
     */
    public RankingInputs applyTo(RankingInputs inputs) {
        return new RankingInputs(inputs.energyTargetPerSlot(),
                inputs.micronutrientTargetsPerSlot(), inputs.budgetPerSlotVnd(),
                inputs.maxMinutes(), inputs.favoriteRecipeIds(), inputs.feedbackByRecipe(),
                inputs.dislikedIngredientIds(), inputs.preferredCuisineTags(),
                inputs.toleranceTags(), recentRecipeIds(), yesterdayMainIngredientTags(),
                weekRecipeCounts, inputs.seed());
    }

    /** Lịch sử sau khi một ngày nữa đã chốt. */
    public PlanVarietyHistory after(List<Choice> choices) {
        Set<String> recipeIds = new LinkedHashSet<>();
        Set<String> tags = new LinkedHashSet<>();
        Map<String, Integer> counts = new HashMap<>(weekRecipeCounts);

        for (Choice choice : choices) {
            if (choice.candidate().locked()) {
                // Mục người dùng chủ động lập lịch lặp: không tính vào lịch sử đa dạng.
                continue;
            }
            recipeIds.add(choice.candidate().recipeId());
            if (choice.candidate().recipe().mainIngredientTag() != null) {
                tags.add(choice.candidate().recipe().mainIngredientTag());
            }
            counts.merge(choice.candidate().recipeId(), 1, Integer::sum);
        }

        List<Set<String>> days = new ArrayList<>(recipeIdsByDay);
        days.add(Set.copyOf(recipeIds));
        return new PlanVarietyHistory(List.copyOf(days), Set.copyOf(tags), Map.copyOf(counts));
    }

    /**
     * Món của ngày vừa lập đã có trong hai ngày gần đây — nguyên liệu của ghi chú lặp món.
     *
     * <p>Gọi <strong>trước</strong> {@link #after}: lịch sử lúc đó còn là của các ngày trước, nên
     * phép giao nói đúng "món này đã ăn gần đây" chứ không phải "món này có trong chính ngày đang
     * xét".</p>
     */
    public Set<String> repeatsOf(List<Choice> choices) {
        Set<String> recent = recentRecipeIds();
        Set<String> repeated = new LinkedHashSet<>();
        for (Choice choice : choices) {
            if (!choice.candidate().locked() && recent.contains(choice.candidate().recipeId())) {
                repeated.add(choice.candidate().recipe().name());
            }
        }
        return repeated;
    }
}
