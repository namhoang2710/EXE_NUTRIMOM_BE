package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.PlanCriterion;
import vn.nutrimom.nutrition.service.MealPlanSearch.Candidate;
import vn.nutrimom.nutrition.service.MealPlanSearch.Choice;
import vn.nutrimom.nutrition.service.MealPlanSearch.Outcome;
import vn.nutrimom.nutrition.service.MealPlanSearch.Penalties;
import vn.nutrimom.nutrition.service.MealPlanSearch.Position;
import vn.nutrimom.nutrition.service.MealPlanSearch.Slot;
import vn.nutrimom.nutrition.service.MealPlanSearch.Targets;
import vn.nutrimom.nutrition.service.MealPlanSearch.WeeklyAllowance;
import vn.nutrimom.nutrition.service.NutritionSnapshots.Portion;
import vn.nutrimom.nutrition.service.NutritionTotals.NutrientTotal;

/**
 * Ghép món đã lọc thành một thực đơn một ngày bằng <strong>beam search</strong>.
 *
 * <p>Vào đây là các vị trí đã có cấu trúc và đã có ứng viên; ra khỏi đây là một phương án hoàn
 * chỉnh, hoặc lời giải thích vì sao không có. Lớp này <strong>không đọc database, không lọc món và
 * không tự cộng tổng dinh dưỡng</strong>: lọc thuộc {@link EligibilityFilter}, điểm khẩu vị và đa
 * dạng thuộc {@link RecipeRanker}, mọi con số dinh dưỡng đến từ {@link NutritionCalculator} (bất
 * biến 2 và 4).</p>
 *
 * <h2>Tám bước</h2>
 *
 * <p>Bước 1 (đóng băng phiên bản hồ sơ), bước 2 (tạo vị trí, đưa món khóa vào) và bước 3 (lọc,
 * chấm điểm, lấy ứng viên) do {@link MealPlanService} làm trước khi gọi vào đây. Lớp này làm bước
 * 4 đến 7: mở rộng theo khẩu phần hợp lệ, loại phương án vi phạm điều kiện cứng, chấm điểm toàn
 * phương án, giữ beam tốt nhất, rồi tính lại tổng của phương án thắng. Bước 8 — trả kết quả lưu
 * được hay trả lý do — thuộc về {@link MealPlanService}.</p>
 *
 * <h2>Chấm điểm ở cấp ngày, không phải cấp món</h2>
 *
 * <pre>
 * J = 0,40·Nutrition + 0,25·Preference + 0,15·Variety + 0,10·Cost + 0,10·Time
 * </pre>
 *
 * <p>Chọn J <strong>nhỏ hơn</strong>. Dinh dưỡng chỉ đánh giá được ở cấp ngày: một bữa thiếu sắt mà
 * bữa khác bù lại thì cả ngày vẫn ổn, nên không thể cộng điểm từng món rồi gọi đó là điểm của ngày.
 * </p>
 *
 * <p><strong>Tiêu chí không có dữ liệu thì bỏ, trọng số còn lại chuẩn hóa.</strong> Không bao giờ
 * cho 0 và cũng không bao giờ coi là đã đạt — dữ liệu thiếu không được dùng để xác nhận mục tiêu.
 * </p>
 *
 * <h2>Đa dạng cập nhật sau từng lựa chọn</h2>
 *
 * <p>Điểm V của một ứng viên phụ thuộc vào những món <em>đã chọn trong chính phương án đang
 * dựng</em>, nên nó được chấm lại ở mỗi lần mở rộng thay vì lấy từ một danh sách điểm cố định. Thiếu
 * điều này thì một ngày gồm ba lần cùng một món vẫn được chấm như ba món khác nhau.</p>
 *
 * <h2>Hết hạn thời gian</h2>
 *
 * <p>Chạm hạn mà chưa có phương án hoàn chỉnh thì trả về rỗng kèm {@code searchLimit = true}, và
 * nơi gọi phải nói <strong>đã hết thời gian tìm</strong> chứ không được kết luận không tồn tại
 * nghiệm (TC19).</p>
 */
@Service
public class MealPlanGenerator {

