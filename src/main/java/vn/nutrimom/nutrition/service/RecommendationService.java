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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.EligibilityStatus;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.MealFeedbackSignal;
import vn.nutrimom.nutrition.domain.MealTemplateEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.PriceEstimateEntity;
import vn.nutrimom.nutrition.domain.RankingCriterion;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.domain.RecommendationLogEntity;
import vn.nutrimom.nutrition.domain.RecommendationLogEntryEntity;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.domain.RecommendationStatus;
import vn.nutrimom.nutrition.dto.RecommendationDtos.ExcludedRecipe;
import vn.nutrimom.nutrition.dto.RecommendationDtos.MealRecommendation;
import vn.nutrimom.nutrition.dto.RecommendationDtos.ReasonView;
import vn.nutrimom.nutrition.dto.RecommendationDtos.RecommendationRequest;
import vn.nutrimom.nutrition.dto.RecommendationDtos.RecommendationResponse;
import vn.nutrimom.nutrition.dto.RecommendationDtos.RecommendedRecipe;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.MealTemplateRepository;
import vn.nutrimom.nutrition.repository.PriceEstimateRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;
import vn.nutrimom.nutrition.repository.RecommendationLogRepository;
import vn.nutrimom.nutrition.service.RecipeCandidate.IngredientFacts;

/**
 * Điều phối một lượt gợi ý: hồ sơ → lọc → chấm điểm → lý do → ghi nhật ký.
 *
 * <p>Thứ tự trên là <strong>nối tiếp</strong>. Danh sách ứng viên đi qua {@link EligibilityFilter}
 * trước, và chỉ những món {@link EligibilityStatus#ELIGIBLE} mới được đưa sang
 * {@link RecipeRanker} (bất biến 2). Không có đường nào để một món yêu thích đi tắt.</p>
 *
 * <p><strong>Không tự cộng tổng dinh dưỡng</strong> (bất biến 4): mọi con số dinh dưỡng đến từ
 * {@link NutritionCalculator} của nhánh 2, trên snapshot một khẩu phần.</p>
 *
 * <p><strong>Không ghép bữa, không lập kế hoạch.</strong> Lớp này trả về một danh sách món đã xếp
 * hạng cho từng loại bữa; việc chọn ra tổ hợp món của một bữa thuộc {@code PlanGenerator} ở
 * nhánh 5, và chính nó sẽ gọi lại lớp này.</p>
 */
