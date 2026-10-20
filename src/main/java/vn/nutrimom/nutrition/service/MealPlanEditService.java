package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.nutrition.domain.GenerationStatus;
import vn.nutrimom.nutrition.domain.MealFeedbackSignal;
import vn.nutrimom.nutrition.domain.MealPlanEntity;
import vn.nutrimom.nutrition.domain.MealPlanItemEntity;
import vn.nutrimom.nutrition.domain.MealPlanPreviewEntity;
import vn.nutrimom.nutrition.domain.MealSlotEntity;
import vn.nutrimom.nutrition.domain.MealTemplateEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.PlanLifecycleState;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PlanConstraints;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PlanView;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.AlternativeView;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.AlternativesView;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.PatchItemRequest;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.PlanEditView;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.RegenerateRequest;
import vn.nutrimom.nutrition.dto.MealPlanEditDtos.UndoRequest;
import vn.nutrimom.nutrition.dto.RecommendationDtos.ReasonView;
import vn.nutrimom.nutrition.repository.MealPlanPreviewRepository;
import vn.nutrimom.nutrition.repository.MealPlanRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;
import vn.nutrimom.nutrition.service.MealPlanCandidates.CandidateSearch;
import vn.nutrimom.nutrition.service.MealPlanCandidates.CatalogContext;
import vn.nutrimom.nutrition.service.MealPlanCandidates.PlanLimits;
import vn.nutrimom.nutrition.service.MealPlanSearch.Candidate;
import vn.nutrimom.nutrition.service.MealPlanSearch.Choice;
import vn.nutrimom.nutrition.service.MealPlanSearch.Outcome;
import vn.nutrimom.nutrition.service.MealPlanSearch.Position;
import vn.nutrimom.nutrition.service.MealPlanSearch.Slot;
import vn.nutrimom.nutrition.service.MealPlanSearch.Targets;
import vn.nutrimom.nutrition.service.MealPlanSearch.WeeklyAllowance;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.PlanRevalidationService.EatenMeals;
import vn.nutrimom.nutrition.service.PlanRevision.RevisionItem;
import vn.nutrimom.nutrition.service.PlanRevision.RevisionSlot;

/**
 * Sửa một thực đơn đã lưu: đổi món, đổi khẩu phần, khóa món, tạo lại, hoàn tác (nhánh 6).
 *
 * <h2>Một trình tự, bốn lối vào</h2>
 *
 * <p>Mọi thao tác ghi ở đây đi qua đúng năm bước, theo đúng thứ tự:</p>
 * <ol>
 *   <li>khóa dòng kế hoạch và kiểm chủ sở hữu ({@code findForUpdate});</li>
 *   <li>đối chiếu {@code plan_version} <strong>và</strong> {@code profile_version};</li>
 *   <li>cho món mới đi qua {@link MealPlanCandidates} — đúng bộ lọc của lúc lập thực đơn;</li>
 *   <li>áp thay đổi, chụp bản lùi, tính lại tổng và kiểm <strong>toàn</strong> kế hoạch (BR12);</li>
 *   <li>ghi kế hoạch cùng tổng trong <strong>một</strong> transaction.</li>
 * </ol>
 *
 * <p>Không có đường nào ghi kế hoạch mà bỏ qua bước 1 và 2: {@code findForUpdate} là cửa duy nhất
 * lớp này dùng để lấy một kế hoạch để sửa, và nó luôn đi kèm {@link #requireVersions}.</p>
 *
 * <h2>Những thứ lớp này cố ý không làm</h2>
 *
 * <p><strong>Không viết nhật ký</strong> (bất biến 3): mục kế hoạch là phần <em>dự kiến</em>, bữa
 * <em>đã ghi</em> sống ở bảng của nhánh 8. Đổi một món đã ăn chỉ đổi kế hoạch; con số "đã ăn" giữ
 * nguyên món thực tế. Khi nhánh 8 có bảng nhật ký, {@link #pinned} là chỗ duy nhất phải mở rộng để
 * BR11 ("tạo lại không sửa mục đã ăn") thành hiện thực — hôm nay chưa có mục nào "đã ăn" nên nó chỉ
 * xét cờ khóa.</p>
 *
 * <p><strong>Không tự cộng tổng</strong> (bất biến 4): mọi con số dinh dưỡng đi qua
 * {@link NutritionCalculator} thông qua {@link MealPlanService#view}, đúng một bộ tính.</p>
 */
@Service
public class MealPlanEditService {

    /**
     * Bản hoàn tác sống bao lâu.
     *
     * <p>Hoàn tác là thao tác ngay sau khi lỡ tay, không phải một kho lịch sử. Một ngày là thừa đủ
     * cho "sáng sửa, trưa đổi ý"; giữ lâu hơn thì bản khôi phục gần như chắc chắn đã lạc hậu so với
     * hồ sơ, và lúc đó việc đúng là lập lại thực đơn chứ không phải lùi về.</p>
     */
    public static final Duration UNDO_TTL = Duration.ofHours(24);