    /** Ứng viên mỗi vị trí sau lọc. Tham số khởi đầu, chỉnh lại bằng bộ dữ liệu đồ án. */
    public static final int CANDIDATES_PER_POSITION = 15;

    /** Mở rộng khi thiếu phương án — vẫn trong hạn thời gian. */
    public static final int EXPANDED_CANDIDATES_PER_POSITION = 30;

    /** Số phương án từng phần giữ lại sau mỗi bước. */
    public static final int BEAM_WIDTH = 20;

    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);
    private static final int PENALTY_SCALE = 6;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final NutritionCalculator calculator;
    private final RecipeRanker ranker;

    public MealPlanGenerator(NutritionCalculator calculator, RecipeRanker ranker) {
        this.calculator = calculator;
        this.ranker = ranker;
    }

    /**
     * Tìm thực đơn tốt nhất cho một ngày, không có giới hạn theo tuần nào.
     *
     * @param preferences ngữ cảnh chấm điểm khẩu vị và đa dạng. Lớp này <strong>chỉ lấy P và
     *                    V</strong> từ {@link RecipeRanker}; năng lượng, ngân sách và thời gian
     *                    được đánh giá ở cấp ngày trong chính lớp này, nên nơi gọi để các trường
     *                    tương ứng của {@code preferences} rỗng
     */
    public Outcome generate(List<Slot> slots, Targets targets, RankingInputs preferences,
                            SearchDeadline deadline) {
        return generate(slots, targets, preferences, deadline, WeeklyAllowance.unlimited());
    }

    /**
     * Tìm thực đơn tốt nhất cho <strong>một ngày trong một kế hoạch nhiều ngày</strong>.
     *
     * <p>Hai thứ của nhánh 9 đi vào qua tham số chứ không qua trạng thái của lớp — lớp này không
     * có trạng thái:</p>
     *
     * <ul>
     *   <li>{@code allowance} mang mức đã dùng của <em>các ngày trước</em>, nên điều kiện cứng
     *       theo tuần được kiểm trên tổng của cả kế hoạch chứ không riêng ngày đang dựng;</li>
     *   <li>{@code preferences.weekRecipeCounts()} và {@code recentRecipeIds()} mang lịch sử các
     *       ngày trước, nên điểm đa dạng của ngày này biết những ngày kia đã ăn gì.</li>
     * </ul>
     *
     * <p>{@code deadline} là hạn của <strong>cả kế hoạch</strong>, không phải của riêng ngày này:
     * nơi gọi dựng một {@link SearchDeadline} rồi truyền qua từng ngày, nên bảy ngày chia nhau
     * đúng một ngân sách thời gian.</p>
     */
    public Outcome generate(List<Slot> slots, Targets targets, RankingInputs preferences,
                            SearchDeadline deadline, WeeklyAllowance allowance) {
        List<Step> steps = steps(slots);
        if (steps.isEmpty()) {
            return new Outcome(List.of(), null, null, false, deadline.elapsed().toMillis());
        }

        List<State> beam = List.of(State.empty());
        for (Step step : steps) {
            if (deadline.exceeded()) {
                // Dừng giữa chừng: chưa có phương án hoàn chỉnh nào để trả về.
                return new Outcome(List.of(), null, null, true, deadline.elapsed().toMillis());
            }

            List<State> next = expand(beam, step, targets, preferences, allowance,
                    CANDIDATES_PER_POSITION);
            boolean scarce = next.size() < BEAM_WIDTH
                    && step.position().candidates().size() > CANDIDATES_PER_POSITION;
            if (scarce && !deadline.exceeded()) {
                // "Mở rộng khi thiếu phương án": duyệt thêm ứng viên, không nới điều kiện nào.
                next = expand(beam, step, targets, preferences, allowance,
                        EXPANDED_CANDIDATES_PER_POSITION);
            }
            if (next.isEmpty()) {
                // Không phải hết giờ: mọi tổ hợp đều vi phạm một điều kiện cứng. Nói đúng như vậy.
                return new Outcome(List.of(), null, null, false, deadline.elapsed().toMillis());
            }
            beam = keepBest(next);
        }

        State best = beam.get(0);
        return new Outcome(best.choices(), best.penalties(), best.objective(),
                false, deadline.elapsed().toMillis());
    }

    // =========================================================================================
    // Hàm mục tiêu — phần phải chứng minh được của nhánh, nên để công khai và tách khỏi vòng lặp
    // =========================================================================================

    /**
     * {@code J} của một phương án, chuẩn hóa trên đúng tập tiêu chí dùng được.
     *
     * <pre>
     * J = Σ(w_i · f_i) / Σ(w_i),  i ∈ tập tiêu chí có dữ liệu
     * </pre>
     *
     * @return null khi không tiêu chí nào đánh giá được — lúc đó không có cơ sở nào để nói phương án
     *         này hơn phương án kia, và nơi gọi phải nói ra điều đó
     */
    public BigDecimal objective(Penalties penalties) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (PlanCriterion criterion : PlanCriterion.values()) {
            BigDecimal value = penalties.of(criterion);
            if (value == null) {
                continue;
            }
            weighted = weighted.add(criterion.weight().multiply(value));
            weightSum = weightSum.add(criterion.weight());
        }
        return weightSum.signum() == 0 ? null
                : weighted.divide(weightSum, MATH).setScale(PENALTY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Năm phần phạt của một phương án, mỗi phần chặn vào 0–1.
     *
     * @param averagePreference trung bình điểm P (0–100) của các món đã chọn
     * @param averageVariety    trung bình điểm V (0–100), đã cập nhật theo từng lựa chọn
     */
    public Penalties penalties(List<Choice> choices, Targets targets,
                               BigDecimal averagePreference, BigDecimal averageVariety) {
        if (choices.isEmpty()) {
            return new Penalties(null, null, null, null, null);
        }
        return new Penalties(
                nutritionPenalty(choices, targets),
                fromScore(averagePreference),
                fromScore(averageVariety),
                costPenalty(choices, targets),
                timePenalty(choices, targets));
    }

    /**
     * Tổng dinh dưỡng của một phương án — <strong>đường duy nhất</strong> để có con số dinh dưỡng.
     *
     * <p>Cả lúc chấm điểm trong beam, lúc dựng phản hồi và lúc đọc lại kế hoạch đã lưu đều đi qua
     * đây, nên tổng API trả về không thể lệch với tổng tính từ snapshot của từng mục (bất biến 4).
     * </p>
     */
    public NutritionTotals totalsOf(List<Choice> choices) {
        List<Portion> portions = new ArrayList<>();
        for (Choice choice : choices) {
            portions.add(new Portion(choice.candidate().snapshot(), choice.servings()));
        }
        return calculator.forPortions(portions);
    }

    /**
     * Phần phạt dinh dưỡng: trung bình của <strong>những nhánh đánh giá được</strong>.
     *
     * <pre>
     * E_penalty     = clamp(|năng lượng ngày − EER phần đã lập| / EER phần đã lập, 0, 1)
     * Micro_penalty = trung bình max(0, 1 − lượng đã biết / mục tiêu)   -- chỉ chất đủ dữ liệu
     * </pre>
     *
     * <p>Vi chất chỉ tính trên chất mang {@link CoverageStatus#COMPLETE}: một chất mà vài nguyên
     * liệu chưa khai số liệu thì tổng của nó thấp hơn thực tế, và phạt theo con số đó sẽ đẩy bộ sinh
     * đi chọn thêm món để bù một thiếu hụt <em>của dữ liệu</em> chứ không phải của bữa ăn.</p>
     *
     * @return null khi cả hai nhánh đều không đánh giá được → bỏ hẳn trọng số dinh dưỡng và ghi rõ
     *         giới hạn đó cho người dùng
     */
    private BigDecimal nutritionPenalty(List<Choice> choices, Targets targets) {
        NutritionTotals totals = totalsOf(choices);

        BigDecimal energy = energyPenalty(totals, targets);
        BigDecimal micro = micronutrientPenalty(totals, targets);

        if (energy == null && micro == null) {
            return null;
        }
        if (energy == null) {
            return micro;
        }
        if (micro == null) {
            return energy;
        }
        return energy.add(micro).divide(new BigDecimal("2"), MATH)
                .setScale(PENALTY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal energyPenalty(NutritionTotals totals, Targets targets) {
        BigDecimal target = targets.energyKcal();
        if (target == null || target.signum() <= 0) {
            return null;
        }
        NutrientTotal energy = totals.nutrient(NutritionCalculator.ENERGY).orElse(null);
        if (energy == null || energy.knownValue() == null) {
            return null;
        }
        return clamp(energy.knownValue().subtract(target).abs().divide(target, MATH));
    }

    private BigDecimal micronutrientPenalty(NutritionTotals totals, Targets targets) {
        BigDecimal sum = BigDecimal.ZERO;
        int counted = 0;
        for (var entry : targets.micronutrients().entrySet()) {
            BigDecimal target = entry.getValue();
            if (target == null || target.signum() <= 0) {
                continue;
            }
            NutrientTotal total = totals.nutrient(entry.getKey()).orElse(null);
            if (total == null || total.knownValue() == null
                    || total.coverageStatus() != CoverageStatus.COMPLETE) {
                continue;
            }
            BigDecimal shortfall = BigDecimal.ONE
                    .subtract(total.knownValue().divide(target, MATH))
                    .max(BigDecimal.ZERO);
            sum = sum.add(shortfall);
            counted++;
        }
        return counted == 0 ? null
                : clamp(sum.divide(new BigDecimal(counted), MATH));
    }

    /**
     * {@code clamp(tổng chi phí / ngân sách − 1, 0, 1)}.
     *
     * @return null khi chưa khai ngân sách, <strong>hoặc</strong> khi còn món chưa quy đổi được giá
     *         — một ngày có món chưa có giá thì tổng chi phí là con số thấp hơn thực tế, và khen nó
     *         rẻ là nói dối
     */
    private BigDecimal costPenalty(List<Choice> choices, Targets targets) {
        BigDecimal budget = targets.dayBudgetVnd();
        if (budget == null || budget.signum() <= 0) {
            return null;
        }
        BigDecimal total = totalCost(choices);
        return total == null ? null : clamp(total.divide(budget, MATH).subtract(BigDecimal.ONE));
    }

    /** Tổng chi phí của phương án; null khi có <strong>bất kỳ</strong> món nào chưa có giá. */
    public BigDecimal totalCost(List<Choice> choices) {
        BigDecimal total = BigDecimal.ZERO;
        for (Choice choice : choices) {
            BigDecimal cost = choice.costVnd();
            if (cost == null) {
                return null;
            }
            total = total.add(cost);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Trung bình {@code clamp(thời gian toàn bữa / giới hạn − 1, 0, 1)} trên các bữa.
     *
     * <p>Thời gian một bữa là <strong>tổng</strong> thời gian các món: MVP ước tính bảo thủ, chưa
     * giả định các bước nấu chạy song song. Giới hạn kiểm ở mức toàn bữa chứ không phải từng món.
     * </p>
     */
    private BigDecimal timePenalty(List<Choice> choices, Targets targets) {
        Integer limit = targets.maxMinutesPerMeal();
        if (limit == null || limit <= 0) {
            return null;
        }
        Map<Integer, Integer> minutesBySlot = minutesBySlot(choices);
        BigDecimal sum = BigDecimal.ZERO;
        for (int minutes : minutesBySlot.values()) {
            sum = sum.add(clamp(new BigDecimal(minutes)
                    .divide(new BigDecimal(limit), MATH)
                    .subtract(BigDecimal.ONE)));
        }
        return clamp(sum.divide(new BigDecimal(minutesBySlot.size()), MATH));
    }

    /** Thời gian nấu của từng bữa, cộng dồn theo món (ước tính bảo thủ). */
    public Map<Integer, Integer> minutesBySlot(List<Choice> choices) {
        Map<Integer, Integer> minutes = new LinkedHashMap<>();
        for (Choice choice : choices) {
            minutes.merge(choice.slotIndex(), choice.candidate().recipe().minutes(), Integer::sum);
        }
        return minutes;
    }

    // =========================================================================================
    // Beam search
    // =========================================================================================

    /** Một vị trí cần lấp, kèm bữa chứa nó. */
    private record Step(int slotIndex, Position position) {
    }

    private List<Step> steps(List<Slot> slots) {
        List<Step> steps = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
            List<Position> positions = slots.get(slotIndex).positions();
            for (int i = 0; i < positions.size(); i++) {
                steps.add(new Step(slotIndex, positions.get(i)));
            }
        }
        return steps;
    }

    private List<State> expand(List<State> beam, Step step, Targets targets,
                               RankingInputs preferences, WeeklyAllowance allowance,
                               int candidateLimit) {
        List<Candidate> candidates = step.position().candidates();
        if (candidates.size() > candidateLimit) {
            candidates = candidates.subList(0, candidateLimit);
        }

        List<State> expanded = new ArrayList<>();
        for (State state : beam) {
            for (Candidate candidate : candidates) {
                for (BigDecimal servings : servingsOptions(step.position(), candidate)) {
                    Choice choice = new Choice(step.slotIndex(), step.position().role(), candidate,
                            servings);
                    // Cắt nhánh TRƯỚC khi chấm điểm: một phương án vi phạm điều kiện cứng không
                    // bao giờ được chấm điểm, và chấm nó cũng chỉ tốn công vô ích.
                    if (!satisfiesHardLimits(state, choice, targets, allowance)) {
                        continue;
                    }
                    expanded.add(state.with(choice, preferences, ranker).scored(this, targets));
                }
            }
        }
        return expanded;
    }

    /** Món khóa giữ nguyên số phần người dùng đã chọn; món thường thử tập khẩu phần của template. */
    private List<BigDecimal> servingsOptions(Position position, Candidate candidate) {
        if (candidate.locked()) {
            return List.of(candidate.lockedServings());
        }
        return position.servingOptions();
    }

    /**
     * Điều kiện cứng kiểm được ngay trong lúc mở rộng.
     *
     * <p>Dị ứng, chế độ ăn bắt buộc, món bị chặn và vai trò bắt buộc đã được xử lý trước khi vào bộ
     * sinh — ứng viên nào tới được đây cũng đã qua {@link EligibilityFilter}. Còn lại hai giới hạn
     * chỉ có nghĩa ở mức tổ hợp: ngân sách tính trên cả ngày, thời gian tính trên toàn bữa.</p>
     *
     * <p>Chi phí và thời gian chỉ tăng khi thêm món, nên vượt ngưỡng ở trạng thái từng phần là đủ để
     * cắt nhánh ngay — không phải chờ tới khi phương án hoàn chỉnh.</p>
     *
     * <p>Giới hạn thực phẩm theo tuần cũng chỉ tăng, nhưng nó đo trên <strong>cả kế hoạch</strong>:
     * {@code allowance} đã mang sẵn phần các ngày trước dùng, nên phép kiểm ở đây là tổng của tuần
     * chứ không phải tổng của ngày (nhánh 9).</p>
     */
    private boolean satisfiesHardLimits(State state, Choice added, Targets targets,
                                        WeeklyAllowance allowance) {
        if (!allowance.allows(state.choices(), added)) {
            return false;
        }
        if (targets.strictBudget() && targets.dayBudgetVnd() != null) {
            BigDecimal before = totalCost(state.choices());
            BigDecimal addedCost = added.costVnd();
            // null = còn món chưa có giá. Lớp trên đã loại những món đó khi bật giới hạn cứng, nên
            // tới đây mà vẫn null thì không có cơ sở khẳng định đã trong ngân sách → loại.
            if (before == null || addedCost == null
                    || before.add(addedCost).compareTo(targets.dayBudgetVnd()) > 0) {
                return false;
            }
        }
        if (targets.strictTime() && targets.maxMinutesPerMeal() != null) {
            int minutes = minutesBySlot(state.choices()).getOrDefault(added.slotIndex(), 0)
                    + added.candidate().recipe().minutes();
            if (minutes > targets.maxMinutesPerMeal()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Giữ lại {@value #BEAM_WIDTH} phương án có J nhỏ nhất.
     *
     * <p>Sắp xếp <strong>ổn định</strong> và chỉ theo J: các phương án bằng điểm giữ nguyên thứ tự
     * sinh ra, mà thứ tự đó đến từ thứ hạng ứng viên của {@link RecipeRanker} — nơi {@code seed} đã
     * tác động. Nhờ vậy cùng seed cho đúng thực đơn cũ, và seed không chạm được vào thứ tự theo
     * điểm.</p>
     */
    private List<State> keepBest(List<State> states) {
        List<State> sorted = new ArrayList<>(states);
        sorted.sort(Comparator.comparing(State::objective,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted.size() > BEAM_WIDTH ? List.copyOf(sorted.subList(0, BEAM_WIDTH))
                : List.copyOf(sorted);
    }

    // =========================================================================================

    private BigDecimal fromScore(BigDecimal score) {
        if (score == null) {
            return null;
        }
        return clamp(BigDecimal.ONE.subtract(score.divide(HUNDRED, MATH)));
    }

    private BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE)
                .setScale(PENALTY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Một phương án đang dựng dở.
     *
     * <p>Mang theo bộ đếm số lần mỗi món đã xuất hiện để điểm đa dạng cập nhật được sau từng lựa
     * chọn, và mang theo tổng điểm P, V để không phải chấm lại các món đã chọn ở mỗi bước.</p>
     */
    private record State(List<Choice> choices, Map<String, Integer> recipeCounts,
                         BigDecimal preferenceSum, BigDecimal varietySum,
                         Penalties penalties, BigDecimal objective) {

        static State empty() {
            return new State(List.of(), Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        }

        State with(Choice choice, RankingInputs preferences, RecipeRanker ranker) {
            Candidate candidate = choice.candidate();
            // Đa dạng nhìn vào CHÍNH phương án đang dựng: số lần món đã xuất hiện ở các vị trí
            // trước, chưa tính vị trí đang xét — đúng ngữ nghĩa weekRecipeCounts của RecipeRanker.
            //
            // Cộng với số lần của CÁC NGÀY TRƯỚC trong cùng kế hoạch (nhánh 9): n_week là "số lần
            // món đã có trong tuần đang tạo", nên thay số của ngày này vào chỗ số của cả tuần sẽ
            // làm một món ăn ba ngày liền chỉ bị phạt như lần đầu.
            Map<String, Integer> weekCounts = mergedCounts(preferences.weekRecipeCounts(),
                    recipeCounts);
            RankingInputs withHistory = new RankingInputs(
                    preferences.energyTargetPerSlot(), preferences.micronutrientTargetsPerSlot(),
                    preferences.budgetPerSlotVnd(), preferences.maxMinutes(),
                    preferences.favoriteRecipeIds(), preferences.feedbackByRecipe(),
                    preferences.dislikedIngredientIds(),
                    preferences.preferredCuisineTags(), preferences.toleranceTags(),
                    preferences.recentRecipeIds(), preferences.yesterdayMainIngredientTags(),
                    weekCounts, preferences.seed());

            // perServing và costVnd để null: lớp này chỉ lấy P và V, còn dinh dưỡng, chi phí và
            // thời gian đều được đánh giá ở cấp ngày. Phản hồi sau bữa thì KHÔNG bỏ qua được —
            // nó nằm trong điểm P, và P là một nửa việc lớp này đang làm.
            RecipeScore score = ranker.score(
                    new RecipeRanker.Scorable(candidate.recipe(), null, null), withHistory);

            List<Choice> nextChoices = new ArrayList<>(choices);
            nextChoices.add(choice);

            Map<String, Integer> nextCounts = new HashMap<>(recipeCounts);
            nextCounts.merge(candidate.recipeId(), 1, Integer::sum);

            return new State(List.copyOf(nextChoices), Map.copyOf(nextCounts),
                    preferenceSum.add(score.preference()), varietySum.add(score.variety()),
                    null, null);
        }

        private static Map<String, Integer> mergedCounts(Map<String, Integer> earlierDays,
                                                         Map<String, Integer> today) {
            if (earlierDays.isEmpty()) {
                return today;
            }
            Map<String, Integer> merged = new HashMap<>(earlierDays);
            today.forEach((recipeId, count) -> merged.merge(recipeId, count, Integer::sum));
            return merged;
        }

        State scored(MealPlanGenerator generator, Targets targets) {
            BigDecimal count = new BigDecimal(choices.size());
            Penalties scored = generator.penalties(choices, targets,
                    preferenceSum.divide(count, MATH), varietySum.divide(count, MATH));
            return new State(choices, recipeCounts, preferenceSum, varietySum, scored,
                    generator.objective(scored));
        }
    }
}
