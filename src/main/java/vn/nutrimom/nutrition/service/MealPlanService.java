package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.GenerationStatus;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.MealPlanEntity;
import vn.nutrimom.nutrition.domain.MealPlanItemEntity;
import vn.nutrimom.nutrition.domain.MealPlanPreviewEntity;
import vn.nutrimom.nutrition.domain.MealSlotEntity;
import vn.nutrimom.nutrition.domain.MealTemplateEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.PlanLifecycleState;
import vn.nutrimom.nutrition.domain.PriceEstimateEntity;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.dto.MealPlanDtos.DayView;
import vn.nutrimom.nutrition.dto.MealPlanDtos.ItemView;
import vn.nutrimom.nutrition.dto.MealPlanDtos.NutrientView;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PlanView;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PreviewRequest;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PreviewResponse;
import vn.nutrimom.nutrition.dto.MealPlanDtos.SavePlanRequest;
import vn.nutrimom.nutrition.dto.MealPlanDtos.SlotView;
import vn.nutrimom.nutrition.dto.RecommendationDtos.ReasonView;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.MealPlanPreviewRepository;
import vn.nutrimom.nutrition.repository.MealPlanRepository;
import vn.nutrimom.nutrition.repository.MealTemplateRepository;
import vn.nutrimom.nutrition.repository.PriceEstimateRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;
import vn.nutrimom.nutrition.service.MealPlanSearch.Candidate;
import vn.nutrimom.nutrition.service.MealPlanSearch.Choice;
import vn.nutrimom.nutrition.service.MealPlanSearch.Outcome;
import vn.nutrimom.nutrition.service.MealPlanSearch.Position;
import vn.nutrimom.nutrition.service.MealPlanSearch.Slot;
import vn.nutrimom.nutrition.service.MealPlanSearch.Targets;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.NutritionTotals.NutrientTotal;
import vn.nutrimom.nutrition.service.RecipeCandidate.IngredientFacts;

/**
 * Lập thực đơn một ngày: hồ sơ → cấu trúc bữa → lọc → xếp hạng → ghép → bản xem trước lưu được.
 *
 * <p>Lớp này làm bước 1, 2, 3 và 8 của tám bước; bước 4–7 (mở rộng, loại, chấm điểm, giữ beam) nằm
 * ở {@link MealPlanGenerator}.</p>
 *
 * <h2>Vì sao không gọi lại {@link RecommendationService}</h2>
 *
 * <p>Bộ gợi ý trả về một <em>danh sách đã xếp hạng cho giao diện</em>, và hợp đồng JSON của nó cố ý
 * <strong>không</strong> mang điểm thành phần, tổng dinh dưỡng hay chi phí — những thứ bộ sinh cần
 * để so hai tổ hợp với nhau. Nên nhánh này dùng lại đúng <em>các viên gạch</em> mà bộ gợi ý dùng
 * ({@link EligibilityFilter} → {@link RecipeRanker} → {@link NutritionCalculator}), theo đúng thứ tự
 * nối tiếp đó, chứ không dùng lại tầng DTO của nó. Lọc vẫn chạy trước, và chỉ món
 * {@code ELIGIBLE} mới được chấm điểm (bất biến 2).</p>
 *
 * <h2>Hai tập trạng thái không lẫn nhau</h2>
 *
 * <p>{@link GenerationStatus} nói lượt sinh ra kết quả gì, {@link PlanLifecycleState} nói bản ghi
 * đang ở đâu trong vòng đời, {@link CoverageStatus} nói dữ liệu đầy đủ tới đâu.
 * <strong>READY không phải chứng nhận y tế.</strong></p>
 */
@Service
public class MealPlanService {

