package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.nutrimom.nutrition.domain.MealSlotEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.PlanCriterion;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.TemplateMode;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;

/**
 * Các kiểu dữ liệu đi vào và đi ra khỏi {@link MealPlanGenerator}.
 *
 * <p>Gom vào một chỗ vì chúng chỉ có nghĩa cùng nhau, và vì tất cả đều là <strong>ảnh chụp</strong>
 * — không entity JPA, không truy vấn nào trong vòng lặp tìm kiếm. Nhờ vậy bộ sinh kiểm được bằng
 * test thuần: dựng vài {@link Candidate} bằng tay là đủ dựng lại TC18, TC19 và TC23 mà không cần
 * database.</p>
 */
public final class MealPlanSearch {

    private MealPlanSearch() {
    }

    /**
     * Một bữa đã chọn xong cấu trúc, chưa chọn món.
     *
     * <p>"Tạo cấu trúc trước, chọn món sau": {@link #positions()} sinh ra từ template của bữa, nên
     * một bữa luôn là một món hoàn chỉnh <em>hoặc</em> một tập vai trò, không bao giờ trộn hai cấu
     * trúc (BR06).</p>
     */
    public record Slot(MealType mealType, short sequence, TemplateMode mode,
                       List<Position> positions) {

        public Slot {
            positions = positions == null ? List.of() : List.copyOf(positions);
        }

        public String templateCode() {
            return MealSlotEntity.templateCode(mealType, mode);
        }

        public List<RecipeRole> roles() {
            return positions.stream().map(Position::role).toList();
        }
    }

    /**
     * Một vị trí theo vai trò trong bữa, kèm ứng viên và tập khẩu phần cho phép.
     *
     * <p>{@code servingOptions} là một <strong>tập hữu hạn</strong> lấy từ template. Bộ sinh không
     * nhân khẩu phần liên tục để ép đủ vi chất — làm thế là bắt người ta ăn ba bát cơm cho đủ sắt.
     * </p>
     */
    public record Position(RecipeRole role, List<BigDecimal> servingOptions,
                           List<Candidate> candidates) {

        public Position {
            servingOptions = servingOptions == null ? List.of() : List.copyOf(servingOptions);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * Một món ứng viên cho một vị trí — <strong>đã qua bước lọc</strong>.
     *
     * <p>Bộ sinh không nhìn thấy món chưa qua lọc: {@link EligibilityFilter} chạy ở lớp trên và chỉ
     * món {@code ELIGIBLE} mới trở thành {@link Candidate} (bất biến 2). Món khóa cũng đi qua đúng
     * đường đó.</p>
     *
     * @param costPerServing chi phí một khẩu phần; null = <strong>chưa quy đổi được giá</strong>,
     *                       không phải 0 đồng
     * @param lockedServings số phần cố định của món khóa; null với món thường
     */
    public record Candidate(RecipeCandidate recipe, RecipeSnapshot snapshot,
                            BigDecimal costPerServing, BigDecimal lockedServings) {

        public boolean locked() {
            return lockedServings != null;
        }

        public String recipeId() {
            return recipe.recipeId();
        }
    }

    /**
     * Mục tiêu và giới hạn của cả ngày.
     *
     * <p>Mọi trường cho phép null, và null luôn nghĩa là <strong>tiêu chí tương ứng không dùng
     * được</strong> — trọng số của nó rơi khỏi hàm mục tiêu, chứ không phải được coi là đã đạt.</p>
     *
     * @param energyKcal         phần năng lượng dự kiến của <em>các bữa được lập</em>, đã nhân với
     *                           tổng tỉ lệ của chúng. Lập trưa và tối thì đây là 60% EER chứ không
     *                           phải 100% — người dùng vẫn ăn các bữa ngoài kế hoạch (TC23)
     * @param micronutrients     phần vi chất tham chiếu, cũng đã nhân tỉ lệ. Giả định kỹ thuật để
     *                           xếp hạng, <strong>không phải mức phân bổ y khoa</strong>
     * @param dayBudgetVnd       ngân sách cả ngày người dùng khai
     * @param maxMinutesPerMeal  giới hạn thời gian của <strong>toàn bữa</strong>, không phải một món
     * @param strictBudget       true thì ngân sách thành điều kiện cứng (BR13)
     * @param strictTime         true thì thời gian thành điều kiện cứng (BR13)
     */
    public record Targets(BigDecimal energyKcal, Map<String, BigDecimal> micronutrients,
                          BigDecimal dayBudgetVnd, Integer maxMinutesPerMeal,
                          boolean strictBudget, boolean strictTime) {

        public Targets {
            micronutrients = micronutrients == null ? Map.of() : Map.copyOf(micronutrients);
        }
    }

    /** Một món đã được đặt vào một vị trí. */
    public record Choice(int slotIndex, RecipeRole role, Candidate candidate, BigDecimal servings) {

        public BigDecimal costVnd() {
            return candidate.costPerServing() == null
                    ? null : candidate.costPerServing().multiply(servings);
        }
    }

    /**
     * Phần phạt của một phương án, mỗi thành phần trong 0–1.
     *
     * <p>null = thành phần đó <strong>không đánh giá được</strong>. Dữ liệu thiếu không được dùng
     * để xác nhận đã đạt mục tiêu, nên nó rơi khỏi phép tổng chứ không thành 0.</p>
     */
    public record Penalties(BigDecimal nutrition, BigDecimal preference, BigDecimal variety,
                            BigDecimal cost, BigDecimal time) {

        public BigDecimal of(PlanCriterion criterion) {
            return switch (criterion) {
                case NUTRITION -> nutrition;
                case PREFERENCE -> preference;
                case VARIETY -> variety;
                case COST -> cost;
                case TIME -> time;
            };
        }

        public Set<PlanCriterion> used() {
            Set<PlanCriterion> used = new LinkedHashSet<>();
            for (PlanCriterion criterion : PlanCriterion.values()) {
                if (of(criterion) != null) {
                    used.add(criterion);
                }
            }
            return used;
        }
    }

    /**
     * Kết quả một lượt tìm kiếm.
     *
     * @param choices      rỗng khi không có phương án hoàn chỉnh nào
     * @param objective    J của phương án được chọn; null khi không có phương án
     * @param searchLimit  true khi lượt tìm dừng vì <strong>hết hạn thời gian</strong>. Nơi gọi
     *                     phải nói rõ là hết thời gian tìm, không được kết luận không tồn tại nghiệm
     * @param elapsedMillis thời gian sinh thực tế, để đối chiếu với mục tiêu 2 giây
     */
    public record Outcome(List<Choice> choices, Penalties penalties, BigDecimal objective,
                          boolean searchLimit, long elapsedMillis) {

        public Outcome {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }

        public boolean complete() {
            return !choices.isEmpty();
        }

        /** Các món của một bữa, theo đúng thứ tự vai trò của template. */
        public List<Choice> choicesOf(int slotIndex) {
            List<Choice> ofSlot = new ArrayList<>();
            for (Choice choice : choices) {
                if (choice.slotIndex() == slotIndex) {
                    ofSlot.add(choice);
                }
            }
            return List.copyOf(ofSlot);
        }
    }
}
