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
     * Phần còn lại của các <strong>giới hạn thực phẩm theo tuần</strong>, tại thời điểm bắt đầu
     * một ngày (nhánh 9).
     *
     * <p>Đây là điều kiện cứng duy nhất của module <strong>không</strong> đánh giá được trong
     * phạm vi một ngày: FDA/EPA cho tần suất cá theo tuần, nên một ngày ăn cá không sai gì cả còn
     * bảy ngày liền thì có. Vì vậy mức đã dùng của các ngày trước đi cùng đối tượng này sang ngày
     * sau, và bộ sinh kiểm {@code đã dùng + đang dựng + món sắp thêm} chứ không kiểm riêng ngày
     * đang làm.</p>
     *
     * <p>Lượng của một món tính <strong>trước</strong> khi vào vòng lặp và nằm sẵn trong
     * {@link Entry#gramPerServingByRecipe()}: bộ sinh mở rộng hàng nghìn tổ hợp, và quy đổi lại
     * gram nguyên liệu ở mỗi lần mở rộng là trả giá cho cùng một phép tính nhiều lần. Quy đổi đó
     * đi qua {@code NutritionCalculator} như mọi lượng nguyên liệu khác (bất biến 4).</p>
     *
     * <p>Nhóm nào không có món nào trong kế hoạch thì vẫn ở đây với {@code consumedGram = 0} —
     * nhờ vậy phản hồi nói được "đã kiểm nhóm này, dùng 0 g" thay vì im lặng.</p>
     */
    public record WeeklyAllowance(List<Entry> entries) {

        public WeeklyAllowance {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        /** Không có giới hạn tuần nào — đường dùng cho các lượt sinh một ngày của nhánh 5 và 6. */
        public static WeeklyAllowance unlimited() {
            return new WeeklyAllowance(List.of());
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /**
         * Một nhóm có giới hạn, kèm phần đã dùng của <strong>cả kế hoạch</strong>.
         *
         * @param gramPerServingByRecipe gram của nhóm này trong <em>một khẩu phần</em> từng món;
         *                               món không có mặt ở đây nghĩa là không chứa nguyên liệu nào
         *                               thuộc nhóm
         */
        public record Entry(String code, String label, BigDecimal maxGram, BigDecimal consumedGram,
                            Map<String, BigDecimal> gramPerServingByRecipe) {

            public Entry {
                gramPerServingByRecipe = gramPerServingByRecipe == null
                        ? Map.of() : Map.copyOf(gramPerServingByRecipe);
            }

            public BigDecimal remainingGram() {
                return maxGram.subtract(consumedGram).max(BigDecimal.ZERO);
            }

            /** Nhóm đã dùng hết mức của tuần. */
            public boolean reached() {
                return consumedGram.compareTo(maxGram) >= 0;
            }

            /** Lượng của nhóm này trong một món ở số phần đã chọn; 0 khi món không thuộc nhóm. */
            public BigDecimal gramOf(String recipeId, BigDecimal servings) {
                BigDecimal perServing = gramPerServingByRecipe.get(recipeId);
                return perServing == null ? BigDecimal.ZERO : perServing.multiply(servings);
            }

            public BigDecimal gramOf(Choice choice) {
                return gramOf(choice.candidate().recipeId(), choice.servings());
            }

            Entry plus(BigDecimal gram) {
                return new Entry(code, label, maxGram, consumedGram.add(gram),
                        gramPerServingByRecipe);
            }
        }

        /**
         * Thêm {@code added} vào phương án đang dựng có vượt nhóm nào không.
         *
         * <p>{@code taken} là các món đã chọn <em>trong ngày đang dựng</em>; phần của các ngày
         * trước đã nằm trong {@link Entry#consumedGram()}. Cộng cả hai mới ra "tổng của cả kế
         * hoạch", và đó mới là thứ giới hạn theo tuần nói tới.</p>
         */
        public boolean allows(List<Choice> taken, Choice added) {
            for (Entry entry : entries) {
                BigDecimal gram = entry.gramOf(added);
                if (gram.signum() == 0) {
                    continue;
                }
                for (Choice choice : taken) {
                    gram = gram.add(entry.gramOf(choice));
                }
                if (entry.consumedGram().add(gram).compareTo(entry.maxGram()) > 0) {
                    return false;
                }
            }
            return true;
        }

        /** Ghi nhận một ngày đã chốt. */
        public WeeklyAllowance consume(List<Choice> choices) {
            if (entries.isEmpty() || choices.isEmpty()) {
                return this;
            }
            List<Entry> next = new ArrayList<>();
            for (Entry entry : entries) {
                BigDecimal gram = BigDecimal.ZERO;
                for (Choice choice : choices) {
                    gram = gram.add(entry.gramOf(choice));
                }
                next.add(entry.plus(gram));
            }
            return new WeeklyAllowance(List.copyOf(next));
        }

        /** Ghi nhận một mục đã nằm sẵn trong kế hoạch đã lưu (nhánh 6 sửa kế hoạch nhiều ngày). */
        public WeeklyAllowance consume(String recipeId, BigDecimal servings) {
            if (entries.isEmpty()) {
                return this;
            }
            List<Entry> next = new ArrayList<>();
            for (Entry entry : entries) {
                next.add(entry.plus(entry.gramOf(recipeId, servings)));
            }
            return new WeeklyAllowance(List.copyOf(next));
        }

        /** Nhóm vượt mức sau khi đã ghi nhận hết; rỗng nghĩa là cả kế hoạch còn trong giới hạn. */
        public List<Entry> exceeded() {
            return entries.stream()
                    .filter(entry -> entry.consumedGram().compareTo(entry.maxGram()) > 0)
                    .toList();
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