    /** TTL của bản xem trước (QĐ-01). */
    public static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    /** Trần số món duyệt cho MỘT vị trí. Chạm trần thì nói ra bằng {@code SEARCH_LIMIT}. */
    private static final int MAX_CANDIDATE_SCAN = 200;

    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);

    /**
     * Khẩu phần dự phòng khi template chưa khai {@code default_serving_options}.
     *
     * <p>Một phần, đúng một lựa chọn: thà bộ sinh không có gì để xoay còn hơn tự bịa ra một dải
     * khẩu phần rồi nhân lên cho đủ vi chất.</p>
     */
    private static final List<BigDecimal> FALLBACK_SERVINGS = List.of(BigDecimal.ONE);

    private final NutritionProfileService profiles;
    private final RecipeRepository recipes;
    private final IngredientRepository ingredients;
    private final PriceEstimateRepository prices;
    private final MealTemplateRepository templates;
    private final MealPlanRepository plans;
    private final MealPlanPreviewRepository previews;
    private final EligibilityFilter filter;
    private final RecipeRanker ranker;
    private final RecipeCostEstimator costs;
    private final NutritionCalculator calculator;
    private final MealPlanGenerator generator;
    private final ObjectMapper json;
    private final Clock clock;

    public MealPlanService(NutritionProfileService profiles, RecipeRepository recipes,
                           IngredientRepository ingredients, PriceEstimateRepository prices,
                           MealTemplateRepository templates, MealPlanRepository plans,
                           MealPlanPreviewRepository previews, EligibilityFilter filter,
                           RecipeRanker ranker, RecipeCostEstimator costs,
                           NutritionCalculator calculator, MealPlanGenerator generator,
                           ObjectMapper json, Clock clock) {
        this.profiles = profiles;
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.prices = prices;
        this.templates = templates;
        this.plans = plans;
        this.previews = previews;
        this.filter = filter;
        this.ranker = ranker;
        this.costs = costs;
        this.calculator = calculator;
        this.generator = generator;
        this.json = json;
        this.clock = clock;
    }

    // =========================================================================================
    // POST /api/v1/meal-plans/preview
    // =========================================================================================

    @Transactional
    public PreviewResponse preview(String userId, PreviewRequest request) {
        ProfileSnapshot profile = profiles.snapshot(userId);
        requireMatchingProfileVersion(request.profileVersion(), profile.profileVersion());

        Optional<PreviewResponse> replayed = replay(userId, request.requestId());
        if (replayed.isPresent()) {
            return replayed.get();
        }

        PreviewResponse response = generate(profile, request);
        return store(userId, request.requestId(), profile.profileVersion(), response);
    }

    /**
     * Gửi lại cùng {@code request_id} trả lại <strong>đúng bản cũ</strong> (_CONTRACT.md mục 6).
     *
     * <p>Bản đã hết hạn thì xóa lười ngay tại đây rồi sinh bản mới: giữ lại một bản chết chỉ để
     * khóa {@code request_id} sẽ làm người dùng bấm "Tạo thực đơn" mà không bao giờ ra được kết quả
     * mới.</p>
     */
    private Optional<PreviewResponse> replay(String userId, String requestId) {
        Optional<MealPlanPreviewEntity> existing =
                previews.findByUserIdAndRequestId(userId, requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        MealPlanPreviewEntity preview = existing.get();
        if (preview.isExpiredAt(now()) && preview.getSavedPlanId() == null) {
            previews.delete(preview);
            previews.flush();
            return Optional.empty();
        }
        return Optional.of(readPayload(preview));
    }

    private PreviewResponse store(String userId, String requestId, int profileVersion,
                                  PreviewResponse response) {
        MealPlanPreviewEntity preview = new MealPlanPreviewEntity();
        preview.setUserId(userId);
        preview.setRequestId(requestId);
        preview.setProfileVersion(profileVersion);
        preview.setRulesVersion(response.rulesVersion());
        preview.setStatus(response.status());
        preview.setCreatedAt(now());
        preview.setExpiresAt(now().plus(PREVIEW_TTL));
        // Ghi payload với chính id sắp lưu, để preview_id trong body và trong bảng là một.
        preview.setId(java.util.UUID.randomUUID().toString());
        PreviewResponse stored = withPreviewId(response, preview.getId(), preview.getExpiresAt());
        preview.setPayload(json.writeValueAsString(stored));

        try {
            previews.saveAndFlush(preview);
        } catch (DataIntegrityViolationException duplicateRequestId) {
            // Hai lượt gửi song song cùng request_id: unique (user_id, request_id) chặn bản thứ
            // hai. Đọc lại bản thắng và trả về nó — đúng tinh thần idempotency, không phải lỗi.
            return previews.findByUserIdAndRequestId(userId, requestId)
                    .map(this::readPayload)
                    .orElseThrow(() -> duplicateRequestId);
        }
        return stored;
    }

    // =========================================================================================
    // Sinh thực đơn
    // =========================================================================================

    private PreviewResponse generate(ProfileSnapshot profile, PreviewRequest request) {
        String rulesVersion = calculator.rules().rulesVersion();
        LocalDate date = request.startDate();
        CatalogContext catalog = loadCatalog();

        List<ReasonNote> notes = new ArrayList<>();
        PlanStructure structure = buildStructure(profile, request, catalog, notes);

        if (!structure.feasible()) {
            // Không hoàn thành được vị trí theo điều kiện cứng. HTTP 200 kèm lý do — và lý do nêu
            // rõ bữa nào, vai trò nào, bộ lọc nào (TC18).
            notes.add(ReasonNote.of(RecommendationReason.NO_MATCH));
            return noMatch(profile, rulesVersion, request, date, structure, notes);
        }

        Targets targets = targetsOf(profile, request, structure);
        Outcome outcome = generator.generate(structure.slots(), targets,
                preferenceInputs(profile, request), SearchDeadline.of(SearchDeadline.ONE_DAY_BUDGET));

        if (!outcome.complete()) {
            notes.add(ReasonNote.of(outcome.searchLimit()
                    ? RecommendationReason.SEARCH_LIMIT : RecommendationReason.NO_MATCH));
            if (outcome.searchLimit()) {
                notes.add(ReasonNote.of(RecommendationReason.NO_MATCH));
            }
            return noMatch(profile, rulesVersion, request, date, structure, notes);
        }

        return render(profile, rulesVersion, request, date, structure, outcome, targets, notes);
    }

    /**
     * Bước 2: tạo vị trí trước, chọn món sau.
     *
     * <p>Mỗi bữa chọn <strong>một</strong> cấu trúc trong các template của nó, ưu tiên cấu trúc ít
     * món hơn ("ưu tiên ít bước"), và chỉ nhận cấu trúc mà <em>mọi</em> vai trò đều còn ứng viên.
     * Không có cấu trúc nào khả thi thì cả kế hoạch là {@code NO_MATCH} — không bao giờ trộn vai
     * trò của hai cấu trúc để lấp đầy (BR06).</p>
     */
    private PlanStructure buildStructure(ProfileSnapshot profile, PreviewRequest request,
                                         CatalogContext catalog, List<ReasonNote> notes) {
        List<Slot> slots = new ArrayList<>();
        List<SlotFailure> failures = new ArrayList<>();
        BigDecimal shareSum = BigDecimal.ZERO;
        boolean searchLimitHit = false;
        int droppedForMissingPrice = 0;

        for (MealType mealType : EnumSet.copyOf(request.mealTypes())) {
            short count = mealType == MealType.SNACK ? request.snackCountOrDefault() : 1;
            for (short sequence = 1; sequence <= count; sequence++) {
                BigDecimal share = shareOf(mealType, count);
                SlotBuild build = buildSlot(profile, request, catalog, mealType, sequence, share);
                searchLimitHit |= build.searchLimitHit();
                droppedForMissingPrice += build.droppedForMissingPrice();
                if (build.slot() == null) {
                    failures.add(new SlotFailure(mealType, sequence, build.failures()));
                    continue;
                }
                slots.add(build.slot());
                if (share != null) {
                    shareSum = shareSum.add(share);
                }
            }
        }

        if (searchLimitHit) {
            notes.add(ReasonNote.of(RecommendationReason.SEARCH_LIMIT));
        }
        if (droppedForMissingPrice > 0) {
            notes.add(ReasonNote.of(RecommendationReason.STRICT_BUDGET_WITHOUT_PRICE,
                    String.valueOf(droppedForMissingPrice)));
        }
        failures.forEach(failure -> notes.addAll(failure.reasons()));

        return new PlanStructure(List.copyOf(slots), List.copyOf(failures),
                shareSum.signum() == 0 ? null : shareSum);
    }

    private SlotBuild buildSlot(ProfileSnapshot profile, PreviewRequest request,
                                CatalogContext catalog, MealType mealType, short sequence,
                                BigDecimal share) {
        List<ReasonNote> failures = new ArrayList<>();
        // Giới hạn cứng của hồ sơ, dựng MỘT lần cho cả bữa: món khóa và món tự chọn đi qua đúng
        // cùng một bộ điều kiện, nên không có đường nào để món khóa đi tắt (bất biến 2).
        ProfileConstraints constraints = hardConstraints(profile, request);
        LockedItems requested =
                lockedItemsFor(request, catalog, constraints, mealType, sequence);
        failures.addAll(requested.rejections());
        if (requested.anyRejected()) {
            // Người dùng yêu cầu giữ đúng món này. Lặng lẽ lấp chỗ đó bằng món khác là trả lời một
            // câu hỏi khác câu họ đặt — và nếu món bị chặn vì dị ứng thì đó đúng là chuyện bất biến
            // 5 cấm: không nới điều kiện an toàn để có kết quả.
            return new SlotBuild(null, List.copyOf(failures), false, 0);
        }
        List<LockedItem> locked = requested.items();
        Set<RecipeRole> lockedRoles = locked.stream()
                .map(LockedItem::role)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(RecipeRole.class)));

        boolean searchLimitHit = false;
        int dropped = 0;

        for (MealTemplateEntity template : templatesFor(mealType)) {
            Set<RecipeRole> roles = EnumSet.copyOf(template.getRequiredRoles());
            if (!roles.containsAll(lockedRoles)) {
                // Món khóa không thuộc cấu trúc này — thử cấu trúc khác trước khi kết luận.
                continue;
            }

            List<Position> positions = new ArrayList<>();
            boolean complete = true;
            for (RecipeRole role : roles) {
                LockedItem pinned = locked.stream()
                        .filter(item -> item.role() == role).findFirst().orElse(null);
                if (pinned != null) {
                    positions.add(new Position(role, List.of(pinned.servings()),
                            List.of(pinned.candidate())));
                    continue;
                }
                CandidateSearch search = candidatesFor(profile, request, catalog, mealType, role,
                        share);
                searchLimitHit |= search.hitScanLimit();
                dropped += search.droppedForMissingPrice();
                if (search.candidates().isEmpty()) {
                    failures.add(roleNoCandidate(mealType, sequence, role, search));
                    complete = false;
                    break;
                }
                positions.add(new Position(role, servingOptions(template), search.candidates()));
            }

            if (complete) {
                return new SlotBuild(new Slot(mealType, sequence, template.getMode(), positions),
                        List.of(), searchLimitHit, dropped);
            }
        }

        if (failures.isEmpty()) {
            failures.add(templatesFor(mealType).isEmpty()
                    // Danh mục chưa khai cấu trúc nào cho bữa này — lỗi seed, nhưng người dùng vẫn
                    // phải nhận được một câu nói rõ chuyện gì đang xảy ra.
                    ? ReasonNote.of(RecommendationReason.ROLE_NO_CANDIDATE,
                            "cấu trúc của %s (danh mục chưa khai template cho bữa này)"
                                    .formatted(describeSlot(mealType, sequence)))
                    // Có template, nhưng không cái nào chứa vai trò của món đã khóa.
                    : ReasonNote.of(RecommendationReason.LOCKED_ITEM_UNPLACEABLE,
                            describeSlot(mealType, sequence)));
        }
        return new SlotBuild(null, List.copyOf(failures), searchLimitHit, dropped);
    }

    /**
     * TC18: nói rõ <strong>vai trò nào, bữa nào</strong> và <strong>bộ lọc nào</strong> đã chặn.
     *
     * <p>Danh sách mã chặn lấy thẳng từ kết luận của {@link EligibilityFilter} cho đúng các món đã
     * bị loại ở vị trí đó, nên nó luôn đối chiếu được với dữ liệu thật, không phải một câu đoán.</p>
     */
    private ReasonNote roleNoCandidate(MealType mealType, short sequence, RecipeRole role,
                                       CandidateSearch search) {
        String filters = search.blockingCodes().isEmpty()
                ? "danh mục chưa có món nào cho vai trò này"
                : "bộ lọc: " + String.join(", ", search.blockingCodes());
        return ReasonNote.of(RecommendationReason.ROLE_NO_CANDIDATE,
                "vai trò %s của %s (%s)".formatted(role, describeSlot(mealType, sequence), filters));
    }

    private String describeSlot(MealType mealType, short sequence) {
        return mealType == MealType.SNACK ? "bữa SNACK #" + sequence : "bữa " + mealType;
    }

    /**
     * Bước 3: lọc rồi chấm điểm, <strong>nối tiếp</strong>, lấy ứng viên đúng loại bữa và vai trò.
     *
     * <p>Thứ hạng ở đây là thứ hạng <em>trong một vị trí</em> theo công thức điểm món của nhánh 4.
     * Việc chọn tổ hợp nào cho cả ngày thuộc {@link MealPlanGenerator} và dùng hàm mục tiêu J ở cấp
     * ngày — hai phép chấm điểm khác nhau cho hai câu hỏi khác nhau.</p>
     */
    private CandidateSearch candidatesFor(ProfileSnapshot profile, PreviewRequest request,
                                          CatalogContext catalog, MealType mealType,
                                          RecipeRole role, BigDecimal share) {
        List<RecipeEntity> rows = recipes.search(mealType, role, null, null,
                Limit.of(MAX_CANDIDATE_SCAN + 1));
        boolean hitScanLimit = rows.size() > MAX_CANDIDATE_SCAN;
        if (hitScanLimit) {
            rows = rows.subList(0, MAX_CANDIDATE_SCAN);
        }

        ProfileConstraints constraints = hardConstraints(profile, request);

        List<Candidate> eligible = new ArrayList<>();
        List<RecipeRanker.Scorable> scorables = new ArrayList<>();
        Map<String, Candidate> byId = new HashMap<>();
        Set<String> blockingCodes = new LinkedHashSet<>();
        int droppedForMissingPrice = 0;

        for (RecipeEntity recipe : rows) {
            RecipeCandidate candidate = RecipeCandidate.of(recipe, catalog.ingredientsById());
            Optional<BigDecimal> cost = costs.perServing(recipe, catalog.pricesByIngredient());
            EligibilityVerdict verdict = filter.judge(candidate, constraints, catalog.facts(), cost);
            if (!verdict.eligible()) {
                verdict.reasons().forEach(reason -> blockingCodes.add(reason.code()));
                continue;
            }
            if (request.constraintsOrEmpty().strictBudgetEnabled() && cost.isEmpty()) {
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
                    calculator.forPortion(snapshot, BigDecimal.ONE), cost.orElse(null), null));
        }

        for (RecipeScore score : ranker.rank(scorables, slotInputs(profile, request, share))) {
            eligible.add(byId.get(score.recipeId()));
            if (eligible.size() >= MealPlanGenerator.EXPANDED_CANDIDATES_PER_POSITION) {
                break;
            }
        }
        return new CandidateSearch(List.copyOf(eligible), List.copyOf(blockingCodes), hitScanLimit,
                droppedForMissingPrice);
    }

    /**
     * Điều kiện cứng của hồ sơ cho bước lọc.
     *
     * <p>Giới hạn thời gian cứng kiểm được ngay ở mức món: một món đã dài hơn giới hạn của cả bữa
     * thì mọi bữa chứa nó cũng dài hơn, nên loại sớm là đúng và rẻ. <strong>Ngân sách thì
     * không</strong> — nó là giới hạn của cả ngày, và chỉ có nghĩa trên một tổ hợp, nên bộ sinh
     * kiểm nó ở đó.</p>
     */
    private ProfileConstraints hardConstraints(ProfileSnapshot profile, PreviewRequest request) {
        var constraints = request.constraintsOrEmpty();
        return profile.constraints(
                constraints.strictTimeEnabled() ? constraints.maxMinutesPerMeal() : null, null);
    }

    /**
     * Món đã khóa, dựng thành ứng viên <strong>sau khi</strong> qua đúng bước lọc của mọi món khác.
     *
     * <p>Khóa một món không mở được đường tắt: món chứa thành phần dị ứng vẫn bị chặn, và kết quả
     * là {@code NO_MATCH} kèm đúng lý do của bước lọc (bất biến 2, BR05).</p>
     */
    private LockedItems lockedItemsFor(PreviewRequest request, CatalogContext catalog,
                                       ProfileConstraints constraints, MealType mealType,
                                       short sequence) {
        List<LockedItem> locked = new ArrayList<>();
        List<ReasonNote> rejections = new ArrayList<>();
        for (var requested : request.lockedItemsOrEmpty()) {
            if (requested.mealType() != mealType || requested.sequenceOrDefault() != sequence) {
                continue;
            }
            RecipeEntity recipe = recipes.findById(requested.recipeId()).orElse(null);
            if (recipe == null || !recipe.getMealTypes().contains(mealType)) {
                rejections.add(ReasonNote.of(RecommendationReason.LOCKED_ITEM_UNPLACEABLE,
                        describeSlot(mealType, sequence)));
                continue;
            }
            RecipeCandidate candidate = RecipeCandidate.of(recipe, catalog.ingredientsById());
            Optional<BigDecimal> cost = costs.perServing(recipe, catalog.pricesByIngredient());
            EligibilityVerdict verdict = filter.judge(candidate, constraints, catalog.facts(), cost);
            if (!verdict.eligible()) {
                rejections.addAll(verdict.reasons());
                continue;
            }
            RecipeSnapshot snapshot = NutritionSnapshots.snapshotOf(recipe,
                    catalog.ingredientsById());
            locked.add(new LockedItem(recipe.getRole(), requested.servings(),
                    new Candidate(candidate, snapshot, cost.orElse(null), requested.servings())));
        }
        return new LockedItems(List.copyOf(locked), List.copyOf(rejections));
    }

    // =========================================================================================
    // Mục tiêu của ngày
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
    private Targets targetsOf(ProfileSnapshot profile, PreviewRequest request,
                              PlanStructure structure) {
        BigDecimal share = structure.shareSum();
        BigDecimal energyPerDay = profile.energyEstimate().kcalPerDay();
        BigDecimal energy = (energyPerDay == null || share == null)
                ? null : energyPerDay.multiply(share);

        Map<String, BigDecimal> micronutrients = new LinkedHashMap<>();
        if (share != null) {
            for (var target : calculator.rules().micronutrientTargets()) {
                if (target.amountPerDay() == null) {
                    continue;
                }
                micronutrients.put(target.code(), target.amountPerDay().multiply(share));
            }
        }

        var constraints = request.constraintsOrEmpty();
        return new Targets(energy, micronutrients,
                constraints.dailyBudgetVnd() == null
                        ? null : new BigDecimal(constraints.dailyBudgetVnd()),
                constraints.maxMinutesPerMeal(),
                constraints.strictBudgetEnabled(), constraints.strictTimeEnabled());
    }

    /**
     * Ngữ cảnh chấm điểm khẩu vị và đa dạng cho bộ sinh.
     *
     * <p>Bốn trường mục tiêu để rỗng là <strong>cố ý</strong>: trong bộ sinh, dinh dưỡng, chi phí và
     * thời gian được đánh giá ở cấp ngày bằng hàm J, nên chấm lại chúng ở cấp món sẽ đếm hai lần
     * cùng một thứ.</p>
     */
    private RankingInputs preferenceInputs(ProfileSnapshot profile, PreviewRequest request) {
        return new RankingInputs(null, Map.of(), null, null,
                profile.favoriteRecipeIds(), profile.dislikedIngredientIds(),
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), request.seedOrDefault());
    }

    /** Ngữ cảnh xếp hạng ứng viên <em>trong một vị trí</em> — công thức điểm món của nhánh 4. */
    private RankingInputs slotInputs(ProfileSnapshot profile, PreviewRequest request,
                                     BigDecimal share) {
        var constraints = request.constraintsOrEmpty();
        BigDecimal budgetPerSlot = (constraints.dailyBudgetVnd() == null || share == null)
                ? null : new BigDecimal(constraints.dailyBudgetVnd()).multiply(share);
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
                constraints.maxMinutesPerMeal(), profile.favoriteRecipeIds(),
                profile.dislikedIngredientIds(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(),
                request.seedOrDefault());
    }

    /**
     * Tỉ lệ năng lượng tham khảo của một bữa.
     *
     * <p>Bữa phụ chia đều cho {@code snack_count} (QĐ-02): ba bữa phụ không được mỗi bữa mang trọn
     * 15% của ngày. Không có template thì trả null, và khi đó nhánh năng lượng bị bỏ hẳn chứ không
     * mượn một tỉ lệ mặc định.</p>
     */
    private BigDecimal shareOf(MealType mealType, short slotCount) {
        Optional<BigDecimal> share = templates.findAll().stream()
                .filter(template -> template.getMealType() == mealType)
                .map(MealTemplateEntity::getRoleTargetShare)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder());
        if (share.isEmpty()) {
            return null;
        }
        return slotCount > 1 ? share.get().divide(new BigDecimal(slotCount), MATH) : share.get();
    }

    private List<MealTemplateEntity> templatesFor(MealType mealType) {
        // Ít vai trò hơn đứng trước: "ưu tiên ít bước" của template bữa sáng, và cũng là cấu trúc
        // dễ hoàn thành hơn khi danh mục còn mỏng.
        return templates.findAll().stream()
                .filter(template -> template.getMealType() == mealType)
                .sorted(Comparator.comparingInt((MealTemplateEntity t) -> t.getRequiredRoles().size())
                        .thenComparing(t -> t.getMode().name()))
                .toList();
    }

    /** Tập khẩu phần cho phép của template, dạng {@code "0.5,1,1.5,2"}. */
    private List<BigDecimal> servingOptions(MealTemplateEntity template) {
        String raw = template.getDefaultServingOptions();
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
    // Dựng phản hồi
    // =========================================================================================

    private PreviewResponse noMatch(ProfileSnapshot profile, String rulesVersion,
                                    PreviewRequest request, LocalDate date,
                                    PlanStructure structure, List<ReasonNote> notes) {
        List<SlotView> slots = new ArrayList<>();
        structure.failures().forEach(failure -> slots.add(new SlotView(
                failure.mealType(), failure.sequence(), null, null, List.of(), false, List.of(),
                views(failure.reasons()))));
        DayView day = new DayView(date, List.copyOf(slots), Map.of(), List.of(), null, List.of());
        return new PreviewResponse(null, GenerationStatus.NO_MATCH, profile.profileVersion(),
                rulesVersion, request.seedOrDefault(), CoverageStatus.PARTIAL, null,
                views(notes), List.of(day));
    }

    private PreviewResponse render(ProfileSnapshot profile, String rulesVersion,
                                   PreviewRequest request, LocalDate date, PlanStructure structure,
                                   Outcome outcome, Targets targets, List<ReasonNote> notes) {
        NutritionTotals totals = generator.totalsOf(outcome.choices());
        BigDecimal dayCost = generator.totalCost(outcome.choices());

        if (totals.coverage() != CoverageStatus.COMPLETE) {
            notes.add(ReasonNote.of(RecommendationReason.NUTRITION_DATA_INCOMPLETE));
        }
        if (targets.dayBudgetVnd() != null && dayCost != null
                && dayCost.compareTo(targets.dayBudgetVnd()) > 0) {
            notes.add(ReasonNote.of(RecommendationReason.DAY_OVER_BUDGET, dayCost.toPlainString()));
        }
        if (targets.maxMinutesPerMeal() != null
                && generator.minutesBySlot(outcome.choices()).values().stream()
                        .anyMatch(minutes -> minutes > targets.maxMinutesPerMeal())) {
            notes.add(ReasonNote.of(RecommendationReason.MEAL_OVER_TIME,
                    String.valueOf(targets.maxMinutesPerMeal())));
        }
        if (structure.partialDay()) {
            notes.add(ReasonNote.of(RecommendationReason.PARTIAL_DAY_PLAN,
                    structure.slots().stream().map(slot -> slot.mealType().name())
                            .distinct().collect(Collectors.joining(", "))));
        }

        List<SlotView> slots = new ArrayList<>();
        for (int index = 0; index < structure.slots().size(); index++) {
            Slot slot = structure.slots().get(index);
            List<ItemView> items = new ArrayList<>();
            for (Choice choice : outcome.choicesOf(index)) {
                items.add(itemView(choice));
            }
            slots.add(new SlotView(slot.mealType(), slot.sequence(), slot.templateCode(),
                    slot.mode(), slot.roles(), true, List.copyOf(items), List.of()));
        }

        DayView day = new DayView(date, List.copyOf(slots), nutritionView(totals),
                totals.methodNotes(), dayCost, List.of());

        return new PreviewResponse(null, statusOf(notes), profile.profileVersion(), rulesVersion,
                request.seedOrDefault(), totals.coverage(), null, views(notes), List.of(day));
    }

    /**
     * {@code READY} khi không còn gì để người dùng phải xem lại.
     *
     * <p>{@code PARTIAL_DAY_PLAN} <strong>không</strong> hạ trạng thái: lập một phần ngày là lựa
     * chọn của người dùng, không phải một khiếm khuyết của kế hoạch. Mọi ghi chú còn lại đều là
     * "còn ưu tiên mềm chưa trọn" hoặc "còn thiếu dữ liệu", nên chúng đưa kế hoạch sang
     * {@code READY_WITH_NOTES}.</p>
     */
    public static GenerationStatus statusOf(List<ReasonNote> notes) {
        boolean needsReview = notes.stream()
                .anyMatch(note -> note.reason() != RecommendationReason.PARTIAL_DAY_PLAN);
        return needsReview ? GenerationStatus.READY_WITH_NOTES : GenerationStatus.READY;
    }

    private ItemView itemView(Choice choice) {
        RecipeCandidate recipe = choice.candidate().recipe();
        return new ItemView(recipe.recipeId(), recipe.name(), recipe.role(), recipe.recipeVersion(),
                choice.servings(), recipe.minutes(), choice.candidate().locked(),
                choice.costVnd(), List.of());
    }

    /**
     * Khối dinh dưỡng của ngày, đúng hình dạng đã công bố ở nhánh 2.
     *
     * <p>{@code known_value} null vẫn <strong>xuất hiện</strong>: đó là cách duy nhất để giao diện
     * nói "chưa đủ dữ liệu cho chất này" thay vì tô đầy thanh tiến trình (bất biến 1).</p>
     */
    private Map<String, NutrientView> nutritionView(NutritionTotals totals) {
        Map<String, NutrientView> view = new LinkedHashMap<>();
        NutrientTotal energy = totals.nutrient(NutritionCalculator.ENERGY).orElse(null);
        if (energy != null) {
            view.put(NutritionCalculator.ENERGY, nutrientView(energy));
        }
        totals.byNutrient().entrySet().stream()
                .filter(entry -> !NutritionCalculator.ENERGY.equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> view.put(entry.getKey(), nutrientView(entry.getValue())));
        return view;
    }

    private NutrientView nutrientView(NutrientTotal total) {
        return new NutrientView(total.knownValue(), total.unit(), total.coverageStatus(),
                total.missingIngredientIds());
    }

    private List<ReasonView> views(List<ReasonNote> notes) {
        List<ReasonView> views = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ReasonNote note : notes) {
            String key = note.code() + "|" + note.detail();
            if (seen.add(key)) {
                views.add(new ReasonView(note.code(), note.message()));
            }
        }
        return List.copyOf(views);
    }

    // =========================================================================================
    // POST /api/v1/meal-plans — lưu bản xem trước
    // =========================================================================================

    /**
     * Lưu bản xem trước thành kế hoạch.
     *
     * <p>Bốn điều kiện, kiểm <strong>lại</strong> ở đây chứ không tin vào lúc tạo bản xem trước: bản
     * còn hạn, hồ sơ chưa đổi, tập quy tắc chưa đổi, và từng công thức vẫn ở đúng phiên bản đã dùng.
     * Ba mươi phút là thừa đủ để người dùng sửa hồ sơ ở một thiết bị khác.</p>
     */
    @Transactional
    public PlanView save(String userId, SavePlanRequest request) {
        MealPlanPreviewEntity preview = previews.findByIdAndUserId(request.previewId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy bản xem trước này."));

        if (preview.getSavedPlanId() != null) {
            // Bấm Lưu hai lần, hoặc mạng gửi lại: trả về đúng kế hoạch đã tạo, không tạo bản thứ hai.
            return get(userId, preview.getSavedPlanId());
        }
        if (preview.isExpiredAt(now())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Bản xem trước đã hết hạn. Hãy tạo lại thực đơn rồi lưu.");
        }
        if (preview.getStatus() == GenerationStatus.NO_MATCH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Bản xem trước này không có thực đơn nào để lưu.");
        }

        ProfileSnapshot profile = profiles.snapshot(userId);
        if (profile.profileVersion() != preview.getProfileVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Hồ sơ ăn uống đã thay đổi sau khi tạo bản xem trước. Hãy tạo lại thực đơn.");
        }
        String rulesVersion = calculator.rules().rulesVersion();
        if (!rulesVersion.equals(preview.getRulesVersion())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Dữ liệu dinh dưỡng đã được cập nhật. Hãy tạo lại thực đơn.");
        }

        PreviewResponse payload = readPayload(preview);
        MealPlanEntity plan = toEntity(userId, payload, rulesVersion);
        plans.saveAndFlush(plan);
        preview.setSavedPlanId(plan.getId());
        previews.saveAndFlush(preview);

        return view(plan);
    }

    private MealPlanEntity toEntity(String userId, PreviewResponse payload, String rulesVersion) {
        DayView day = payload.days().get(0);
        Map<String, IngredientEntity> ingredientsById = loadIngredients();

        MealPlanEntity plan = new MealPlanEntity();
        plan.setUserId(userId);
        plan.setStartDate(day.localDate());
        plan.setEndDate(day.localDate());
        plan.setGenerationStatus(payload.status());
        plan.setLifecycleState(PlanLifecycleState.SAVED);
        plan.setPlanVersion(1);
        plan.setProfileVersion(payload.profileVersion());
        plan.setRulesVersion(rulesVersion);

        for (SlotView slotView : day.slots()) {
            MealSlotEntity slot = new MealSlotEntity();
            slot.setLocalDate(day.localDate());
            slot.setMealType(slotView.mealType());
            slot.setSequence(slotView.sequence());
            slot.setTemplateCode(slotView.templateCode());
            plan.add(slot);

            for (ItemView itemView : slotView.items()) {
                RecipeEntity recipe = recipes.findById(itemView.recipeId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_CONFLICT,
                                "Món trong bản xem trước không còn trong danh mục. "
                                        + "Hãy tạo lại thực đơn."));
                if (recipe.getVersion() != itemView.recipeVersion()) {
                    throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                            "Công thức đã được cập nhật sau khi tạo bản xem trước. "
                                    + "Hãy tạo lại thực đơn.");
                }
                MealPlanItemEntity item = new MealPlanItemEntity();
                item.setRecipeId(recipe.getId());
                item.setRecipeVersion(recipe.getVersion());
                item.setServings(itemView.servings());
                item.setLocked(itemView.locked());
                item.setNutrientSnapshot(json.writeValueAsString(
                        NutritionSnapshots.snapshotOf(recipe, ingredientsById)));
                item.setCostSnapshot(itemView.estimatedCostVnd());
                slot.add(item);
            }
        }
        return plan;
    }

    // =========================================================================================
    // GET /api/v1/meal-plans/{id}
    // =========================================================================================

    @Transactional(readOnly = true)
    public PlanView get(String userId, String planId) {
        MealPlanEntity plan = plans.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy thực đơn này."));
        return view(plan);
    }

    /**
     * Dựng lại kế hoạch đã lưu, <strong>từ snapshot</strong>.
     *
     * <p>Tổng dinh dưỡng tính lại bằng chính {@link NutritionCalculator} trên snapshot đã lưu, nên
     * nó bằng đúng con số của bản xem trước kể cả sau khi seed đổi công thức (bất biến 4 và BR17).
     * Vai trò và thời gian nấu thì đọc từ danh mục hiện tại: chúng là thông tin hiển thị, không
     * tham gia phép tính nào.</p>
     */
    private PlanView view(MealPlanEntity plan) {
        List<Choice> choices = new ArrayList<>();
        List<SlotView> slots = new ArrayList<>();
        List<MealSlotEntity> ordered = plan.getSlots().stream()
                .sorted(Comparator.comparing(MealSlotEntity::getLocalDate)
                        .thenComparing(slot -> slot.getMealType().ordinal())
                        .thenComparing(MealSlotEntity::getSequence))
                .toList();

        Map<String, RecipeEntity> recipesById = new HashMap<>();
        for (MealSlotEntity slot : ordered) {
            for (MealPlanItemEntity item : slot.getItems()) {
                recipesById.computeIfAbsent(item.getRecipeId(),
                        id -> recipes.findById(id).orElse(null));
            }
        }

        List<NutritionSnapshots.Portion> portions = new ArrayList<>();
        for (MealSlotEntity slot : ordered) {
            List<ItemView> items = new ArrayList<>();
            for (MealPlanItemEntity item : slot.getItems()) {
                RecipeSnapshot snapshot = json.readValue(item.getNutrientSnapshot(),
                        RecipeSnapshot.class);
                portions.add(new NutritionSnapshots.Portion(snapshot, item.getServings()));
                RecipeEntity recipe = recipesById.get(item.getRecipeId());
                items.add(new ItemView(item.getRecipeId(), snapshot.name(),
                        recipe == null ? null : recipe.getRole(), item.getRecipeVersion(),
                        item.getServings(), recipe == null ? null : recipe.getMinutes(),
                        item.isLocked(), item.getCostSnapshot(), List.of()));
            }
            slots.add(new SlotView(slot.getMealType(), slot.getSequence(), slot.getTemplateCode(),
                    slot.mode(), items.stream()
                            .map(ItemView::role).filter(java.util.Objects::nonNull).toList(),
                    true, List.copyOf(items), List.of()));
        }

        NutritionTotals totals = calculator.forPortions(portions);
        BigDecimal cost = totalCostOf(ordered);
        List<ReasonNote> notes = new ArrayList<>();
        if (totals.coverage() != CoverageStatus.COMPLETE) {
            notes.add(ReasonNote.of(RecommendationReason.NUTRITION_DATA_INCOMPLETE));
        }

        DayView day = new DayView(plan.getStartDate(), List.copyOf(slots), nutritionView(totals),
                totals.methodNotes(), cost, List.of());

        return new PlanView(plan.getId(), plan.getGenerationStatus(), plan.getLifecycleState(),
                plan.getPlanVersion(), plan.getProfileVersion(), plan.getRulesVersion(),
                plan.getStartDate(), plan.getEndDate(), totals.coverage(), views(notes),
                List.of(day));
    }

    /** Tổng chi phí của kế hoạch; null ngay khi <strong>một</strong> mục chưa có giá. */
    private BigDecimal totalCostOf(List<MealSlotEntity> slots) {
        BigDecimal total = BigDecimal.ZERO;
        for (MealSlotEntity slot : slots) {
            for (MealPlanItemEntity item : slot.getItems()) {
                if (item.getCostSnapshot() == null) {
                    return null;
                }
                total = total.add(item.getCostSnapshot());
            }
        }
        return total;
    }

    // =========================================================================================
    // Hạ tầng chung
    // =========================================================================================

    private void requireMatchingProfileVersion(Integer sent, int current) {
        if (sent != null && sent != current) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Hồ sơ ăn uống đã thay đổi. Hãy tải lại hồ sơ rồi tạo thực đơn.");
        }
    }

    private PreviewResponse readPayload(MealPlanPreviewEntity preview) {
        PreviewResponse stored = json.readValue(preview.getPayload(), PreviewResponse.class);
        return withPreviewId(stored, preview.getId(), preview.getExpiresAt());
    }

    private PreviewResponse withPreviewId(PreviewResponse response, String previewId,
                                          OffsetDateTime expiresAt) {
        // NO_MATCH vẫn có preview_id và hạn dùng: lần gửi lại cùng request_id phải nhận đúng câu
        // trả lời cũ, còn id để đối chiếu khi đi tìm lỗi.
        return new PreviewResponse(previewId, response.status(), response.profileVersion(),
                response.rulesVersion(), response.seed(), response.nutritionCoverage(), expiresAt,
                response.notes(), response.days());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * Danh mục nguyên liệu và giá, nạp <strong>một lượt</strong> cho cả lượt lập thực đơn.
     *
     * <p>Bước lọc phải đi xuống nguyên liệu con của từng dòng công thức; truy vấn từng mã trong
     * vòng lặp sẽ sinh ra hàng trăm câu SELECT cho một lần bấm nút.</p>
     */
    private CatalogContext loadCatalog() {
        Map<String, IngredientEntity> ingredientsById = loadIngredients();
        Map<String, IngredientFacts> facts = ingredientsById.values().stream()
                .collect(Collectors.toMap(IngredientEntity::getId, IngredientFacts::of));
        Map<String, PriceEstimateEntity> pricesByIngredient = prices.findAll().stream()
                .collect(Collectors.toMap(PriceEstimateEntity::getIngredientId,
                        Function.identity()));
        return new CatalogContext(ingredientsById, facts, pricesByIngredient);
    }

    private Map<String, IngredientEntity> loadIngredients() {
        return ingredients.findAll().stream()
                .collect(Collectors.toMap(IngredientEntity::getId, Function.identity()));
    }

    // =========================================================================================
    // Kiểu dữ liệu nội bộ
    // =========================================================================================

    /** Danh mục nguyên liệu và giá, nạp một lượt cho cả lượt lập thực đơn. */
    private record CatalogContext(Map<String, IngredientEntity> ingredientsById,
                                  Map<String, IngredientFacts> facts,
                                  Map<String, PriceEstimateEntity> pricesByIngredient) {
    }

    /** Cấu trúc ngày đã dựng: các bữa lấp được, các bữa không, và tổng tỉ lệ năng lượng của chúng. */
    private record PlanStructure(List<Slot> slots, List<SlotFailure> failures,
                                 BigDecimal shareSum) {

        boolean feasible() {
            return failures.isEmpty() && !slots.isEmpty();
        }

        /** Kế hoạch chỉ gồm một phần các bữa trong ngày (TC23). */
        boolean partialDay() {
            return shareSum != null && shareSum.compareTo(BigDecimal.ONE) < 0;
        }
    }

    /** Một bữa không lấp được, kèm lý do nêu rõ vai trò và bộ lọc. */
    private record SlotFailure(MealType mealType, short sequence, List<ReasonNote> reasons) {
    }

    /** Kết quả dựng một bữa. {@code slot} null nghĩa là không cấu trúc nào khả thi. */
    private record SlotBuild(Slot slot, List<ReasonNote> failures, boolean searchLimitHit,
                             int droppedForMissingPrice) {
    }

    /** Ứng viên của một vị trí, kèm những gì cần để giải thích khi danh sách rỗng. */
    private record CandidateSearch(List<Candidate> candidates, List<String> blockingCodes,
                                   boolean hitScanLimit, int droppedForMissingPrice) {
    }

    /** Một món người dùng khóa, đã qua bước lọc. */
    private record LockedItem(RecipeRole role, BigDecimal servings, Candidate candidate) {
    }

    /**
     * Món khóa của một bữa, tách thành "đặt được" và "bị từ chối".
     *
     * <p>Có một món bị từ chối là <strong>cả bữa hỏng</strong>, không phải một vị trí trống để lấp
     * bằng món khác: người dùng đã nói rõ họ muốn giữ món đó.</p>
     */
    private record LockedItems(List<LockedItem> items, List<ReasonNote> rejections) {

        boolean anyRejected() {
            return !rejections.isEmpty();
        }
    }
}