    /** Số lựa chọn trả cho màn hình đổi món — vừa một danh sách người đọc hết được. */
    private static final int ALTERNATIVES_PER_ITEM = MealPlanGenerator.CANDIDATES_PER_POSITION;

    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);
    private static final int MONEY_SCALE = 2;
    private static final int SERVING_SCALE = 2;

    private final NutritionProfileService profiles;
    private final RecipeRepository recipes;
    private final MealPlanRepository plans;
    private final MealPlanPreviewRepository previews;
    private final MealPlanCandidates candidates;
    private final MealPlanService planViews;
    private final MealPlanGenerator generator;
    private final WeeklyFoodLimits weeklyLimits;
    private final NutritionCalculator calculator;
    private final MealFeedbackService feedbacks;
    private final PlanRevalidationService revalidations;
    private final ObjectMapper json;
    private final Clock clock;

    public MealPlanEditService(NutritionProfileService profiles, RecipeRepository recipes,
                               MealPlanRepository plans, MealPlanPreviewRepository previews,
                               MealPlanCandidates candidates, MealPlanService planViews,
                               MealPlanGenerator generator, WeeklyFoodLimits weeklyLimits,
                               NutritionCalculator calculator, MealFeedbackService feedbacks,
                               PlanRevalidationService revalidations,
                               ObjectMapper json, Clock clock) {
        this.profiles = profiles;
        this.recipes = recipes;
        this.plans = plans;
        this.previews = previews;
        this.candidates = candidates;
        this.planViews = planViews;
        this.generator = generator;
        this.weeklyLimits = weeklyLimits;
        this.calculator = calculator;
        this.feedbacks = feedbacks;
        this.revalidations = revalidations;
        this.json = json;
        this.clock = clock;
    }

    // =========================================================================================
    // GET /api/v1/meal-plans/{planId}/items/{itemId}/alternatives
    // =========================================================================================

    /**
     * Các lựa chọn <strong>cùng bữa, cùng vai trò</strong> cho một mục, kèm tác động của từng cái.
     *
     * <p>Bước 2 của UC02 là "kiểm tra lại hồ sơ": danh sách này dựng từ hồ sơ <em>hiện tại</em>, nên
     * một món đã lọt vào kế hoạch từ trước khi mẹ bầu khai thêm dị ứng sẽ không bao giờ xuất hiện ở
     * đây. Kết quả rỗng vẫn là HTTP 200 — hết món hợp lệ là một kết quả nghiệp vụ, kèm lý do nêu rõ
     * bộ lọc nào đã chặn (TC18).</p>
     */
    @Transactional(readOnly = true)
    public AlternativesView alternatives(String userId, String planId, String itemId) {
        MealPlanEntity plan = plans.findByIdAndUserId(planId, userId)
                .orElseThrow(MealPlanEditService::planNotFound);
        Located located = locate(plan, itemId);
        ProfileSnapshot profile = profiles.snapshot(userId);
        PlanLimits limits = limitsOf(profile, null, 0L);
        CatalogContext catalog = candidates.loadCatalog();

        MealSlotEntity slot = located.slot();
        MealPlanItemEntity item = located.item();
        RecipeEntity current = requireCatalogRecipe(item.getRecipeId());
        MealTemplateEntity template = candidates.template(slot.getMealType(), slot.mode())
                .orElse(null);

        short slotCount = snackCount(plan, slot);
        CandidateSearch search = candidates.candidatesFor(profile, limits, catalog,
                slot.getMealType(), current.getRole(),
                candidates.shareOf(slot.getMealType(), slotCount), ALTERNATIVES_PER_ITEM,
                current.getId());

        List<AlternativeView> alternatives = new ArrayList<>();
        for (Candidate candidate : search.candidates()) {
            alternatives.add(alternativeView(candidate, item, current));
        }

        List<ReasonNote> notes = new ArrayList<>();
        if (search.hitScanLimit()) {
            notes.add(ReasonNote.of(RecommendationReason.SEARCH_LIMIT));
        }
        if (alternatives.isEmpty()) {
            notes.add(ReasonNote.of(RecommendationReason.NO_MATCH));
            notes.add(candidates.roleNoCandidate(slot.getMealType(), slot.getSequence(),
                    current.getRole(), search));
        }

        return new AlternativesView(item.getId(), slot.getMealType(), slot.getSequence(),
                current.getRole(), current.getId(), item.getServings(),
                candidates.servingOptions(template), List.copyOf(alternatives),
                MealPlanService.views(notes));
    }

    /**
     * Tác động của một lựa chọn, tính ở <strong>đúng số phần hiện tại</strong> của mục.
     *
     * <p>Chênh lệch chi phí null khi một trong hai bên chưa quy đổi được giá: trừ một con số cho
     * một khoảng trống rồi gọi kết quả là "không chênh lệch" là nói dối về dữ liệu chưa có.</p>
     */
    private AlternativeView alternativeView(Candidate candidate, MealPlanItemEntity item,
                                            RecipeEntity current) {
        RecipeCandidate recipe = candidate.recipe();
        BigDecimal cost = candidate.costPerServing() == null ? null
                : candidate.costPerServing().multiply(item.getServings())
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal deltaCost = (cost == null || item.getCostSnapshot() == null) ? null
                : cost.subtract(item.getCostSnapshot());
        return new AlternativeView(recipe.recipeId(), recipe.name(), recipe.recipeVersion(),
                recipe.minutes(), candidate.snapshot().servingGram(), cost, deltaCost,
                recipe.minutes() - current.getMinutes());
    }

    // =========================================================================================
    // PATCH /api/v1/meal-plans/{planId}/items/{itemId}
    // =========================================================================================

    /**
     * Đổi món, đổi khẩu phần hoặc khóa một mục — <strong>giữ nguyên mọi mục khác</strong>.
     *
     * <p>Không lưu món rồi mới tính tổng ở một yêu cầu rời: cả hai nằm trong cùng transaction này,
     * nên không có khoảnh khắc nào kế hoạch mang món mới mà tổng còn của món cũ.</p>
     */
    @Transactional
    public PlanEditView patch(String userId, String planId, String itemId,
                              PatchItemRequest request) {
        if (request.changesNothing()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Yêu cầu không nêu thay đổi nào: cần recipe_id, servings, grams hoặc locked.",
                    Map.of("recipe_id", "cần ít nhất một trường thay đổi"));
        }
        if (request.hasBothServingForms()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Chỉ gửi servings hoặc grams, không gửi cả hai.",
                    Map.of("grams", "không gửi cùng lúc với servings"));
        }

        MealPlanEntity plan = requirePlanForUpdate(userId, planId);
        Located located = locate(plan, itemId);
        ProfileSnapshot profile = profiles.snapshot(userId);
        requireVersions(plan, profile, request.expectedPlanVersion(),
                request.expectedProfileVersion());

        PlanLimits limits = limitsOf(profile, null, 0L);
        CatalogContext catalog = candidates.loadCatalog();
        MealSlotEntity slot = located.slot();
        MealPlanItemEntity item = located.item();

        RecipeEntity replacement = replacementFor(request, slot, item, profile, limits, catalog);
        RecipeSnapshot snapshot = replacement == null
                ? readSnapshot(item)
                : NutritionSnapshots.snapshotOf(replacement, catalog.ingredientsById());
        List<BigDecimal> servingOptions = candidates.servingOptions(
                candidates.template(slot.getMealType(), slot.mode()).orElse(null));
        BigDecimal servings = resolveServings(request, item, snapshot, servingOptions);
        requireWeeklyLimits(plan, item, replacement == null ? item.getRecipeId()
                : replacement.getId(), servings, snapshot);

        // Chụp bản lùi TRƯỚC khi chạm vào mục nào — sau khi mọi phép kiểm đã qua, nên một yêu cầu
        // bị từ chối không để lại một bản hoàn tác trỏ về trạng thái chưa hề thay đổi.
        storeRevision(userId, plan);

        if (replacement != null) {
            item.setRecipeId(replacement.getId());
            item.setRecipeVersion(replacement.getVersion());
            item.setNutrientSnapshot(json.writeValueAsString(snapshot));
            // Món mới thì giá cũng mới: lấy từ bảng giá hiện tại, nhân đúng số phần sắp lưu.
            BigDecimal perServing =
                    candidates.candidateOf(replacement, catalog, null).costPerServing();
            item.setCostSnapshot(perServing == null ? null
                    : perServing.multiply(servings).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        } else if (servings.compareTo(item.getServings()) != 0) {
            // Chỉ đổi khẩu phần: giữ nguyên đơn giá đã chụp lúc chọn món, chỉ nhân lại. Đọc giá
            // hôm nay ở đây sẽ làm con số nhảy vì một lý do người dùng không hề thao tác.
            item.setCostSnapshot(scaledCost(item.getCostSnapshot(), item.getServings(), servings));
        }
        item.setServings(servings);
        if (request.locked() != null) {
            item.setLocked(request.locked());
        }

        plan.setPlanVersion(plan.getPlanVersion() + 1);
        return finish(userId, plan, wholePlanNotes(plan, limits), List.of(), true);
    }

    /**
     * Món thay thế đã qua <strong>mọi</strong> phép kiểm, hoặc null khi yêu cầu không đổi món.
     *
     * <p>Gửi lại đúng mã món đang nằm trong bữa không phải là đổi món — giao diện thường gửi cả cụm
     * trường, và coi đó là một lần đổi sẽ viết lại snapshot bằng công thức hôm nay, tức là âm thầm
     * đổi con số dinh dưỡng của một mục mà người dùng chỉ định sửa khẩu phần.</p>
     */
    private RecipeEntity replacementFor(PatchItemRequest request, MealSlotEntity slot,
                                        MealPlanItemEntity item, ProfileSnapshot profile,
                                        PlanLimits limits, CatalogContext catalog) {
        if (request.recipeId() == null) {
            return null;
        }
        if (request.recipeId().equals(item.getRecipeId())) {
            requireMatchingRecipeVersion(request.recipeVersion(), item.getRecipeVersion());
            return null;
        }

        RecipeEntity recipe = recipes.findById(request.recipeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy món này trong danh mục."));
        requireMatchingRecipeVersion(request.recipeVersion(), recipe.getVersion());

        if (!recipe.getMealTypes().contains(slot.getMealType())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Món này không dùng cho bữa " + slot.getMealType() + ".",
                    Map.of("recipe_id", "không thuộc bữa " + slot.getMealType()));
        }
        RecipeRole role = requireCatalogRecipe(item.getRecipeId()).getRole();
        if (recipe.getRole() != role) {
            // BR06: một bữa là một món hoàn chỉnh HOẶC một tập vai trò. Thay món tinh bột bằng món
            // canh là đổi cấu trúc của bữa, không phải đổi một lựa chọn trong cấu trúc đó.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Món thay thế phải cùng vai trò %s với món đang có.".formatted(role),
                    Map.of("recipe_id", "vai trò %s, cần %s".formatted(recipe.getRole(), role)));
        }

        EligibilityVerdict verdict = candidates.judge(recipe, profile, limits, catalog);
        if (!verdict.eligible()) {
            // Món vẫn xem được thông tin ở GET /api/v1/recipes; chỉ nút Thêm là bị chặn, và lý do
            // nói thẳng thành phần nào đang xung đột thay vì "không hợp lệ".
            String why = explain(verdict);
            throw new BusinessException(ErrorCode.RECIPE_NOT_ELIGIBLE, why,
                    Map.of("recipe_id", why));
        }
        return recipe;
    }

    /**
     * Đổi món hoặc tăng khẩu phần <strong>không được đi vòng qua giới hạn theo tuần</strong>.
     *
     * <p>Điều kiện cứng chỉ là điều kiện cứng khi mọi đường vào kế hoạch đều kiểm nó. Bộ sinh chặn
     * đường lập mới và đường tạo lại; nút "Đổi món" là đường thứ ba, và nếu nó không kiểm thì bảy
     * ngày cá hồi chỉ cách người dùng vài lần bấm — lại còn trông như hệ thống đã duyệt.</p>
     *
     * <p>Tính lại trên <strong>toàn kế hoạch</strong> với mục đã thay, chứ không cộng thêm phần
     * chênh: cộng chênh lệch giả định tổng hiện tại đang hợp lệ, mà một kế hoạch lưu từ trước khi
     * seed khai giới hạn này thì không.</p>
     */
    private void requireWeeklyLimits(MealPlanEntity plan, MealPlanItemEntity patched,
                                     String recipeId, BigDecimal servings,
                                     RecipeSnapshot snapshot) {
        if (weeklyLimits.limits().isEmpty()) {
            return;
        }
        List<RecipeSnapshot> snapshots = new ArrayList<>();
        snapshots.add(snapshot);
        for (MealSlotEntity slot : plan.getSlots()) {
            slot.getItems().forEach(item -> snapshots.add(readSnapshot(item)));
        }

        WeeklyAllowance allowance = weeklyLimits.allowanceFor(snapshots);
        for (MealSlotEntity slot : plan.getSlots()) {
            for (MealPlanItemEntity item : slot.getItems()) {
                allowance = item == patched
                        ? allowance.consume(recipeId, servings)
                        : allowance.consume(item.getRecipeId(), item.getServings());
            }
        }

        List<WeeklyAllowance.Entry> exceeded = allowance.exceeded();
        if (!exceeded.isEmpty()) {
            WeeklyAllowance.Entry entry = exceeded.get(0);
            String why = "Thay đổi này đưa %s lên %s g, vượt mức %s g cho cả kế hoạch tuần."
                    .formatted(entry.label(), entry.consumedGram().stripTrailingZeros()
                            .toPlainString(), entry.maxGram().stripTrailingZeros().toPlainString());
            throw new BusinessException(ErrorCode.RECIPE_NOT_ELIGIBLE, why,
                    Map.of("recipe_id", why));
        }
    }

    private void requireMatchingRecipeVersion(Integer sent, int current) {
        if (sent != null && sent != current) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Công thức đã được cập nhật. Hãy tải lại món rồi chọn lại.");
        }
    }

    /**
     * Số phần sắp lưu, đã kiểm BR07.
     *
     * <p>Người dùng chọn trong tập khẩu phần của cấu trúc bữa, hoặc nhập theo gram. Dù đường nào,
     * kết quả phải <strong>&gt; 0 và nằm trong miền</strong> mà cấu trúc đó hỗ trợ: miền là một
     * khoảng, nên ô nhập gram không bị ép về đúng bốn nấc 0,5 · 1 · 1,5 · 2, nhưng cũng không mở
     * đường cho một mục mười phần.</p>
     */
    private BigDecimal resolveServings(PatchItemRequest request, MealPlanItemEntity item,
                                       RecipeSnapshot snapshot, List<BigDecimal> options) {
        if (request.grams() != null) {
            BigDecimal converted = calculator.servingsForCookedGram(snapshot, request.grams())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "Món này chưa có dữ liệu khối lượng một phần, hãy chọn theo số phần.",
                            Map.of("grams", "món chưa cân được một phần bao nhiêu gram")))
                    .setScale(SERVING_SCALE, RoundingMode.HALF_UP);
            return requireInServingDomain(converted, options, "grams");
        }
        if (request.servings() != null) {
            return requireInServingDomain(
                    request.servings().setScale(SERVING_SCALE, RoundingMode.HALF_UP),
                    options, "servings");
        }
        return item.getServings();
    }

    private BigDecimal requireInServingDomain(BigDecimal servings, List<BigDecimal> options,
                                              String field) {
        BigDecimal min = options.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        BigDecimal max = options.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        if (servings.signum() <= 0 || servings.compareTo(min) < 0 || servings.compareTo(max) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Số phần phải nằm trong %s–%s phần của cấu trúc bữa này."
                            .formatted(min.toPlainString(), max.toPlainString()),
                    Map.of(field, "ngoài miền %s–%s".formatted(min.toPlainString(),
                            max.toPlainString())));
        }
        return servings;
    }

    private BigDecimal scaledCost(BigDecimal cost, BigDecimal from, BigDecimal to) {
        if (cost == null || from == null || from.signum() == 0) {
            return null;
        }
        return cost.divide(from, MATH).multiply(to).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // =========================================================================================
    // POST /api/v1/meal-plans/{planId}/regenerate
    // =========================================================================================

    /**
     * Tạo lại các mục <strong>chưa giữ</strong>, nguyên vẹn những mục đã giữ.
     *
     * <p>Mục khóa không còn hợp lệ sau khi hồ sơ đổi thì yêu cầu bị từ chối kèm tên mục đó
     * ({@code RECIPE_NOT_ELIGIBLE}) — <strong>không âm thầm mở khóa</strong>. Người dùng đã nói họ
     * muốn giữ món đó; tự bỏ giữ để lượt tạo lại chạy trót lọt là trả lời một câu hỏi khác.</p>
     *
     * <p>Không tìm được phương án quanh các mục đã giữ thì kế hoạch <strong>giữ nguyên</strong> và
     * phản hồi liệt kê chính các mục đó, để người dùng chọn mục muốn bỏ giữ. HTTP 200: hết phương
     * án là kết quả nghiệp vụ, không phải lỗi hệ thống (_CONTRACT.md mục 3).</p>
     */
    @Transactional
    public PlanEditView regenerate(String userId, String planId, RegenerateRequest request) {
        MealPlanEntity plan = requirePlanForUpdate(userId, planId);
        ProfileSnapshot profile = profiles.snapshot(userId);
        requireVersions(plan, profile, request.expectedPlanVersion(),
                request.expectedProfileVersion());

        PlanLimits limits = limitsOf(profile, request.constraints(), request.seedOrDefault());
        CatalogContext catalog = candidates.loadCatalog();
        // BR11: bữa đã ghi nhật ký không được lập lại. Nạp một lượt ở đây rồi đi cùng mọi bước
        // dưới — hỏi lại nhật ký trong vòng lặp là một câu truy vấn cho mỗi bữa của mỗi ngày.
        EatenMeals eaten = revalidations.eatenMealsOf(plan);
        List<MealSlotEntity> slots = MealPlanService.orderedSlots(plan);
        // Kế hoạch nhiều ngày tạo lại theo NGÀY, đúng như lúc lập (nhánh 9): một hàm mục tiêu
        // chung cho bảy ngày sẽ cho phép một ngày thiếu được bù bằng một ngày thừa.
        Map<LocalDate, List<MealSlotEntity>> byDate = slots.stream()
                .collect(Collectors.groupingBy(MealSlotEntity::getLocalDate, LinkedHashMap::new,
                        Collectors.toList()));

        List<ReasonNote> notes = new ArrayList<>();
        Map<LocalDate, List<Slot>> structures = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> shareSums = new LinkedHashMap<>();
        boolean feasible = true;

        for (var day : byDate.entrySet()) {
            List<Slot> structure = new ArrayList<>();
            BigDecimal shareSum = BigDecimal.ZERO;
            for (MealSlotEntity slot : day.getValue()) {
                BigDecimal share = candidates.shareOf(slot.getMealType(), snackCount(plan, slot));
                Optional<Slot> rebuilt =
                        rebuildSlot(slot, profile, limits, catalog, share, eaten, notes);
                if (rebuilt.isEmpty()) {
                    feasible = false;
                    continue;
                }
                structure.add(rebuilt.get());
                if (share != null) {
                    shareSum = shareSum.add(share);
                }
            }
            structures.put(day.getKey(), structure);
            shareSums.put(day.getKey(), shareSum.signum() == 0 ? null : shareSum);
        }

        List<Outcome> outcomes = new ArrayList<>();
        boolean searchLimit = false;
        if (feasible) {
            SearchDeadline deadline = SearchDeadline.of(SearchDeadline.budgetFor(byDate.size()));
            RankingInputs preferences = candidates.preferenceInputs(profile, limits);
            WeeklyAllowance consumed = weeklyLimits.allowanceFor(regenerationSnapshots(plan,
                    structures.values()));
            PlanVarietyHistory history = PlanVarietyHistory.empty();

            for (var day : byDate.entrySet()) {
                Targets targets = candidates.targetsOf(profile, limits, shareSums.get(day.getKey()));
                Outcome outcome = generator.generate(structures.get(day.getKey()), targets,
                        history.applyTo(preferences), deadline,
                        // Mục đang giữ ở các ngày CHƯA tạo lại vẫn nằm trong kế hoạch sau lượt
                        // này, nên chúng ăn vào mức tuần ngay từ bây giờ. Mục giữ của chính ngày
                        // đang dựng thì không: nó đã là một lựa chọn của ngày đó và bộ sinh cộng
                        // nó như mọi lựa chọn khác.
                        withPinnedOfLaterDays(consumed, byDate, day.getKey(), eaten));
                if (!outcome.complete()) {
                    searchLimit = outcome.searchLimit();
                    feasible = false;
                    break;
                }
                consumed = consumed.consume(outcome.choices());
                history = history.after(outcome.choices());
                outcomes.add(outcome);
            }
        }

        if (!feasible) {
            if (!outcomes.isEmpty() || searchLimit) {
                notes.add(ReasonNote.of(searchLimit
                        ? RecommendationReason.SEARCH_LIMIT : RecommendationReason.NO_MATCH));
            }
            // Một ngày hỏng là cả lượt hỏng: kế hoạch giữ nguyên, không có ngày nào bị thay một
            // nửa.
            notes.addAll(unlockSuggestions(slots));
            return finish(userId, plan, List.of(), notes, false);
        }

        storeRevision(userId, plan);
        int day = 0;
        for (var entry : byDate.entrySet()) {
            applyOutcome(entry.getValue(), structures.get(entry.getKey()), outcomes.get(day++),
                    eaten);
        }
        plan.setPlanVersion(plan.getPlanVersion() + 1);
        return finish(userId, plan, wholePlanNotes(plan, limits), notes, true);
    }

    /**
     * Mọi công thức mà lượt tạo lại có thể để lại trong kế hoạch: ứng viên của từng vị trí và món
     * đang nằm sẵn trong kế hoạch.
     *
     * <p>Thiếu một món ở đây thì giới hạn theo tuần tính nó là 0 g — một điều kiện an toàn bỏ qua
     * trong im lặng, nên tập này cố ý rộng hơn cần.</p>
     */
    private List<RecipeSnapshot> regenerationSnapshots(MealPlanEntity plan,
                                                       Collection<List<Slot>> structures) {
        List<RecipeSnapshot> snapshots = new ArrayList<>();
        for (List<Slot> structure : structures) {
            for (Slot slot : structure) {
                for (Position position : slot.positions()) {
                    position.candidates().forEach(candidate -> snapshots.add(candidate.snapshot()));
                }
            }
        }
        for (MealSlotEntity slot : plan.getSlots()) {
            slot.getItems().forEach(item -> snapshots.add(readSnapshot(item)));
        }
        return snapshots;
    }

    /** Mức tuần đã dùng, cộng thêm các mục đang giữ của những ngày sau ngày đang dựng. */
    private WeeklyAllowance withPinnedOfLaterDays(WeeklyAllowance consumed,
                                                  Map<LocalDate, List<MealSlotEntity>> byDate,
                                                  LocalDate currentDay, EatenMeals eaten) {
        WeeklyAllowance allowance = consumed;
        for (var day : byDate.entrySet()) {
            if (!day.getKey().isAfter(currentDay)) {
                continue;
            }
            for (MealSlotEntity slot : day.getValue()) {
                boolean alreadyEaten = eaten.contains(slot);
                for (MealPlanItemEntity item : slot.getItems()) {
                    if (pinned(item, alreadyEaten)) {
                        allowance = allowance.consume(item.getRecipeId(), item.getServings());
                    }
                }
            }
        }
        return allowance;
    }

    /**
     * Một bữa dựng lại: mục đã giữ thành vị trí cố định, phần còn lại mở cho bộ sinh.
     *
     * <p>Cấu trúc bữa <strong>không đổi</strong> — vẫn đúng template đã lưu trong
     * {@code template_code}. Tạo lại là tìm lựa chọn khác trong cùng cấu trúc, không phải dựng lại
     * một bữa khác hình dạng (BR06).</p>
     */
    private Optional<Slot> rebuildSlot(MealSlotEntity slot,
                                       ProfileSnapshot profile, PlanLimits limits,
                                       CatalogContext catalog, BigDecimal share,
                                       EatenMeals eaten, List<ReasonNote> notes) {
        MealTemplateEntity template = candidates.template(slot.getMealType(), slot.mode())
                .orElse(null);
        if (template == null) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Cấu trúc của %s không còn trong danh mục. Hãy tạo lại thực đơn."
                            .formatted(MealPlanCandidates.describeSlot(slot.getMealType(),
                                    slot.getSequence())));
        }

        boolean alreadyEaten = eaten.contains(slot);
        if (alreadyEaten && !slot.getItems().isEmpty()) {
            // Bữa không đổi mà không ai nói gì trông như một lỗi — lý do thì ngược lại hẳn.
            notes.add(ReasonNote.of(RecommendationReason.PLAN_KEPT_EATEN_MEAL,
                    MealPlanCandidates.describeSlot(slot.getMealType(), slot.getSequence())));
        }

        Map<RecipeRole, MealPlanItemEntity> keep = new HashMap<>();
        for (MealPlanItemEntity item : slot.getItems()) {
            if (!pinned(item, alreadyEaten)) {
                continue;
            }
            RecipeEntity recipe = requireCatalogRecipe(item.getRecipeId());
            if (alreadyEaten) {
                // Bữa đã ăn giữ nguyên và KHÔNG bị kiểm lại điều kiện: nó là một việc đã xảy ra,
                // không phải một lựa chọn còn đang chờ nấu. Chặn cả lượt tạo lại vì một bữa hôm
                // qua không hợp dị ứng vừa khai sẽ khiến người dùng không còn đường nào đi tiếp —
                // họ không sửa lại được bữa đã ăn (BR17).
                keep.put(recipe.getRole(), item);
                continue;
            }
            EligibilityVerdict verdict = candidates.judge(recipe, profile, limits, catalog);
            if (!verdict.eligible()) {
                throw new BusinessException(ErrorCode.RECIPE_NOT_ELIGIBLE,
                        "Món giữ ở %s (%s) không còn hợp hồ sơ: %s. Hãy đổi món đó trước khi tạo lại."
                                .formatted(MealPlanCandidates.describeSlot(slot.getMealType(),
                                                slot.getSequence()),
                                        recipe.getName(), explain(verdict)),
                        Map.of("item_id", item.getId()));
            }
            keep.put(recipe.getRole(), item);
        }

        List<Position> positions = new ArrayList<>();
        for (RecipeRole role : EnumSet.copyOf(template.getRequiredRoles())) {
            MealPlanItemEntity held = keep.get(role);
            if (held != null) {
                // Giữ đúng recipe_version và số phần đã chọn (TC11): dựng ứng viên từ công thức
                // hiện tại sẽ lặng lẽ nâng phiên bản của một món người dùng yêu cầu giữ nguyên.
                positions.add(new Position(role, List.of(held.getServings()),
                        List.of(heldCandidate(held, catalog))));
                continue;
            }
            CandidateSearch search = candidates.candidatesFor(profile, limits, catalog,
                    slot.getMealType(), role, share,
                    MealPlanGenerator.EXPANDED_CANDIDATES_PER_POSITION, null);
            if (search.candidates().isEmpty()) {
                notes.add(candidates.roleNoCandidate(slot.getMealType(), slot.getSequence(), role,
                        search));
                return Optional.empty();
            }
            positions.add(new Position(role, candidates.servingOptions(template),
                    search.candidates()));
        }
        return Optional.of(new Slot(slot.getMealType(), slot.getSequence(), template.getMode(),
                positions));
    }

    /**
     * Ứng viên dựng từ <strong>snapshot đã lưu</strong> của một mục được giữ.
     *
     * <p>Nhờ vậy món giữ mang đúng {@code recipe_version} và đúng con số của lúc chọn, kể cả khi
     * seed đã sửa công thức trong lúc đó (BR17). Chi phí cũng lấy lại từ bản chụp, quy về một
     * phần — món được giữ thì không có lý do gì giá của nó nhảy sau một lượt tạo lại.</p>
     */
    private Candidate heldCandidate(MealPlanItemEntity item, CatalogContext catalog) {
        RecipeSnapshot snapshot = readSnapshot(item);
        RecipeEntity recipe = requireCatalogRecipe(item.getRecipeId());
        RecipeCandidate candidate = new RecipeCandidate(item.getRecipeId(), snapshot.name(),
                item.getRecipeVersion(), recipe.getRole(), recipe.getMealTypes(),
                recipe.getMinutes(), recipe.getMainIngredientTag(), snapshot.servingsYield(),
                RecipeCandidate.of(recipe, catalog.ingredientsById()).ingredients());
        BigDecimal perServing = scaledCost(item.getCostSnapshot(), item.getServings(),
                BigDecimal.ONE);
        return new Candidate(candidate, snapshot, perServing, item.getServings());
    }

    /**
     * Mục mà lượt tạo lại <strong>không được chạm vào</strong>.
     *
     * <p>Hai vế, và chúng khác nhau về bản chất dù cùng kết quả:</p>
     * <ul>
     *   <li><strong>khóa</strong> — người dùng nói họ muốn giữ món này. Vẫn bị kiểm lại điều kiện,
     *       và không hợp thì lượt tạo lại dừng kèm tên mục đó (BR10);</li>
     *   <li><strong>đã ghi nhật ký</strong> — bữa đã ăn rồi. Không kiểm lại, không đổi, không có
     *       đường nào để một lượt tạo lại viết lại một việc đã xảy ra (BR11, bất biến 3).</li>
     * </ul>
     *
     * <p>Vì lượt tạo lại không có đường nào khác để bỏ qua một mục, đây là chỗ duy nhất quyết định
     * cả hai.</p>
     *
     * @param alreadyEaten bữa chứa mục này đã có dòng trong {@code app.diary_entries}
     */
    private boolean pinned(MealPlanItemEntity item, boolean alreadyEaten) {
        return item.isLocked() || alreadyEaten;
    }

    /** Các mục đang giữ, nêu tên để người dùng chọn mục muốn bỏ giữ. */
    private List<ReasonNote> unlockSuggestions(List<MealSlotEntity> slots) {
        List<ReasonNote> notes = new ArrayList<>();
        for (MealSlotEntity slot : slots) {
            for (MealPlanItemEntity item : slot.getItems()) {
                if (item.isLocked()) {
                    notes.add(ReasonNote.of(RecommendationReason.LOCKED_ITEM_UNPLACEABLE,
                            MealPlanCandidates.describeSlot(slot.getMealType(),
                                    slot.getSequence())));
                }
            }
        }
        return notes;
    }

    /**
     * Thay các mục <strong>chưa giữ</strong> bằng phương án vừa tìm được.
     *
     * <p>Mục đã giữ không bị xóa rồi tạo lại: dòng cũ ở nguyên đó, nên {@code item_id} mà giao diện
     * đang cầm vẫn trỏ đúng sau một lượt tạo lại — và {@code recipe_version} cùng snapshot của nó
     * không có cơ hội bị viết lại (TC11).</p>
     */
    private void applyOutcome(List<MealSlotEntity> daySlots, List<Slot> structure,
                              Outcome outcome, EatenMeals eaten) {
        for (int index = 0; index < structure.size(); index++) {
            // Cấu trúc dựng theo đúng thứ tự các bữa của ngày và lượt tạo lại dừng ngay khi một
            // bữa không dựng được, nên vị trí thứ i của cấu trúc là bữa thứ i của ngày.
            MealSlotEntity slot = daySlots.get(index);
            boolean alreadyEaten = eaten.contains(slot);
            slot.getItems().removeIf(item -> !pinned(item, alreadyEaten));
            for (Choice choice : outcome.choicesOf(index)) {
                Candidate candidate = choice.candidate();
                if (candidate.locked()) {
                    // Chính là mục đã giữ, vẫn còn nguyên dòng của nó trong bữa.
                    continue;
                }
                MealPlanItemEntity item = new MealPlanItemEntity();
                item.setRecipeId(candidate.recipeId());
                item.setRecipeVersion(candidate.recipe().recipeVersion());
                item.setServings(choice.servings());
                item.setLocked(false);
                item.setNutrientSnapshot(json.writeValueAsString(candidate.snapshot()));
                item.setCostSnapshot(choice.costVnd() == null ? null
                        : choice.costVnd().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                slot.add(item);
            }
        }
    }

    /**
     * Khóa của một bữa — <strong>có ngày</strong>.
     *
     * <p>Thiếu ngày thì với kế hoạch bảy ngày của nhánh 9, bảy bữa trưa cùng mang khóa
     * {@code LUNCH#1}: lượt hoàn tác sẽ ghi đè cả bảy bằng nội dung của một ngày, và không có lỗi
     * nào nổ ra để nói rằng sáu ngày vừa bị viết lại.</p>
     */
    private static String slotKey(MealSlotEntity slot) {
        return slotKey(slot.getLocalDate(), slot.getMealType(), slot.getSequence());
    }

    private static String slotKey(LocalDate date, MealType mealType, short sequence) {
        return date + "|" + mealType.name() + "#" + sequence;
    }

    // =========================================================================================
    // POST /api/v1/meal-plans/{planId}/undo
    // =========================================================================================

    /**
     * Khôi phục kế hoạch về ngay trước thao tác sửa gần nhất.
     *
     * <p>Hai điều dễ làm sai, và cả hai đều cố ý làm ngược lại:</p>
     * <ul>
     *   <li><strong>Kiểm lại điều kiện hiện tại trước khi khôi phục.</strong> Hồ sơ có thể đã đổi
     *       từ lúc chụp; một món hợp lệ hôm qua mà nay vi phạm dị ứng thì không được quay lại kế
     *       hoạch chỉ vì nó từng ở đó (bất biến 5).</li>
     *   <li><strong>{@code plan_version} vẫn tăng.</strong> Khôi phục nội dung cũ không có nghĩa là
     *       quay ngược đồng hồ: hạ số phiên bản sẽ cho một thiết bị đang cầm số cũ ghi đè thành
     *       công lên thao tác này.</li>
     * </ul>
     *
     * <p>Một bước lùi, không phải một ngăn xếp: bản chụp bị xóa sau khi dùng, nên hoàn tác hai lần
     * liên tiếp nhận 404 thay vì lùi tiếp về một trạng thái người dùng không còn nhớ.</p>
     */
    @Transactional
    public PlanEditView undo(String userId, String planId, UndoRequest request) {
        MealPlanEntity plan = requirePlanForUpdate(userId, planId);
        ProfileSnapshot profile = profiles.snapshot(userId);
        requireVersions(plan, profile, request.expectedPlanVersion(),
                request.expectedProfileVersion());

        MealPlanPreviewEntity stored = previews
                .findByUserIdAndRequestId(userId, PlanRevision.requestIdFor(planId))
                .filter(row -> !row.isExpiredAt(now()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không còn thao tác nào để hoàn tác trên thực đơn này."));
        PlanRevision revision = json.readValue(stored.getPayload(), PlanRevision.class);

        PlanLimits limits = limitsOf(profile, null, 0L);
        CatalogContext catalog = candidates.loadCatalog();
        restore(plan, revision, profile, limits, catalog);

        previews.delete(stored);
        previews.flush();

        plan.setPlanVersion(plan.getPlanVersion() + 1);
        return finish(userId, plan, wholePlanNotes(plan, limits), List.of(), true);
    }

    private void restore(MealPlanEntity plan, PlanRevision revision, ProfileSnapshot profile,
                         PlanLimits limits, CatalogContext catalog) {
        Map<String, MealSlotEntity> bySlotKey = plan.getSlots().stream()
                .collect(Collectors.toMap(MealPlanEditService::slotKey, slot -> slot));

        for (RevisionSlot snapshot : revision.slots()) {
            MealSlotEntity slot = bySlotKey.get(slotKey(
                    snapshot.localDate(), snapshot.mealType(), snapshot.sequence()));
            if (slot == null) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                        "Thực đơn đã đổi cấu trúc bữa, không khôi phục được. Hãy tải lại.");
            }
            // Kiểm TRƯỚC khi xóa mục nào: một món không còn hợp lệ phải dừng cả lượt hoàn tác,
            // không để lại một bữa trống.
            for (RevisionItem item : snapshot.items()) {
                RecipeEntity recipe = requireCatalogRecipe(item.recipeId());
                EligibilityVerdict verdict = candidates.judge(recipe, profile, limits, catalog);
                if (!verdict.eligible()) {
                    throw new BusinessException(ErrorCode.RECIPE_NOT_ELIGIBLE,
                            "Không hoàn tác được: món %s của bản trước nay không còn hợp hồ sơ (%s)."
                                    .formatted(recipe.getName(), explain(verdict)),
                            Map.of("recipe_id", recipe.getId()));
                }
            }
        }

        for (RevisionSlot snapshot : revision.slots()) {
            MealSlotEntity slot = bySlotKey.get(slotKey(
                    snapshot.localDate(), snapshot.mealType(), snapshot.sequence()));
            slot.getItems().clear();
            for (RevisionItem saved : snapshot.items()) {
                MealPlanItemEntity item = new MealPlanItemEntity();
                item.setRecipeId(saved.recipeId());
                item.setRecipeVersion(saved.recipeVersion());
                item.setServings(saved.servings());
                item.setLocked(saved.locked());
                item.setNutrientSnapshot(saved.nutrientSnapshot());
                item.setCostSnapshot(saved.costSnapshot());
                slot.add(item);
            }
        }
    }

    // =========================================================================================
    // Bản hoàn tác
    // =========================================================================================

    /**
     * Chụp trạng thái hiện tại thành bản lùi, ghi đè bản cũ nếu có.
     *
     * <p>Ghi đè chứ không xếp chồng: {@code uq_meal_plan_previews_request} cho mỗi kế hoạch đúng
     * một hàng, và đó chính là "một bước lùi" mà tài liệu nhánh yêu cầu.</p>
     */
    private void storeRevision(String userId, MealPlanEntity plan) {
        String requestId = PlanRevision.requestIdFor(plan.getId());
        MealPlanPreviewEntity row = previews.findByUserIdAndRequestId(userId, requestId)
                .orElseGet(MealPlanPreviewEntity::new);
        if (row.getId() == null) {
            row.setId(java.util.UUID.randomUUID().toString());
            row.setUserId(userId);
            row.setRequestId(requestId);
            row.setSavedPlanId(plan.getId());
        }
        row.setProfileVersion(plan.getProfileVersion());
        row.setRulesVersion(plan.getRulesVersion());
        row.setStatus(plan.getGenerationStatus());
        row.setPayload(json.writeValueAsString(PlanRevision.of(plan)));
        row.setCreatedAt(now());
        row.setExpiresAt(now().plus(UNDO_TTL));
        previews.saveAndFlush(row);
    }

    private boolean undoAvailable(String userId, String planId) {
        return previews.findByUserIdAndRequestId(userId, PlanRevision.requestIdFor(planId))
                .filter(row -> !row.isExpiredAt(now()))
                .isPresent();
    }

    // =========================================================================================
    // Kiểm toàn kế hoạch và dựng phản hồi
    // =========================================================================================

    /**
     * BR12: sau mỗi thao tác sửa, kiểm lại <strong>cả ngày</strong>, không riêng mục vừa đổi.
     *
     * <p>Ngân sách là giới hạn của ngày và thời gian là giới hạn của một bữa, nên không mục nào tự
     * nó vi phạm được — đổi một món rẻ hơn vẫn có thể đẩy cả ngày vượt ngân sách nếu nó kéo theo
     * khẩu phần lớn hơn. Ghi chú ở đây là <em>ưu tiên mềm chưa trọn</em>, không phải lỗi: kế hoạch
     * vẫn lưu được và vẫn dùng được (BR13).</p>
     *
     * <p>Kế hoạch một ngày nên "các điều kiện liên quan của tuần" chỉ có một ngày để xét; nhánh 9
     * mở rộng chính hàm này sang nhiều ngày.</p>
     */
    private List<ReasonNote> wholePlanNotes(MealPlanEntity plan, PlanLimits limits) {
        List<ReasonNote> notes = new ArrayList<>();

        if (limits.dailyBudgetVnd() != null) {
            // Ngân sách người dùng khai là của MỘT ngày, nên kế hoạch nhiều ngày so theo từng
            // ngày: so tổng cả tuần với mức của một ngày thì bảy ngày nào cũng "vượt".
            for (LocalDate date : datesOf(plan)) {
                BigDecimal cost = MealPlanService.dayCostOf(plan, date);
                if (cost != null && cost.compareTo(limits.dailyBudgetVnd()) > 0) {
                    notes.add(ReasonNote.of(RecommendationReason.DAY_OVER_BUDGET,
                            cost.toPlainString()));
                    break;
                }
            }
        }

        notes.addAll(weeklyLimits.notes(weeklyConsumption(plan)));

        if (limits.maxMinutesPerMeal() != null) {
            Map<String, Integer> minutes = recipeMinutes(plan);
            for (MealSlotEntity slot : plan.getSlots()) {
                int total = slot.getItems().stream()
                        .mapToInt(item -> minutes.getOrDefault(item.getRecipeId(), 0))
                        .sum();
                if (total > limits.maxMinutesPerMeal()) {
                    notes.add(ReasonNote.of(RecommendationReason.MEAL_OVER_TIME,
                            String.valueOf(limits.maxMinutesPerMeal())));
                    break;
                }
            }
        }
        return notes;
    }

    /** Các ngày của kế hoạch, theo thứ tự. */
    private List<LocalDate> datesOf(MealPlanEntity plan) {
        return plan.getSlots().stream()
                .map(MealSlotEntity::getLocalDate)
                .distinct()
                .sorted()
                .toList();
    }

    /** Mức tuần đã dùng của kế hoạch <strong>như nó đang nằm trong database</strong>. */
    private WeeklyAllowance weeklyConsumption(MealPlanEntity plan) {
        if (weeklyLimits.limits().isEmpty()) {
            return WeeklyAllowance.unlimited();
        }
        List<RecipeSnapshot> snapshots = new ArrayList<>();
        for (MealSlotEntity slot : plan.getSlots()) {
            slot.getItems().forEach(item -> snapshots.add(readSnapshot(item)));
        }
        WeeklyAllowance allowance = weeklyLimits.allowanceFor(snapshots);
        for (MealSlotEntity slot : plan.getSlots()) {
            for (MealPlanItemEntity item : slot.getItems()) {
                allowance = allowance.consume(item.getRecipeId(), item.getServings());
            }
        }
        return allowance;
    }

    private Map<String, Integer> recipeMinutes(MealPlanEntity plan) {
        Set<String> ids = plan.getSlots().stream()
                .flatMap(slot -> slot.getItems().stream())
                .map(MealPlanItemEntity::getRecipeId)
                .collect(Collectors.toSet());
        Map<String, Integer> minutes = new HashMap<>();
        recipes.findAllById(ids).forEach(recipe -> minutes.put(recipe.getId(),
                recipe.getMinutes()));
        return minutes;
    }

    /**
     * Ghi kế hoạch và dựng phản hồi — <strong>cùng một transaction</strong> với thay đổi.
     *
     * <p>{@code generation_status} tính lại từ chính các ghi chú vừa dựng, bằng đúng hàm của nhánh
     * 5: một kế hoạch sau khi sửa mà hết ghi chú thì quay về {@code READY}, và ngược lại.</p>
     *
     * <p>{@code lifecycle_state} cũng vậy, và đây là chỗ vòng trạng thái của nhánh 10 khép lại:
     * {@link MealPlanService#view} vừa kiểm lại cả kế hoạch theo hồ sơ hiện tại, nên đổi xong mục
     * xung đột cuối cùng là kế hoạch tự quay về {@link PlanLifecycleState#SAVED} — không cần ai
     * nhớ bấm thêm một nút nào.</p>
     */
    private PlanEditView finish(String userId, MealPlanEntity plan, List<ReasonNote> planNotes,
                                List<ReasonNote> operationNotes, boolean changed) {
        PlanView rendered = planViews.view(plan, planNotes);
        if (changed) {
            GenerationStatus status = statusFrom(rendered.notes());
            plan.setGenerationStatus(status);
            plan.setLifecycleState(rendered.lifecycleState());
            plans.saveAndFlush(plan);
            rendered = withStatus(rendered, status);
        }
        return new PlanEditView(rendered, changed, plan.getPlanVersion(),
                undoAvailable(userId, plan.getId()), MealPlanService.views(operationNotes));
    }

    private GenerationStatus statusFrom(List<ReasonView> notes) {
        boolean needsReview = notes.stream()
                .anyMatch(note -> !MealPlanService.describesOnly(note.code()));
        return needsReview ? GenerationStatus.READY_WITH_NOTES : GenerationStatus.READY;
    }

    private PlanView withStatus(PlanView view, GenerationStatus status) {
        return new PlanView(view.planId(), status, view.lifecycleState(), view.planVersion(),
                view.profileVersion(), view.rulesVersion(), view.startDate(), view.endDate(),
                view.nutritionCoverage(), view.notes(), view.days());
    }

    // =========================================================================================
    // Hạ tầng chung
    // =========================================================================================

    private MealPlanEntity requirePlanForUpdate(String userId, String planId) {
        return plans.findForUpdate(planId, userId).orElseThrow(MealPlanEditService::planNotFound);
    }

    /**
     * Đối chiếu <strong>cả hai</strong> con số phiên bản trước khi chạm vào dữ liệu.
     *
     * <p>Không thay gì rồi mới báo lỗi: hai câu kiểm này đứng trước mọi lệnh ghi trong lớp, nên một
     * yêu cầu cầm phiên bản cũ nhận 409 và kế hoạch còn nguyên (TC14).</p>
     */
    private void requireVersions(MealPlanEntity plan, ProfileSnapshot profile, int expectedPlan,
                                 int expectedProfile) {
        if (plan.getPlanVersion() != expectedPlan) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Thực đơn đã được sửa ở nơi khác. Hãy tải lại thực đơn rồi chọn lại.");
        }
        if (profile.profileVersion() != expectedProfile) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Hồ sơ ăn uống đã thay đổi. Hãy tải lại hồ sơ rồi chọn lại.");
        }
    }

    /** Mục cần sửa, cùng bữa chứa nó. */
    private record Located(MealSlotEntity slot, MealPlanItemEntity item) {
    }

    private Located locate(MealPlanEntity plan, String itemId) {
        for (MealSlotEntity slot : plan.getSlots()) {
            for (MealPlanItemEntity item : slot.getItems()) {
                if (item.getId().equals(itemId)) {
                    return new Located(slot, item);
                }
            }
        }
        // Chủ sở hữu đã nằm trong truy vấn kế hoạch, nên tới đây nghĩa là mục không thuộc kế hoạch
        // này — và với người gọi thì "không tồn tại" là câu đúng (TC20).
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                "Không tìm thấy mục này trong thực đơn.");
    }

    private RecipeEntity requireCatalogRecipe(String recipeId) {
        return recipes.findById(recipeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_CONFLICT,
                        "Món trong thực đơn không còn trong danh mục. Hãy tạo lại thực đơn."));
    }

    /**
     * Số bữa cùng loại trong ngày — mẫu số của tỉ lệ năng lượng khi có nhiều bữa phụ (QĐ-02).
     */
    private short snackCount(MealPlanEntity plan, MealSlotEntity slot) {
        return (short) plan.getSlots().stream()
                .filter(other -> other.getMealType() == slot.getMealType()
                        && other.getLocalDate().equals(slot.getLocalDate()))
                .count();
    }

    private RecipeSnapshot readSnapshot(MealPlanItemEntity item) {
        return json.readValue(item.getNutrientSnapshot(), RecipeSnapshot.class);
    }

    /** Lý do bước lọc, ghép thành một câu người đọc được — BR15: luôn dựng từ dữ liệu thật. */
    private String explain(EligibilityVerdict verdict) {
        return verdict.reasons().stream()
                .map(ReasonNote::message)
                .collect(Collectors.joining("; "));
    }

    private static BusinessException planNotFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thực đơn này.");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * Giới hạn mềm cho một thao tác sửa.
     *
     * <p>Lấy từ hồ sơ ăn uống, vì kế hoạch đã lưu không mang theo ngân sách của lượt lập ra nó —
     * và điều người dùng quan tâm lúc đổi món là ngân sách <em>hiện tại</em> của họ. Lượt tạo lại
     * được gửi kèm {@code constraints} riêng thì bản gửi kèm thắng.</p>
     */
    private PlanLimits limitsOf(ProfileSnapshot profile, PlanConstraints constraints, long seed) {
        // Phản hồi sau bữa nạp một lượt ở đây, đi cùng limits xuống cả danh sách món thay thế lẫn
        // bộ sinh của lượt tạo lại. Món người dùng vừa bấm "không thích" không nên đứng đầu danh
        // sách "Đổi món" — đó đúng là lúc họ đang tìm cách thoát khỏi nó.
        Map<String, MealFeedbackSignal> feedback = feedbacks.latestSignals(profile.userId());
        if (constraints == null) {
            return new PlanLimits(
                    profile.dailyBudgetVnd() == null
                            ? null : new BigDecimal(profile.dailyBudgetVnd()),
                    profile.maxMinutesPerMeal(), false, false, seed, feedback);
        }
        Long budget = constraints.dailyBudgetVnd() != null
                ? constraints.dailyBudgetVnd() : profile.dailyBudgetVnd();
        Integer minutes = constraints.maxMinutesPerMeal() != null
                ? constraints.maxMinutesPerMeal() : profile.maxMinutesPerMeal();
        return new PlanLimits(budget == null ? null : new BigDecimal(budget), minutes,
                constraints.strictBudgetEnabled(), constraints.strictTimeEnabled(), seed, feedback);
    }
}
