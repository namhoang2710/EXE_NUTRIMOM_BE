package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.MealFeedbackSignal;
import vn.nutrimom.nutrition.domain.MealTemplateEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.PriceEstimateEntity;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.domain.TemplateMode;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.MealTemplateRepository;
import vn.nutrimom.nutrition.repository.PriceEstimateRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;
import vn.nutrimom.nutrition.service.MealPlanSearch.Candidate;
import vn.nutrimom.nutrition.service.MealPlanSearch.Targets;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.RecipeCandidate.IngredientFacts;

/**
 * Nguồn ứng viên dùng chung cho <strong>mọi</strong> đường vào thực đơn: lập mới (nhánh 5), đổi món
 * và tạo lại (nhánh 6).
 *
 * <p>Tách ra khỏi {@link MealPlanService} khi nhánh 6 cần đúng những viên gạch đó. Nếu nhánh 6 chép
 * lại chuỗi <em>lọc → chấm điểm</em> thì sẽ có hai đường sinh ứng viên, và chỉ cần một đường quên
 * một bước lọc là món chứa thành phần dị ứng vào được thực đơn qua nút "Đổi món" dù đường lập mới
 * vẫn chặn nó. Bất biến 2 chỉ giữ được khi có <strong>một</strong> chỗ duy nhất biến công thức
 * thành ứng viên.</p>
 *
 * <p>Lớp này không ghi gì và không biết gì về kế hoạch đã lưu: nó nhận hồ sơ cùng vài giới hạn, rồi
 * trả về ứng viên, mục tiêu và tập khẩu phần. Quyết định dùng chúng ra sao thuộc về nơi gọi.</p>
 */
@Service
public class MealPlanCandidates {