@Service
public class RecommendationService {

    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);

    /**
     * Trần số món duyệt trong một lượt.
     *
     * <p>Chạm trần thì kết quả kèm {@link RecommendationReason#SEARCH_LIMIT} — nói ra, chứ không
     * lặng lẽ cắt bớt rồi để người dùng tưởng danh mục chỉ có bấy nhiêu món.</p>
     */
    private static final int MAX_CANDIDATES = 200;

    private static final int DEFAULT_LIMIT = 5;

    /** Tối đa hai lý do mỗi món (BR15). */
    private static final int MAX_REASONS_PER_RECIPE = 2;

    private final NutritionProfileService profiles;
    private final RecipeRepository recipes;
    private final IngredientRepository ingredients;
    private final PriceEstimateRepository prices;
    private final MealTemplateRepository templates;
    private final RecommendationLogRepository logs;
    private final EligibilityFilter filter;
    private final RecipeRanker ranker;
    private final RecipeCostEstimator costs;
    private final NutritionCalculator calculator;
    private final MealFeedbackService feedbacks;

    public RecommendationService(NutritionProfileService profiles, RecipeRepository recipes,
                                 IngredientRepository ingredients, PriceEstimateRepository prices,
                                 MealTemplateRepository templates, RecommendationLogRepository logs,
                                 EligibilityFilter filter, RecipeRanker ranker,
                                 RecipeCostEstimator costs, NutritionCalculator calculator,
                                 MealFeedbackService feedbacks) {
        this.profiles = profiles;
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.prices = prices;
        this.templates = templates;
        this.logs = logs;
        this.filter = filter;
        this.ranker = ranker;
        this.costs = costs;
        this.calculator = calculator;
        this.feedbacks = feedbacks;
    }

    @Transactional
    public RecommendationResponse recommend(String userId, RecommendationRequest request) {
        ProfileSnapshot profile = profiles.snapshot(userId);
        String rulesVersion = calculator.rules().rulesVersion();
        long seed = request.seed() == null ? 0L : request.seed();

        // Danh mục nguyên liệu nạp MỘT LƯỢT cho cả lượt gợi ý. Bước lọc phải đi xuống nguyên liệu
        // con của từng dòng công thức, và truy vấn từng mã trong vòng lặp sẽ sinh ra hàng trăm câu
        // SELECT cho một lần bấm nút. Danh mục là dữ liệu seed, vài chục dòng.
        Map<String, IngredientEntity> ingredientsById = ingredients.findAll().stream()
                .collect(Collectors.toMap(IngredientEntity::getId, Function.identity()));
        Map<String, IngredientFacts> catalog = ingredientsById.values().stream()
                .collect(Collectors.toMap(IngredientEntity::getId, IngredientFacts::of));

        List<ReasonView> notes = new ArrayList<>();
        if (!profile.allergyChecked()) {
            notes.add(view(ReasonNote.of(RecommendationReason.ALLERGY_STATUS_UNCONFIRMED)));
        }
        if (profile.dietPattern() != null && filter.dietFilterIsCoarse(profile.dietPattern())) {
            notes.add(view(ReasonNote.of(RecommendationReason.DIET_FILTER_COARSE)));
        }

        // Phản hồi sau bữa của nhánh 8, nạp MỘT LƯỢT cho cả lượt gợi ý và đã lọc hạn sẵn. Tra từng
        // món trong vòng lặp sẽ sinh ra hàng trăm câu SELECT cho một lần bấm nút, trong khi một
        // người dùng chỉ có vài chục phản hồi.
        Map<String, MealFeedbackSignal> feedback = feedbacks.latestSignals(userId);

        List<MealRecommendation> meals = new ArrayList<>();
        for (MealType mealType : new TreeSet<>(request.mealTypes())) {
            meals.add(recommendOneMeal(userId, profile, request, mealType, seed, rulesVersion,
                    catalog, ingredientsById, feedback));
        }

        return new RecommendationResponse(profile.profileVersion(), rulesVersion, seed,
                profile.allergyChecked(), List.copyOf(notes), List.copyOf(meals));
    }

    // -------------------------------------------------------------------------------------

    private MealRecommendation recommendOneMeal(String userId, ProfileSnapshot profile,
                                                RecommendationRequest request, MealType mealType,
                                                long seed, String rulesVersion,
                                                Map<String, IngredientFacts> catalog,
                                                Map<String, IngredientEntity> ingredientsById,
                                                Map<String, MealFeedbackSignal> feedback) {
        BigDecimal share = targetShare(mealType, profile);
        BigDecimal budgetPerSlot = perSlot(profile.dailyBudgetVnd(), share);
        BigDecimal energyPerSlot = perSlot(profile.energyEstimate().kcalPerDay(), share);

        ProfileConstraints constraints = profile.constraints(
                request.strictTimeEnabled() ? profile.maxMinutesPerMeal() : null,
                request.strictBudgetEnabled() ? budgetPerSlot : null);

        List<RecipeEntity> rows = recipes.search(mealType, request.role(), null, null,
                Limit.of(MAX_CANDIDATES + 1));
        boolean hitSearchLimit = rows.size() > MAX_CANDIDATES;
        if (hitSearchLimit) {
            rows = rows.subList(0, MAX_CANDIDATES);
        }

        Map<String, PriceEstimateEntity> priceByIngredient = priceIndex(rows);

        // --- Bước 1: lọc ------------------------------------------------------------------
        List<EligibilityVerdict> verdicts = new ArrayList<>();
        Map<String, RecipeEntity> byId = new LinkedHashMap<>();
        Map<String, RecipeCandidate> candidateById = new LinkedHashMap<>();
        Map<String, Optional<BigDecimal>> costById = new HashMap<>();
        for (RecipeEntity recipe : rows) {
            byId.put(recipe.getId(), recipe);
            RecipeCandidate candidate = RecipeCandidate.of(recipe, ingredientsById);
            candidateById.put(recipe.getId(), candidate);
            Optional<BigDecimal> cost = costs.perServing(recipe, priceByIngredient);
            costById.put(recipe.getId(), cost);
            verdicts.add(filter.judge(candidate, constraints, catalog, cost));
        }

        // --- Bước 2: chấm điểm, chỉ trên món đã qua lọc -------------------------------------
        RankingInputs inputs = new RankingInputs(
                energyPerSlot, micronutrientTargets(share), budgetPerSlot,
                profile.maxMinutesPerMeal(), profile.favoriteRecipeIds(),
                // Đúng MỘT phản hồi gần nhất còn hiệu lực cho mỗi món (QĐ-03). Hạn bảy ngày của
                // HARD_NOW đã lọc ở MealFeedbackService, nên lớp chấm điểm không phải biết ngày
                // tháng. Món vắng mặt ở đây = chưa phản hồi bao giờ, hoặc phản hồi cũ đã hết hạn.
                feedback,
                profile.dislikedIngredientIds(),
                // Hai tập này luôn rỗng ở nhánh này: danh mục chưa có nhãn nhóm ẩm thực và nhãn
                // mùi/kết cấu cho công thức. Xem ghi chú ở RecipeRanker — cố ý không suy bừa.
                Set.of(), Set.of(),
                orEmpty(request.recentRecipeIds()), orEmpty(request.yesterdayMainIngredientTags()),
                request.weekRecipeCounts() == null ? Map.of() : request.weekRecipeCounts(),
                seed);

        List<RecipeRanker.Scorable> scorables = new ArrayList<>();
        for (EligibilityVerdict verdict : verdicts) {
            if (!verdict.eligible()) continue;
            RecipeEntity recipe = byId.get(verdict.recipeId());
            scorables.add(new RecipeRanker.Scorable(
                    candidateById.get(verdict.recipeId()),
                    calculator.forPortion(NutritionSnapshots.snapshotOf(recipe, ingredientsById),
                            BigDecimal.ONE),
                    costById.get(recipe.getId()).orElse(null)));
        }
        List<RecipeScore> ranked = ranker.rank(scorables, inputs);

        int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        List<RecipeScore> top = ranked.size() > limit ? ranked.subList(0, limit) : ranked;

        // --- Bước 3: dựng phản hồi và ghi nhật ký -------------------------------------------
        List<ReasonView> notes = new ArrayList<>();
        RecommendationStatus status = top.isEmpty()
                ? RecommendationStatus.NO_MATCH : RecommendationStatus.READY;
        if (status == RecommendationStatus.NO_MATCH) {
            notes.add(view(ReasonNote.of(RecommendationReason.NO_MATCH)));
        }
        if (hitSearchLimit) {
            notes.add(view(ReasonNote.of(RecommendationReason.SEARCH_LIMIT)));
        }
        Set<RankingCriterion> criteria = criteriaUsed(top);
        if (status == RecommendationStatus.READY && criteria.isEmpty()) {
            // Không tiêu chí nào dùng được: danh sách chỉ là thứ tự ổn định của bộ món mẫu. Phải
            // nói ra, nếu không người dùng tưởng nó đã được cá nhân hóa.
            notes.add(view(ReasonNote.of(RecommendationReason.BASIC_SUGGESTION)));
        }

        RecommendationLogEntity log = writeLog(userId, profile, request, mealType, seed,
                rulesVersion, status, notes, criteria, verdicts, ranked, top);

        return new MealRecommendation(mealType, status, List.copyOf(notes),
                criteria.stream().map(RankingCriterion::symbol).sorted().toList(),
                mealCoverage(top), items(top, byId), excluded(verdicts, byId), log.getId());
    }

    private List<RecommendedRecipe> items(List<RecipeScore> top, Map<String, RecipeEntity> byId) {
        List<RecommendedRecipe> items = new ArrayList<>();
        for (RecipeScore score : top) {
            RecipeEntity recipe = byId.get(score.recipeId());
            items.add(new RecommendedRecipe(recipe.getId(), recipe.getName(), recipe.getRole(),
                    recipe.getMinutes(), score.nutritionStatus(), reasonsFor(score, recipe)));
        }
        return List.copyOf(items);
    }

    /**
     * Tối đa hai lý do, và <strong>luôn có ít nhất một</strong>.
     *
     * <p>Dự phòng là thời gian nấu: nó đọc thẳng từ cột {@code minutes} của công thức nên luôn có
     * và luôn đối chiếu được, đúng tinh thần BR15. Không bao giờ rơi về một câu chung chung kiểu
     * "món phù hợp với bạn" — câu đó không dựa trên dữ liệu nào.</p>
     */
    private List<ReasonView> reasonsFor(RecipeScore score, RecipeEntity recipe) {
        Map<String, ReasonView> unique = new LinkedHashMap<>();
        for (ReasonNote note : score.highlights()) {
            if (note.reason().kind() != RecommendationReason.Kind.HIGHLIGHT) continue;
            unique.putIfAbsent(note.code(), view(note));
            if (unique.size() == MAX_REASONS_PER_RECIPE) break;
        }
        if (unique.isEmpty()) {
            ReasonNote fallback = ReasonNote.of(RecommendationReason.QUICK_TO_COOK,
                    String.valueOf(recipe.getMinutes()));
            unique.put(fallback.code(), view(fallback));
        }
        return List.copyOf(unique.values());
    }

    private List<ExcludedRecipe> excluded(List<EligibilityVerdict> verdicts,
                                          Map<String, RecipeEntity> byId) {
        List<ExcludedRecipe> excluded = new ArrayList<>();
        for (EligibilityVerdict verdict : verdicts) {
            if (verdict.eligible()) continue;
            RecipeEntity recipe = byId.get(verdict.recipeId());
            excluded.add(new ExcludedRecipe(recipe.getId(), recipe.getName(), verdict.status(),
                    verdict.reasons().stream().map(this::view).toList()));
        }
        return List.copyOf(excluded);
    }

    /**
     * Độ đầy đủ dữ liệu của cả bữa: PARTIAL ngay khi <strong>một</strong> món gợi ý còn thiếu.
     *
     * <p>Danh sách rỗng cũng là PARTIAL — không có gì để khẳng định thì không khẳng định.</p>
     */
    private CoverageStatus mealCoverage(List<RecipeScore> top) {
        if (top.isEmpty()) return CoverageStatus.PARTIAL;
        boolean complete = top.stream()
                .allMatch(score -> score.nutritionStatus() == CoverageStatus.COMPLETE);
        return CoverageStatus.of(complete);
    }

    private Set<RankingCriterion> criteriaUsed(List<RecipeScore> top) {
        Set<RankingCriterion> used = new LinkedHashSet<>();
        top.forEach(score -> used.addAll(score.criteriaUsed()));
        return used;
    }

    // -------------------------------------------------------------------------------------

    /**
     * Tỉ lệ năng lượng tham khảo của bữa trong ngày.
     *
     * <p>Lấy từ {@code app.meal_templates}. Một loại bữa có thể có nhiều chế độ (một món hoàn
     * chỉnh, hay một tập vai trò) nhưng tỉ lệ của bữa trong ngày <strong>không phụ thuộc</strong>
     * vào cấu trúc được chọn, nên lấy giá trị lớn nhất và coi việc hai chế độ khai lệch nhau là
     * lỗi seed chứ không phải tình huống cần xử lý.</p>
     *
     * <p>Bữa phụ chia đều tỉ lệ cho {@code snack_count} (QĐ-02): ba bữa phụ không được mỗi bữa
     * mang trọn 15% của ngày.</p>
     *
     * <p>Không có template thì trả rỗng — và khi đó cả điểm năng lượng lẫn điểm ngân sách đều
     * không hoạt động, chứ không mượn một tỉ lệ mặc định.</p>
     */
    private BigDecimal targetShare(MealType mealType, ProfileSnapshot profile) {
        Optional<BigDecimal> share = templates.findAll().stream()
                .filter(template -> template.getMealType() == mealType)
                .map(MealTemplateEntity::getRoleTargetShare)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder());
        if (share.isEmpty()) return null;
        if (mealType == MealType.SNACK && profile.snackCount() != null
                && profile.snackCount() > 1) {
            return share.get().divide(new BigDecimal(profile.snackCount()), MATH);
        }
        return share.get();
    }

    private BigDecimal perSlot(Number dailyTotal, BigDecimal share) {
        if (dailyTotal == null || share == null) return null;
        BigDecimal total = dailyTotal instanceof BigDecimal decimal
                ? decimal : new BigDecimal(dailyTotal.toString());
        return total.multiply(share);
    }

    /** Phần vi chất tham chiếu của bữa — giả định kỹ thuật để xếp hạng, không phải mức y khoa. */
    private Map<String, BigDecimal> micronutrientTargets(BigDecimal share) {
        if (share == null) return Map.of();
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        for (var target : calculator.rules().micronutrientTargets()) {
            if (target.amountPerDay() == null) continue;
            targets.put(target.code(), target.amountPerDay().multiply(share));
        }
        return targets;
    }

    private Map<String, PriceEstimateEntity> priceIndex(List<RecipeEntity> rows) {
        List<String> ingredientIds = rows.stream()
                .flatMap(recipe -> recipe.getIngredients().stream())
                .map(line -> line.getIngredientId())
                .distinct()
                .toList();
        if (ingredientIds.isEmpty()) return Map.of();
        return prices.findByIngredientIdIn(ingredientIds).stream()
                .collect(Collectors.toMap(PriceEstimateEntity::getIngredientId,
                        Function.identity()));
    }

    private RecommendationLogEntity writeLog(String userId, ProfileSnapshot profile,
                                             RecommendationRequest request, MealType mealType,
                                             long seed, String rulesVersion,
                                             RecommendationStatus status, List<ReasonView> notes,
                                             Set<RankingCriterion> criteria,
                                             List<EligibilityVerdict> verdicts,
                                             List<RecipeScore> ranked, List<RecipeScore> top) {
        RecommendationLogEntity log = new RecommendationLogEntity();
        log.setUserId(userId);
        log.setRequestId(request.requestId());
        log.setProfileVersion(profile.profileVersion());
        log.setRulesVersion(rulesVersion);
        log.setSeed(seed);
        log.setMealType(mealType);
        log.setStatus(status.name());
        log.setReasonCodes(join(notes.stream().map(ReasonView::code).toList()));
        log.setCriteriaUsed(join(criteria.stream().map(RankingCriterion::symbol).sorted().toList()));

        Map<String, RecipeScore> scoreById = ranked.stream()
                .collect(Collectors.toMap(RecipeScore::recipeId, Function.identity()));
        Map<String, Integer> rankById = new HashMap<>();
        for (int i = 0; i < top.size(); i++) {
            rankById.put(top.get(i).recipeId(), i + 1);
        }

        for (EligibilityVerdict verdict : verdicts) {
            RecommendationLogEntryEntity entry = new RecommendationLogEntryEntity();
            entry.setRecipeId(verdict.recipeId());
            entry.setRecipeVersion(verdict.recipeVersion());
            entry.setEligibility(verdict.status());
            entry.setReasonCodes(join(verdict.reasons().stream().map(ReasonNote::code).toList()));
            entry.setNutritionStatus(CoverageStatus.PARTIAL);

            RecipeScore score = scoreById.get(verdict.recipeId());
            if (score != null) {
                entry.setScoreNutrition(score.nutrition());
                entry.setScorePreference(score.preference());
                entry.setScoreVariety(score.variety());
                entry.setScoreBudget(score.budget());
                entry.setScoreConvenience(score.convenience());
                entry.setScoreTotal(score.total());
                entry.setNutritionStatus(score.nutritionStatus());
                Integer rank = rankById.get(verdict.recipeId());
                entry.setRank(rank == null ? null : rank.shortValue());
                if (entry.getReasonCodes().isBlank()) {
                    entry.setReasonCodes(join(score.highlights().stream()
                            .map(ReasonNote::code).distinct().toList()));
                }
            }
            log.add(entry);
        }
        return logs.saveAndFlush(log);
    }

    private String join(List<String> values) {
        String joined = String.join(",", values);
        // Cột rộng 255: cắt chứ không để cả lượt gợi ý hỏng vì nhật ký quá dài. Nhật ký là thứ
        // phục vụ việc đối chiếu, nó không được phép làm hỏng thứ nó đang ghi lại.
        return joined.length() > 255 ? joined.substring(0, 255) : joined;
    }

    private ReasonView view(ReasonNote note) {
        return new ReasonView(note.code(), note.message());
    }

    private Set<String> orEmpty(Set<String> values) {
        return values == null ? Set.of() : values;
    }
}