    /** Trần số món duyệt cho MỘT vị trí. Chạm trần thì nói ra bằng {@code SEARCH_LIMIT}. */
    public static final int MAX_CANDIDATE_SCAN = 200;

    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);

    /**
     * Khẩu phần dự phòng khi template chưa khai {@code default_serving_options}.
     *
     * <p>Một phần, đúng một lựa chọn: thà bộ sinh không có gì để xoay còn hơn tự bịa ra một dải
     * khẩu phần rồi nhân lên cho đủ vi chất.</p>
     */
    private static final List<BigDecimal> FALLBACK_SERVINGS = List.of(BigDecimal.ONE);

    private final RecipeRepository recipes;
    private final IngredientRepository ingredients;
    private final PriceEstimateRepository prices;
    private final MealTemplateRepository templates;
    private final EligibilityFilter filter;
    private final RecipeRanker ranker;
    private final RecipeCostEstimator costs;
    private final NutritionCalculator calculator;

    public MealPlanCandidates(RecipeRepository recipes, IngredientRepository ingredients,
                              PriceEstimateRepository prices, MealTemplateRepository templates,
                              EligibilityFilter filter, RecipeRanker ranker,
                              RecipeCostEstimator costs, NutritionCalculator calculator) {
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.prices = prices;
        this.templates = templates;
        this.filter = filter;
        this.ranker = ranker;
        this.costs = costs;
        this.calculator = calculator;
    }

    // =========================================================================================
    // Kiểu dữ liệu
    // =========================================================================================

    /**
     * Bối cảnh của <em>một lượt</em> lập hoặc sửa thực đơn, tách khỏi hình dạng request của bất kỳ
     * endpoint nào.
     *
     * <p>Nhánh 5 dựng từ {@code constraints} của yêu cầu xem trước, nhánh 6 dựng từ hồ sơ ăn uống.
     * Hai nguồn, một kiểu dữ liệu — nên chuỗi lọc và chấm điểm không phải biết ai đang gọi.</p>
     *
     * <p>{@code strictBudget} và {@code strictTime} mặc định tắt: chi phí và thời gian là ưu tiên
     * <strong>mềm</strong> trừ khi người dùng tự bật (BR13).</p>
     *
     * <p>Không phải trường nào ở đây cũng là một "giới hạn": {@code seed} và
     * {@code feedbackByRecipe} là dữ liệu <em>của lượt này</em>, nạp một lần ở nơi gọi rồi đi cùng
     * nhau xuống mọi bước. Gom chúng vào đây thay vì truyền song song là cách giữ cho hai đường
     * chấm điểm của một lượt không bao giờ cầm hai bộ dữ liệu khác nhau.</p>
     *
     * @param feedbackByRecipe phản hồi sau bữa còn hiệu lực của người dùng, nạp <strong>một
     *                         lượt</strong> cho cả lượt lập (xem {@code MealFeedbackService}). Tra
     *                         lại trong vòng lặp chọn ứng viên sẽ là một câu truy vấn cho mỗi vai
     *                         trò của mỗi bữa — và với thực đơn bảy ngày của nhánh 9 thì là hàng
     *                         chục câu giống hệt nhau
     */
    public record PlanLimits(BigDecimal dailyBudgetVnd, Integer maxMinutesPerMeal,
                             boolean strictBudget, boolean strictTime, long seed,
                             Map<String, MealFeedbackSignal> feedbackByRecipe) {

        public PlanLimits {
            feedbackByRecipe = feedbackByRecipe == null ? Map.of() : Map.copyOf(feedbackByRecipe);
        }

        public static PlanLimits none() {
            return new PlanLimits(null, null, false, false, 0L, Map.of());
        }

        /** Giới hạn thời gian chỉ khi người dùng bật; dùng cho bước lọc ở mức món. */
        public Integer strictMaxMinutes() {
            return strictTime ? maxMinutesPerMeal : null;
        }
    }

    /** Danh mục nguyên liệu và giá, nạp một lượt cho cả lượt lập hoặc sửa thực đơn. */
    public record CatalogContext(Map<String, IngredientEntity> ingredientsById,
                                 Map<String, IngredientFacts> facts,
                                 Map<String, PriceEstimateEntity> pricesByIngredient) {
    }

    /** Ứng viên của một vị trí, kèm những gì cần để giải thích khi danh sách rỗng. */
    public record CandidateSearch(List<Candidate> candidates, List<String> blockingCodes,
                                  boolean hitScanLimit, int droppedForMissingPrice) {
    }

    // =========================================================================================
    // Danh mục
    // =========================================================================================

    /**
     * Danh mục nguyên liệu và giá, nạp <strong>một lượt</strong>.
     *
     * <p>Bước lọc phải đi xuống nguyên liệu con của từng dòng công thức; truy vấn từng mã trong
     * vòng lặp sẽ sinh ra hàng trăm câu SELECT cho một lần bấm nút.</p>
     */
    public CatalogContext loadCatalog() {
        Map<String, IngredientEntity> ingredientsById = loadIngredients();
        Map<String, IngredientFacts> facts = ingredientsById.values().stream()
                .collect(Collectors.toMap(IngredientEntity::getId, IngredientFacts::of));
        Map<String, PriceEstimateEntity> pricesByIngredient = prices.findAll().stream()
                .collect(Collectors.toMap(PriceEstimateEntity::getIngredientId,
                        Function.identity()));
        return new CatalogContext(ingredientsById, facts, pricesByIngredient);
    }

    public Map<String, IngredientEntity> loadIngredients() {
        return ingredients.findAll().stream()
                .collect(Collectors.toMap(IngredientEntity::getId, Function.identity()));
    }

    // =========================================================================================
    // Lọc rồi chấm điểm — nối tiếp, một đường duy nhất
    // =========================================================================================

    /**
     * Điều kiện cứng của hồ sơ cho bước lọc.
     *
     * <p>Giới hạn thời gian cứng kiểm được ngay ở mức món: một món đã dài hơn giới hạn của cả bữa
     * thì mọi bữa chứa nó cũng dài hơn, nên loại sớm là đúng và rẻ. <strong>Ngân sách thì
     * không</strong> — nó là giới hạn của cả ngày, và chỉ có nghĩa trên một tổ hợp, nên bộ sinh
     * kiểm nó ở đó.</p>
     */
    public ProfileConstraints hardConstraints(ProfileSnapshot profile, PlanLimits limits) {
        return profile.constraints(limits.strictMaxMinutes(), null);
    }

    /**
     * Xét <strong>một</strong> món theo đúng bộ lọc mà mọi ứng viên khác đi qua.
     *
     * <p>Dùng cho món người dùng khóa (nhánh 5) và cho món người dùng chọn thay thế (nhánh 6): cả
     * hai đều là "món do người dùng chỉ định", và cả hai đều không có đường đi tắt qua bước lọc.</p>
     */
    public EligibilityVerdict judge(RecipeEntity recipe, ProfileSnapshot profile, PlanLimits limits,
                                    CatalogContext catalog) {
        RecipeCandidate candidate = RecipeCandidate.of(recipe, catalog.ingredientsById());
        Optional<BigDecimal> cost = costs.perServing(recipe, catalog.pricesByIngredient());
        return filter.judge(candidate, hardConstraints(profile, limits), catalog.facts(), cost);
    }

    /**
     * Xét một món chỉ theo các điều kiện <strong>bắt buộc</strong> của hồ sơ (nhánh 10).
     *
     * <p>Cùng {@link EligibilityFilter} và cùng đường dựng ứng viên với {@link #judge}, chỉ khác
     * tập điều kiện: {@link ProfileSnapshot#mandatoryConstraints()} bỏ hết ưu tiên mềm, vì câu hỏi
     * ở đây không phải "món này có nên được gợi ý không" mà "một món <em>đã nằm trong kế hoạch</em>
     * có còn dùng được sau khi hồ sơ đổi không".</p>
     *
     * <p>Không truyền chi phí: không có giới hạn ngân sách cứng nào để đối chiếu, nên quy đổi giá
     * ở đây chỉ là việc thừa.</p>
     */
    public EligibilityVerdict judgeMandatory(RecipeEntity recipe, ProfileSnapshot profile,
                                             CatalogContext catalog) {
        RecipeCandidate candidate = RecipeCandidate.of(recipe, catalog.ingredientsById());
        return filter.judge(candidate, profile.mandatoryConstraints(), catalog.facts(),
                Optional.empty());
    }

    /** Ứng viên dựng từ một công thức <strong>đã qua</strong> {@link #judge}. */
    public Candidate candidateOf(RecipeEntity recipe, CatalogContext catalog,
                                 BigDecimal lockedServings) {
        RecipeCandidate candidate = RecipeCandidate.of(recipe, catalog.ingredientsById());
        RecipeSnapshot snapshot = NutritionSnapshots.snapshotOf(recipe, catalog.ingredientsById());
        BigDecimal cost = costs.perServing(recipe, catalog.pricesByIngredient()).orElse(null);
        return new Candidate(candidate, snapshot, cost, lockedServings);
    }

    /**
     * Lọc rồi chấm điểm, <strong>nối tiếp</strong>, lấy ứng viên đúng loại bữa và vai trò.
     *
     * <p>Thứ hạng ở đây là thứ hạng <em>trong một vị trí</em> theo công thức điểm món của nhánh 4.
     * Việc chọn tổ hợp nào cho cả ngày thuộc {@link MealPlanGenerator} và dùng hàm mục tiêu J ở cấp
     * ngày — hai phép chấm điểm khác nhau cho hai câu hỏi khác nhau.</p>
     *
     * @param excludeRecipeId món không muốn thấy trong danh sách; null = không loại ai. Màn hình
     *                        "đổi món" dùng nó để không gợi ý lại chính món đang nằm trong bữa
     */
    public CandidateSearch candidatesFor(ProfileSnapshot profile, PlanLimits limits,
                                         CatalogContext catalog, MealType mealType,
                                         RecipeRole role, BigDecimal share, int maxResults,
                                         String excludeRecipeId) {
        List<RecipeEntity> rows = recipes.search(mealType, role, null, null,
                Limit.of(MAX_CANDIDATE_SCAN + 1));
        boolean hitScanLimit = rows.size() > MAX_CANDIDATE_SCAN;
        if (hitScanLimit) {
            rows = rows.subList(0, MAX_CANDIDATE_SCAN);
        }

        ProfileConstraints constraints = hardConstraints(profile, limits);

        List<Candidate> eligible = new ArrayList<>();
        List<RecipeRanker.Scorable> scorables = new ArrayList<>();
        Map<String, Candidate> byId = new HashMap<>();
        Set<String> blockingCodes = new LinkedHashSet<>();
        int droppedForMissingPrice = 0;

        for (RecipeEntity recipe : rows) {
            if (recipe.getId().equals(excludeRecipeId)) {
                continue;
            }
            RecipeCandidate candidate = RecipeCandidate.of(recipe, catalog.ingredientsById());
            Optional<BigDecimal> cost = costs.perServing(recipe, catalog.pricesByIngredient());
            EligibilityVerdict verdict = filter.judge(candidate, constraints, catalog.facts(), cost);
            if (!verdict.eligible()) {
                verdict.reasons().forEach(reason -> blockingCodes.add(reason.code()));
                continue;
            }
            if (limits.strictBudget() && cost.isEmpty()) {
                // Bật giới hạn ngân sách cứng mà món chưa có giá: KHÔNG coi dữ liệu thiếu là đã
                // đạt giới hạn. Món không vào kế hoạch, và người dùng được mời bổ sung giá hoặc
                // tắt giới hạn — họ chọn, hệ thống không tự nới.
                droppedForMissingPrice++;
                blockingCodes.add(RecommendationReason.STRICT_BUDGET_WITHOUT_PRICE.name());
                continue;
            }
            RecipeSnapshot snapshot = NutritionSnapshots.snapshotOf(recipe,
                    catalog.ingredientsById());
            Candidate planCandidate = new Candidate(candidate, snapshot, cost.orElse(null), null);
            byId.put(recipe.getId(), planCandidate);
            scorables.add(new RecipeRanker.Scorable(candidate,
                    calculator.forPortion(snapshot, BigDecimal.ONE), cost.orElse(null)));
        }

        for (RecipeScore score : ranker.rank(scorables, slotInputs(profile, limits, share))) {
            eligible.add(byId.get(score.recipeId()));
            if (eligible.size() >= maxResults) {
                break;
            }
        }
        return new CandidateSearch(List.copyOf(eligible), List.copyOf(blockingCodes), hitScanLimit,
                droppedForMissingPrice);
    }

    // =========================================================================================
    // Mục tiêu và ngữ cảnh chấm điểm
    // =========================================================================================

    /**
     * Mục tiêu năng lượng và vi chất của <strong>đúng những bữa được lập</strong> (TC23).
     *
     * <p>Chỉ lập trưa và tối thì mục tiêu là 60% mức ngày, <strong>không</strong> chuẩn hóa hai bữa
     * đó lên thành toàn bộ năng lượng ngày: mẹ bầu vẫn ăn sáng và ăn vặt ngoài kế hoạch, và ép hai
     * bữa gánh cả ngày sẽ đẩy khẩu phần của chúng lên quá mức.</p>
     *
     * <p>Ngân sách thì ngược lại, giữ nguyên mức ngày người dùng khai: đó là số tiền họ định tiêu
     * trong ngày, không phải một mức chia theo bữa.</p>
     */
    public Targets targetsOf(ProfileSnapshot profile, PlanLimits limits, BigDecimal shareSum) {
        BigDecimal energyPerDay = profile.energyEstimate().kcalPerDay();
        BigDecimal energy = (energyPerDay == null || shareSum == null)
                ? null : energyPerDay.multiply(shareSum);

        Map<String, BigDecimal> micronutrients = new LinkedHashMap<>();
        if (shareSum != null) {
            for (var target : calculator.rules().micronutrientTargets()) {
                if (target.amountPerDay() == null) {
                    continue;
                }
                micronutrients.put(target.code(), target.amountPerDay().multiply(shareSum));
            }
        }

        return new Targets(energy, micronutrients, limits.dailyBudgetVnd(),
                limits.maxMinutesPerMeal(), limits.strictBudget(), limits.strictTime());
    }

    /**
     * Ngữ cảnh chấm điểm khẩu vị và đa dạng cho bộ sinh.
     *
     * <p>Bốn trường mục tiêu để rỗng là <strong>cố ý</strong>: trong bộ sinh, dinh dưỡng, chi phí và
     * thời gian được đánh giá ở cấp ngày bằng hàm J, nên chấm lại chúng ở cấp món sẽ đếm hai lần
     * cùng một thứ.</p>
     */
    public RankingInputs preferenceInputs(ProfileSnapshot profile, PlanLimits limits) {
        return new RankingInputs(null, Map.of(), null, null,
                profile.favoriteRecipeIds(), limits.feedbackByRecipe(),
                profile.dislikedIngredientIds(),
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), limits.seed());
    }

    /** Ngữ cảnh xếp hạng ứng viên <em>trong một vị trí</em> — công thức điểm món của nhánh 4. */
    public RankingInputs slotInputs(ProfileSnapshot profile, PlanLimits limits, BigDecimal share) {
        BigDecimal budgetPerSlot = (limits.dailyBudgetVnd() == null || share == null)
                ? null : limits.dailyBudgetVnd().multiply(share);
        BigDecimal energyPerSlot = (profile.energyEstimate().kcalPerDay() == null || share == null)
                ? null : profile.energyEstimate().kcalPerDay().multiply(share);

        Map<String, BigDecimal> micronutrients = new LinkedHashMap<>();
        if (share != null) {
            for (var target : calculator.rules().micronutrientTargets()) {
                if (target.amountPerDay() != null) {
                    micronutrients.put(target.code(), target.amountPerDay().multiply(share));
                }
            }
        }
        return new RankingInputs(energyPerSlot, micronutrients, budgetPerSlot,
                limits.maxMinutesPerMeal(), profile.favoriteRecipeIds(),
                limits.feedbackByRecipe(), profile.dislikedIngredientIds(),
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), limits.seed());
    }

    // =========================================================================================
    // Template
    // =========================================================================================

    /**
     * Tỉ lệ năng lượng tham khảo của một bữa.
     *
     * <p>Bữa phụ chia đều cho {@code snack_count} (QĐ-02): ba bữa phụ không được mỗi bữa mang trọn
     * 15% của ngày. Không có template thì trả null, và khi đó nhánh năng lượng bị bỏ hẳn chứ không
     * mượn một tỉ lệ mặc định.</p>
     */
    public BigDecimal shareOf(MealType mealType, short slotCount) {
        Optional<BigDecimal> share = templates.findAll().stream()
                .filter(template -> template.getMealType() == mealType)
                .map(MealTemplateEntity::getRoleTargetShare)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder());
        if (share.isEmpty()) {
            return null;
        }
        return slotCount > 1 ? share.get().divide(new BigDecimal(slotCount), MATH) : share.get();
    }

    public List<MealTemplateEntity> templatesFor(MealType mealType) {
        // Ít vai trò hơn đứng trước: "ưu tiên ít bước" của template bữa sáng, và cũng là cấu trúc
        // dễ hoàn thành hơn khi danh mục còn mỏng.
        return templates.findAll().stream()
                .filter(template -> template.getMealType() == mealType)
                .sorted(Comparator.comparingInt((MealTemplateEntity t) -> t.getRequiredRoles().size())
                        .thenComparing(t -> t.getMode().name()))
                .toList();
    }

    /** Template của đúng cấu trúc một bữa đã lưu đang dùng; rỗng khi danh mục đã bỏ cấu trúc đó. */
    public Optional<MealTemplateEntity> template(MealType mealType, TemplateMode mode) {
        if (mode == null) {
            return Optional.empty();
        }
        return templates.findAll().stream()
                .filter(template -> template.getMealType() == mealType
                        && template.getMode() == mode)
                .findFirst();
    }

    /** Tập khẩu phần cho phép của template, dạng {@code "0.5,1,1.5,2"}. */
    public List<BigDecimal> servingOptions(MealTemplateEntity template) {
        String raw = template == null ? null : template.getDefaultServingOptions();
        if (raw == null || raw.isBlank()) {
            return FALLBACK_SERVINGS;
        }
        List<BigDecimal> options = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                BigDecimal value = new BigDecimal(trimmed);
                if (value.signum() > 0) {
                    options.add(value);
                }
            } catch (NumberFormatException malformedSeed) {
                // Một ô hỏng trong seed không được làm hỏng cả lượt lập thực đơn; bỏ qua ô đó.
            }
        }
        return options.isEmpty() ? FALLBACK_SERVINGS : List.copyOf(options);
    }

    // =========================================================================================
    // Lý do
    // =========================================================================================

    /**
     * TC18: nói rõ <strong>vai trò nào, bữa nào</strong> và <strong>bộ lọc nào</strong> đã chặn.
     *
     * <p>Danh sách mã chặn lấy thẳng từ kết luận của {@link EligibilityFilter} cho đúng các món đã
     * bị loại ở vị trí đó, nên nó luôn đối chiếu được với dữ liệu thật, không phải một câu đoán.</p>
     */
    public ReasonNote roleNoCandidate(MealType mealType, short sequence, RecipeRole role,
                                      CandidateSearch search) {
        String filters = search.blockingCodes().isEmpty()
                ? "danh mục chưa có món nào cho vai trò này"
                : "bộ lọc: " + String.join(", ", search.blockingCodes());
        return ReasonNote.of(RecommendationReason.ROLE_NO_CANDIDATE,
                "vai trò %s của %s (%s)".formatted(role, describeSlot(mealType, sequence), filters));
    }

    public static String describeSlot(MealType mealType, short sequence) {
        return mealType == MealType.SNACK ? "bữa SNACK #" + sequence : "bữa " + mealType;
    }
}
