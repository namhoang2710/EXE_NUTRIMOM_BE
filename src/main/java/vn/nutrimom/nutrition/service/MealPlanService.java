package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
import vn.nutrimom.nutrition.service.NutritionSnapshots.Portion;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.PlanRevalidationService.Revalidation;
import vn.nutrimom.nutrition.service.NutritionTotals.NutrientTotal;

/**
 * Lập thực đơn một ngày: hồ sơ → cấu trúc bữa → lọc → xếp hạng → ghép → bản xem trước lưu được.
 *
 * <p>Lớp này làm bước 1, 2, 3 và 8 của tám bước; bước 4–7 (mở rộng, loại, chấm điểm, giữ beam) nằm
 * ở {@link MealPlanGenerator}, còn việc biến công thức thành ứng viên thuộc
 * {@link MealPlanCandidates} — dùng chung với nhánh 6 để chỉ có một đường qua bước lọc.</p>
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

    private final NutritionProfileService profiles;
    private final RecipeRepository recipes;
    private final MealPlanRepository plans;
    private final MealPlanPreviewRepository previews;
    private final MealPlanCandidates candidates;
    private final NutritionCalculator calculator;
    private final MealPlanGenerator generator;
    private final WeeklyFoodLimits weeklyLimits;
    private final MealFeedbackService feedbacks;
    private final PlanRevalidationService revalidations;
    private final ObjectMapper json;
    private final Clock clock;

    public MealPlanService(NutritionProfileService profiles, RecipeRepository recipes,
                           MealPlanRepository plans, MealPlanPreviewRepository previews,
                           MealPlanCandidates candidates, NutritionCalculator calculator,
                           MealPlanGenerator generator, WeeklyFoodLimits weeklyLimits,
                           MealFeedbackService feedbacks, PlanRevalidationService revalidations,
                           ObjectMapper json, Clock clock) {
        this.profiles = profiles;
        this.recipes = recipes;
        this.plans = plans;
        this.previews = previews;
        this.candidates = candidates;
        this.calculator = calculator;
        this.generator = generator;
        this.weeklyLimits = weeklyLimits;
        this.feedbacks = feedbacks;
        this.revalidations = revalidations;
        this.json = json;
        this.clock = clock;
    }

    // =========================================================================================
    // POST /api/v1/meal-plans/preview
    // =========================================================================================

    /**
     * Tạo bản xem trước cho một, ba hoặc bảy ngày.
     *
     * <h2>Chỗ duy nhất của cả module để chặn theo gói (QĐ-04)</h2>
     *
     * <p>MVP <strong>không gate gì</strong>. Khi nhóm quyết định bán, dòng dưới đây đặt ngay sau
     * phép kiểm {@code request_id}, và <strong>chỉ cho {@code dayCountOrDefault() != 1}</strong>:
     * </p>
     *
     * <pre>
     * entitlements.requireFeature(userId, PlanFeature.PERSONALISED_GUIDANCE);
     * </pre>
     *
     * <p>Dùng lại key đã có; <strong>không thêm giá trị mới vào {@code PlanFeature}</strong> —
     * enum đó khớp cứng thứ tự với {@code pricing-catalog.ts} bên FE, thêm một dòng là dán sai
     * nhãn cả bảng giá. Lỗi trả {@code PLAN_UPGRADE_REQUIRED} (403), đã có sẵn.</p>
     *
     * <p><strong>Không bao giờ</strong> gate lọc dị ứng, cảnh báo thành phần, điều kiện chế biến
     * hay giới hạn thực phẩm theo tuần: đó là an toàn, không phải quyền lợi bán.</p>
     */
    @Transactional
    public PreviewResponse preview(String userId, PreviewRequest request) {
        requireUsableRequestId(request.requestId());
        ProfileSnapshot profile = profiles.snapshot(userId);
        requireMatchingProfileVersion(request.profileVersion(), profile.profileVersion());

        String fingerprint = PreviewFingerprint.of(request, json);
        Optional<PreviewResponse> replayed = replay(userId, request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        PreviewResponse response = generate(profile, request);
        return store(userId, request.requestId(), fingerprint, profile.profileVersion(), response);
    }

    /**
     * Gửi lại cùng {@code request_id} trả lại <strong>đúng bản cũ</strong> (_CONTRACT.md mục 6) —
     * nhưng chỉ khi nội dung yêu cầu cũng y như cũ.
     *
     * <p>Bản đã hết hạn thì xóa lười ngay tại đây rồi sinh bản mới: giữ lại một bản chết chỉ để
     * khóa {@code request_id} sẽ làm người dùng bấm "Tạo thực đơn" mà không bao giờ ra được kết quả
     * mới.</p>
     */
    private Optional<PreviewResponse> replay(String userId, String requestId, String fingerprint) {
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
        return Optional.of(replayOf(preview, fingerprint));
    }

    /** Đọc payload đúng một lần: đối chiếu dấu vân tay rồi dựng body từ chính cây JSON đó. */
    private PreviewResponse replayOf(MealPlanPreviewEntity preview, String fingerprint) {
        JsonNode payload = json.readTree(preview.getPayload());
        requireSameRequest(payload, fingerprint);
        return withPreviewId(responseOf(payload), preview.getId(), preview.getExpiresAt());
    }

    /**
     * Cùng {@code request_id} nhưng body khác là <strong>lỗi của client</strong>, không phải một
     * lần gửi lại.
     *
     * <p>Trả bản cũ ở đây là nói dối: client vừa sửa yêu cầu — thêm {@code locked_items}, đổi
     * {@code start_date} — và sẽ tin rằng yêu cầu mới đã chạy. {@code IDEMPOTENCY_CONFLICT} (409)
     * đã có sẵn trong {@code ErrorCode} và _CONTRACT.md mục 3 xếp nó vào nhóm dùng lại; module
     * assistant đã xử đúng kiểu này ở {@code AssistantStore}.</p>
     *
     * <p>Dòng ghi trước khi có dấu vân tay thì không có gì để đối chiếu — giữ nguyên hành vi cũ
     * thay vì báo xung đột oan. Xem {@link PreviewFingerprint} về khoảng mù nửa giờ đó.</p>
     */
    private void requireSameRequest(JsonNode payload, String fingerprint) {
        JsonNode stored = payload.path(PreviewFingerprint.PAYLOAD_FIELD);
        if (!stored.isString()) {
            return;
        }
        if (!stored.stringValue().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "request_id này đã dùng cho một yêu cầu tạo thực đơn khác. "
                            + "Hãy gửi lại với request_id mới.",
                    Map.of("request_id", "đã gắn với một yêu cầu khác"));
        }
    }

    private PreviewResponse store(String userId, String requestId, String fingerprint,
                                  int profileVersion, PreviewResponse response) {
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
        ObjectNode payload = json.valueToTree(stored);
        payload.put(PreviewFingerprint.PAYLOAD_FIELD, fingerprint);
        preview.setPayload(json.writeValueAsString(payload));

        try {
            previews.saveAndFlush(preview);
        } catch (DataIntegrityViolationException duplicateRequestId) {
            // Hai lượt gửi song song cùng request_id: unique (user_id, request_id) chặn bản thứ
            // hai. Đọc lại bản thắng — nhưng vẫn đối chiếu body: "song song" không có nghĩa là
            // "cùng nội dung", và hai yêu cầu khác nhau đua nhau trên một khóa vẫn là xung đột.
            MealPlanPreviewEntity winner = previews.findByUserIdAndRequestId(userId, requestId)
                    .orElseThrow(() -> duplicateRequestId);
            return replayOf(winner, fingerprint);
        }
        return stored;
    }

    /**
     * Một tiền tố của {@code request_id} dành riêng cho bản hoàn tác của nhánh 6.
     *
     * <p>Hoàn tác lưu ảnh chụp kế hoạch vào chính bảng bản xem trước (xem {@link PlanRevision}),
     * nên một {@code request_id} do người dùng gửi lên mà trùng tiền tố đó sẽ đọc trúng một payload
     * không phải bản xem trước. Chặn ngay ở cửa vào rẻ hơn nhiều so với việc mọi chỗ đọc payload
     * phải tự đoán mình đang cầm loại nào.</p>
     */
    private void requireUsableRequestId(String requestId) {
        if (requestId != null && requestId.startsWith(PlanRevision.REQUEST_ID_PREFIX)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "request_id không được bắt đầu bằng \"" + PlanRevision.REQUEST_ID_PREFIX
                            + "\" — tiền tố này dành cho bản hoàn tác kế hoạch.",
                    Map.of("request_id", "tiền tố \"" + PlanRevision.REQUEST_ID_PREFIX
                            + "\" đã được hệ thống dùng"));
        }
    }

    // =========================================================================================
    // Sinh thực đơn
    // =========================================================================================

    /**
     * Lập một, ba hoặc bảy ngày — <strong>một cấu trúc, nhiều lượt ghép</strong> (nhánh 9).
     *
     * <p>Cấu trúc bữa và danh sách ứng viên không phụ thuộc ngày: cùng hồ sơ, cùng danh mục, cùng
     * giới hạn thì ngày thứ bảy cũng lọc ra đúng tập món của ngày thứ nhất. Nên bước 2 và 3 chạy
     * <strong>một lượt</strong> rồi dùng lại; chỉ bước ghép lặp theo ngày. Dựng lại cấu trúc cho
     * từng ngày là lọc cả danh mục bảy lần cho một lần bấm nút, và đó mới là thứ làm vỡ hạn 5
     * giây.</p>
     *
     * <p>Mỗi ngày đi qua bộ sinh với <strong>mục tiêu dinh dưỡng của riêng ngày đó</strong>: gộp
     * bảy ngày vào một hàm mục tiêu sẽ cho phép một ngày thiếu năng lượng được bù bằng một ngày
     * thừa, mà người ta thì ăn theo ngày.</p>
     *
     * <p>Hai thứ nối các ngày lại với nhau: {@link PlanVarietyHistory} (đa dạng liên ngày) và
     * {@link WeeklyAllowance} (giới hạn thực phẩm theo tuần). Cả hai cập nhật sau <em>từng</em>
     * ngày.</p>
     */
    private PreviewResponse generate(ProfileSnapshot profile, PreviewRequest request) {
        String rulesVersion = calculator.rules().rulesVersion();
        List<LocalDate> dates = datesOf(request);
        CatalogContext catalog = candidates.loadCatalog();
        PlanLimits limits = limitsOf(profile, request);

        List<ReasonNote> notes = new ArrayList<>();
        PlanStructure structure = buildStructure(profile, request, limits, catalog, notes);

        if (!structure.feasible()) {
            // Không hoàn thành được vị trí theo điều kiện cứng. HTTP 200 kèm lý do — và lý do nêu
            // rõ bữa nào, vai trò nào, bộ lọc nào (TC18).
            notes.add(ReasonNote.of(RecommendationReason.NO_MATCH));
            return noMatch(profile, rulesVersion, request, dates, structure, notes);
        }

        Targets targets = candidates.targetsOf(profile, limits, structure.shareSum());
        RankingInputs preferences = candidates.preferenceInputs(profile, limits);
        // Hạn của CẢ kế hoạch, không phải mỗi ngày một hạn: bảy ngày chia nhau 5 giây.
        SearchDeadline deadline =
                SearchDeadline.of(SearchDeadline.budgetFor(request.dayCountOrDefault()));

        WeeklyAllowance allowance = weeklyLimits.allowanceFor(snapshotsOf(structure));
        PlanVarietyHistory history = PlanVarietyHistory.empty();
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> repeatedDishes = new LinkedHashSet<>();

        for (int day = 0; day < dates.size(); day++) {
            Outcome outcome = generator.generate(structure.slots(), targets,
                    history.applyTo(preferences), deadline, allowance);

            if (!outcome.complete()) {
                // Một ngày không ghép được thì cả kế hoạch không ghép được: sáu ngày rưỡi không
                // phải thứ người dùng yêu cầu, và lưu nó lại sẽ tạo ra một tuần thiếu ngày mà
                // giao diện vẫn mở ra được. Hết giờ và hết phương án vẫn là hai chuyện khác hẳn
                // nhau (TC19).
                notes.add(ReasonNote.of(outcome.searchLimit()
                        ? RecommendationReason.SEARCH_LIMIT : RecommendationReason.NO_MATCH));
                if (outcome.searchLimit()) {
                    notes.add(ReasonNote.of(RecommendationReason.NO_MATCH));
                }
                return noMatch(profile, rulesVersion, request, dates, structure, notes);
            }

            // Đọc lịch sử TRƯỚC khi ghi ngày này vào: "lặp lại" nghĩa là so với các ngày trước.
            repeatedDishes.addAll(history.repeatsOf(outcome.choices()));
            allowance = allowance.consume(outcome.choices());
            history = history.after(outcome.choices());
            outcomes.add(outcome);
        }

        if (!repeatedDishes.isEmpty()) {
            // Một ghi chú cho cả kế hoạch, không phải một ghi chú mỗi ngày: với bảy ngày thì cùng
            // một món tinh bột sẽ sinh sáu dòng gần giống nhau ở đầu trang.
            notes.add(ReasonNote.of(RecommendationReason.REPEATED_RECIPE_ACROSS_DAYS,
                    String.join(", ", repeatedDishes)));
        }
        notes.addAll(weeklyLimits.notes(allowance));

        return render(profile, rulesVersion, request, dates, structure, outcomes, targets, notes);
    }

    /** Các ngày của kế hoạch, liên tiếp từ {@code start_date}. */
    private List<LocalDate> datesOf(PreviewRequest request) {
        List<LocalDate> dates = new ArrayList<>();
        for (int day = 0; day < request.dayCountOrDefault(); day++) {
            dates.add(request.startDate().plusDays(day));
        }
        return List.copyOf(dates);
    }

    /**
     * Mọi công thức có thể xuất hiện trong kế hoạch.
     *
     * <p>Giới hạn theo tuần quy sẵn gram của từng món trước khi vào vòng lặp, nên tập này phải
     * <strong>đủ</strong>: một món không có ở đây mà lại được chọn sẽ được tính là 0 g, tức là một
     * điều kiện an toàn bị bỏ qua trong im lặng.</p>
     */
    private List<RecipeSnapshot> snapshotsOf(PlanStructure structure) {
        List<RecipeSnapshot> snapshots = new ArrayList<>();
        for (Slot slot : structure.slots()) {
            for (Position position : slot.positions()) {
                for (Candidate candidate : position.candidates()) {
                    snapshots.add(candidate.snapshot());
                }
            }
        }
        return snapshots;
    }

    /** Giới hạn của lượt xem trước này, dạng chung mà {@link MealPlanCandidates} hiểu. */
    /**
     * Bối cảnh của lượt lập này.
     *
     * <p>Phản hồi sau bữa nạp <strong>một lượt ở đây</strong> rồi đi cùng {@code limits} xuống cả
     * hai đường chấm điểm — chọn ứng viên cho từng vị trí và chấm lại điểm P trong bộ sinh. Nạp ở
     * một chỗ duy nhất là cách duy nhất để hai đường đó không bao giờ cầm hai bộ dữ liệu khác
     * nhau, và cũng tránh một câu truy vấn giống hệt nhau cho mỗi vai trò của mỗi bữa.</p>
     */
    private PlanLimits limitsOf(ProfileSnapshot profile, PreviewRequest request) {
        var constraints = request.constraintsOrEmpty();
        return new PlanLimits(
                constraints.dailyBudgetVnd() == null
                        ? null : new BigDecimal(constraints.dailyBudgetVnd()),
                constraints.maxMinutesPerMeal(), constraints.strictBudgetEnabled(),
                constraints.strictTimeEnabled(), request.seedOrDefault(),
                feedbacks.latestSignals(profile.userId()));
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
                                         PlanLimits limits, CatalogContext catalog,
                                         List<ReasonNote> notes) {
        List<Slot> slots = new ArrayList<>();
        List<SlotFailure> failures = new ArrayList<>();
        BigDecimal shareSum = BigDecimal.ZERO;
        boolean searchLimitHit = false;
        int droppedForMissingPrice = 0;

        for (MealType mealType : EnumSet.copyOf(request.mealTypes())) {
            short count = mealType == MealType.SNACK ? request.snackCountOrDefault() : 1;
            for (short sequence = 1; sequence <= count; sequence++) {
                BigDecimal share = candidates.shareOf(mealType, count);
                SlotBuild build =
                        buildSlot(profile, request, limits, catalog, mealType, sequence, share);
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

    private SlotBuild buildSlot(ProfileSnapshot profile, PreviewRequest request, PlanLimits limits,
                                CatalogContext catalog, MealType mealType, short sequence,
                                BigDecimal share) {
        List<ReasonNote> failures = new ArrayList<>();
        // Món khóa và món tự chọn đi qua đúng cùng một bộ điều kiện của MealPlanCandidates, nên
        // không có đường nào để món khóa đi tắt (bất biến 2).
        LockedItems requested =
                lockedItemsFor(request, profile, limits, catalog, mealType, sequence);
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

        for (MealTemplateEntity template : candidates.templatesFor(mealType)) {
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
                CandidateSearch search = candidates.candidatesFor(profile, limits, catalog,
                        mealType, role, share,
                        MealPlanGenerator.EXPANDED_CANDIDATES_PER_POSITION, null);
                searchLimitHit |= search.hitScanLimit();
                dropped += search.droppedForMissingPrice();
                if (search.candidates().isEmpty()) {
                    failures.add(candidates.roleNoCandidate(mealType, sequence, role, search));
                    complete = false;
                    break;
                }
                positions.add(new Position(role, candidates.servingOptions(template),
                        search.candidates()));
            }

            if (complete) {
                return new SlotBuild(new Slot(mealType, sequence, template.getMode(), positions),
                        List.of(), searchLimitHit, dropped);
            }
        }

        if (failures.isEmpty()) {
            failures.add(candidates.templatesFor(mealType).isEmpty()
                    // Danh mục chưa khai cấu trúc nào cho bữa này — lỗi seed, nhưng người dùng vẫn
                    // phải nhận được một câu nói rõ chuyện gì đang xảy ra.
                    ? ReasonNote.of(RecommendationReason.ROLE_NO_CANDIDATE,
                            "cấu trúc của %s (danh mục chưa khai template cho bữa này)"
                                    .formatted(MealPlanCandidates.describeSlot(mealType, sequence)))
                    // Có template, nhưng không cái nào chứa vai trò của món đã khóa.
                    : ReasonNote.of(RecommendationReason.LOCKED_ITEM_UNPLACEABLE,
                            MealPlanCandidates.describeSlot(mealType, sequence)));
        }
        return new SlotBuild(null, List.copyOf(failures), searchLimitHit, dropped);
    }

    /**
     * Món đã khóa, dựng thành ứng viên <strong>sau khi</strong> qua đúng bước lọc của mọi món khác.
     *
     * <p>Khóa một món không mở được đường tắt: món chứa thành phần dị ứng vẫn bị chặn, và kết quả
     * là {@code NO_MATCH} kèm đúng lý do của bước lọc (bất biến 2, BR05).</p>
     */
    private LockedItems lockedItemsFor(PreviewRequest request, ProfileSnapshot profile,
                                       PlanLimits limits, CatalogContext catalog,
                                       MealType mealType, short sequence) {
        List<LockedItem> locked = new ArrayList<>();
        List<ReasonNote> rejections = new ArrayList<>();
        for (var requested : request.lockedItemsOrEmpty()) {
            if (requested.mealType() != mealType || requested.sequenceOrDefault() != sequence) {
                continue;
            }
            RecipeEntity recipe = recipes.findById(requested.recipeId()).orElse(null);
            if (recipe == null || !recipe.getMealTypes().contains(mealType)) {
                rejections.add(ReasonNote.of(RecommendationReason.LOCKED_ITEM_UNPLACEABLE,
                        MealPlanCandidates.describeSlot(mealType, sequence)));
                continue;
            }
            EligibilityVerdict verdict = candidates.judge(recipe, profile, limits, catalog);
            if (!verdict.eligible()) {
                rejections.addAll(verdict.reasons());
                continue;
            }
            locked.add(new LockedItem(recipe.getRole(), requested.servings(),
                    candidates.candidateOf(recipe, catalog, requested.servings())));
        }
        return new LockedItems(List.copyOf(locked), List.copyOf(rejections));
    }

    // =========================================================================================
    // Dựng phản hồi
    // =========================================================================================

    private PreviewResponse noMatch(ProfileSnapshot profile, String rulesVersion,
                                    PreviewRequest request, List<LocalDate> dates,
                                    PlanStructure structure, List<ReasonNote> notes) {
        List<SlotView> slots = new ArrayList<>();
        structure.failures().forEach(failure -> slots.add(new SlotView(
                failure.mealType(), failure.sequence(), null, null, List.of(), false, List.of(),
                views(failure.reasons()))));
        // Cấu trúc hỏng ở đâu thì hỏng ở mọi ngày — nó không phụ thuộc ngày. Vẫn trả đủ số ngày
        // đã yêu cầu để thanh chọn ngày của giao diện không phải đoán kế hoạch dài bao nhiêu.
        List<DayView> days = new ArrayList<>();
        dates.forEach(date -> days.add(new DayView(date, List.copyOf(slots), Map.of(), List.of(),
                null, List.of())));
        return new PreviewResponse(null, GenerationStatus.NO_MATCH, profile.profileVersion(),
                rulesVersion, request.seedOrDefault(), CoverageStatus.PARTIAL, null,
                views(notes), List.copyOf(days));
    }

    /**
     * Dựng phản hồi của cả kế hoạch.
     *
     * <p>Ghi chú đi ra <strong>hai chỗ</strong>: ngày nào có vấn đề gì thì nằm ở
     * {@code days[].notes} kèm con số của chính ngày đó, còn {@code notes} ở mức kế hoạch gom lại
     * theo mã để trả lời "kế hoạch này có gì cần xem". Một kế hoạch bảy ngày vượt ngân sách bốn
     * ngày không nên bắt người đọc lướt qua bốn dòng giống nhau ở đầu trang mới biết là có chuyện,
     * nhưng cũng không được giấu mất ngày nào.</p>
     */
    private PreviewResponse render(ProfileSnapshot profile, String rulesVersion,
                                   PreviewRequest request, List<LocalDate> dates,
                                   PlanStructure structure, List<Outcome> outcomes,
                                   Targets targets, List<ReasonNote> notes) {
        List<DayView> days = new ArrayList<>();
        List<Portion> wholePlan = new ArrayList<>();

        for (int day = 0; day < dates.size(); day++) {
            Outcome outcome = outcomes.get(day);
            NutritionTotals totals = generator.totalsOf(outcome.choices());
            BigDecimal dayCost = generator.totalCost(outcome.choices());
            List<ReasonNote> dayNotes = dayNotes(structure, outcome, targets, totals, dayCost);
            addNewCodes(notes, dayNotes);

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
            for (Choice choice : outcome.choices()) {
                wholePlan.add(new Portion(choice.candidate().snapshot(), choice.servings()));
            }

            days.add(new DayView(dates.get(day), List.copyOf(slots), nutritionView(totals),
                    totals.methodNotes(), dayCost, views(dayNotes)));
        }

        // Độ phủ của CẢ kế hoạch, tính một lượt trên mọi mục: cộng bảy kết luận COMPLETE/PARTIAL
        // lại với nhau sẽ mất danh sách nguyên liệu nào còn thiếu chất nào.
        CoverageStatus coverage = calculator.forPortions(wholePlan).coverage();

        return new PreviewResponse(null, statusOf(notes), profile.profileVersion(), rulesVersion,
                request.seedOrDefault(), coverage, null, views(notes), List.copyOf(days));
    }

    /**
     * Đưa ghi chú của một ngày lên mức kế hoạch, <strong>mỗi mã một lần</strong>.
     *
     * <p>Khác với {@link #views(List)} (bỏ trùng theo cặp mã + dữ liệu): ở mức kế hoạch, bốn ngày
     * vượt ngân sách với bốn con số khác nhau vẫn chỉ là một chuyện cần xem. Con số của từng ngày
     * không mất đi — nó nằm ở {@code days[].notes} của chính ngày đó.</p>
     */
    private void addNewCodes(List<ReasonNote> planNotes, List<ReasonNote> dayNotes) {
        Set<String> seen = planNotes.stream().map(ReasonNote::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (ReasonNote note : dayNotes) {
            if (seen.add(note.code())) {
                planNotes.add(note);
            }
        }
    }

    /** Những gì phải nói về <strong>một ngày</strong>: dữ liệu thiếu, vượt ngân sách, vượt giờ. */
    private List<ReasonNote> dayNotes(PlanStructure structure, Outcome outcome, Targets targets,
                                      NutritionTotals totals, BigDecimal dayCost) {
        List<ReasonNote> notes = new ArrayList<>();
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
            // TC23: kế hoạch bảy ngày chỉ gồm trưa và tối thì TỪNG NGÀY là một phần ngày, không
            // phải cả kế hoạch là một phần tuần.
            notes.add(ReasonNote.of(RecommendationReason.PARTIAL_DAY_PLAN,
                    structure.slots().stream().map(slot -> slot.mealType().name())
                            .distinct().collect(Collectors.joining(", "))));
        }
        return notes;
    }

    /**
     * Ghi chú <strong>mô tả</strong> kế hoạch chứ không nêu điều gì cần xem lại.
     *
     * <p>{@code PARTIAL_DAY_PLAN}: lập một phần ngày là lựa chọn của người dùng, không phải khiếm
     * khuyết của kế hoạch. {@code WEEKLY_FOOD_LIMIT_CHECKED}: nói rằng điều kiện theo tuần đã chạy
     * và kế hoạch nằm trong mức — một kết quả tốt, hạ trạng thái vì nó thì mọi kế hoạch có cá đều
     * mang nhãn "cần xem lại".</p>
     */
    private static final Set<RecommendationReason> DESCRIPTIVE_NOTES = EnumSet.of(
            RecommendationReason.PARTIAL_DAY_PLAN,
            RecommendationReason.WEEKLY_FOOD_LIMIT_CHECKED);

    /**
     * {@code READY} khi không còn gì để người dùng phải xem lại.
     *
     * <p>Mọi ghi chú ngoài {@link #DESCRIPTIVE_NOTES} đều là "còn ưu tiên mềm chưa trọn" hoặc
     * "còn thiếu dữ liệu", nên chúng đưa kế hoạch sang {@code READY_WITH_NOTES}.</p>
     */
    public static GenerationStatus statusOf(List<ReasonNote> notes) {
        boolean needsReview = notes.stream()
                .anyMatch(note -> !DESCRIPTIVE_NOTES.contains(note.reason()));
        return needsReview ? GenerationStatus.READY_WITH_NOTES : GenerationStatus.READY;
    }

    /** Cùng danh sách trên, dạng mã — nhánh 6 tính lại trạng thái từ ghi chú đã dựng. */
    public static boolean describesOnly(String reasonCode) {
        return DESCRIPTIVE_NOTES.stream().anyMatch(reason -> reason.name().equals(reasonCode));
    }

    /**
     * Một món của bản xem trước.
     *
     * <p>{@code item_id} còn trống ở đây là đúng: bản xem trước chưa có dòng nào trong database,
     * nên chưa có mục nào để nhánh 6 trỏ tới. Nó chỉ có giá trị sau khi kế hoạch được lưu.</p>
     */
    private ItemView itemView(Choice choice) {
        RecipeCandidate recipe = choice.candidate().recipe();
        return new ItemView(null, recipe.recipeId(), recipe.name(), recipe.role(),
                recipe.recipeVersion(), choice.servings(),
                choice.candidate().snapshot().servingGram(), recipe.minutes(),
                // Nội dung vừa qua bước lọc theo đúng hồ sơ của lượt này, nên không mục nào xung
                // đột. Bản xem trước cũng chưa có dòng nào trong database để đánh dấu.
                choice.candidate().locked(), false, choice.costVnd(), List.of());
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

    /** Lý do ở dạng hiển thị, bỏ trùng theo cặp (mã, dữ liệu sinh ra nó). */
    public static List<ReasonView> views(List<ReasonNote> notes) {
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
        List<DayView> days = payload.days();
        Map<String, IngredientEntity> ingredientsById = candidates.loadIngredients();

        MealPlanEntity plan = new MealPlanEntity();
        plan.setUserId(userId);
        plan.setStartDate(days.get(0).localDate());
        plan.setEndDate(days.get(days.size() - 1).localDate());
        plan.setGenerationStatus(payload.status());
        plan.setLifecycleState(PlanLifecycleState.SAVED);
        plan.setPlanVersion(1);
        plan.setProfileVersion(payload.profileVersion());
        plan.setRulesVersion(rulesVersion);

        for (DayView day : days) {
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

    public PlanView view(MealPlanEntity plan) {
        return view(plan, List.of());
    }

    /**
     * Dựng lại kế hoạch đã lưu, <strong>từ snapshot</strong>.
     *
     * <p>Tổng dinh dưỡng tính lại bằng chính {@link NutritionCalculator} trên snapshot đã lưu, nên
     * nó bằng đúng con số của bản xem trước kể cả sau khi seed đổi công thức (bất biến 4 và BR17).
     * Vai trò và thời gian nấu thì đọc từ danh mục hiện tại: chúng là thông tin hiển thị, không
     * tham gia phép tính nào.</p>
     *
     * <p><strong>Kiểm lại theo hồ sơ hiện tại ngay tại đây</strong> (nhánh 10): mỗi mục chưa ăn
     * được đối chiếu với các điều kiện bắt buộc đang có, và {@code lifecycle_state} trả về là kết
     * quả <em>vừa tính</em> chứ không phải cột đã lưu. Cột có thể lạc hậu — người dùng đổi hồ sơ ở
     * thiết bị khác thì không có gì chạy qua kế hoạch này để cập nhật nó — và một thực đơn nói
     * "đang hợp lệ" trong khi nó chứa món vừa thành dị ứng là đúng thứ không được phép xảy ra.</p>
     *
     * @param extraNotes ghi chú mà <em>nơi gọi</em> vừa phát hiện — nhánh 6 kiểm lại toàn kế hoạch
     *                   sau mỗi thao tác sửa và đưa kết quả vào đây, thay vì tự dựng một response
     *                   thứ hai có hình dạng hơi khác
     */
    public PlanView view(MealPlanEntity plan, List<ReasonNote> extraNotes) {
        List<MealSlotEntity> ordered = orderedSlots(plan);
        Revalidation revalidation = profiles.snapshotIfAvailable(plan.getUserId())
                .map(profile -> revalidations.check(plan, profile))
                .orElseGet(Revalidation::clean);

        Map<String, RecipeEntity> recipesById = new HashMap<>();
        for (MealSlotEntity slot : ordered) {
            for (MealPlanItemEntity item : slot.getItems()) {
                recipesById.computeIfAbsent(item.getRecipeId(),
                        id -> recipes.findById(id).orElse(null));
            }
        }

        // Nhóm theo ngày chứ không dồn mọi bữa vào một DayView: với kế hoạch bảy ngày, một khối
        // gồm 28 bữa và một tổng dinh dưỡng của cả tuần không nói được ngày nào thiếu gì, và
        // thanh chọn ngày của giao diện không có gì để bám vào.
        Map<LocalDate, List<SlotView>> slotsByDate = new LinkedHashMap<>();
        Map<LocalDate, List<Portion>> portionsByDate = new LinkedHashMap<>();
        List<Portion> wholePlan = new ArrayList<>();

        for (MealSlotEntity slot : ordered) {
            List<ItemView> items = new ArrayList<>();
            for (MealPlanItemEntity item : slot.getItems()) {
                RecipeSnapshot snapshot = json.readValue(item.getNutrientSnapshot(),
                        RecipeSnapshot.class);
                Portion portion = new Portion(snapshot, item.getServings());
                portionsByDate.computeIfAbsent(slot.getLocalDate(), date -> new ArrayList<>())
                        .add(portion);
                wholePlan.add(portion);
                RecipeEntity recipe = recipesById.get(item.getRecipeId());
                // Món khóa cũng mang cờ xung đột như mọi món khác (BR10): khóa là một yêu cầu
                // giữ, không phải một chứng nhận an toàn, và hệ thống không âm thầm mở khóa nó.
                items.add(new ItemView(item.getId(), item.getRecipeId(), snapshot.name(),
                        recipe == null ? null : recipe.getRole(), item.getRecipeVersion(),
                        item.getServings(), snapshot.servingGram(),
                        recipe == null ? null : recipe.getMinutes(),
                        item.isLocked(), revalidation.conflicts(item.getId()),
                        item.getCostSnapshot(), views(revalidation.reasonsFor(item.getId()))));
            }
            slotsByDate.computeIfAbsent(slot.getLocalDate(), date -> new ArrayList<>())
                    .add(new SlotView(slot.getMealType(), slot.getSequence(),
                            slot.getTemplateCode(), slot.mode(), items.stream()
                                    .map(ItemView::role).filter(Objects::nonNull).toList(),
                            true, List.copyOf(items), List.of()));
        }

        List<DayView> days = new ArrayList<>();
        for (var entry : slotsByDate.entrySet()) {
            NutritionTotals dayTotals = calculator.forPortions(
                    portionsByDate.getOrDefault(entry.getKey(), List.of()));
            days.add(new DayView(entry.getKey(), List.copyOf(entry.getValue()),
                    nutritionView(dayTotals), dayTotals.methodNotes(),
                    dayCostOf(plan, entry.getKey()), List.of()));
        }

        NutritionTotals totals = calculator.forPortions(wholePlan);
        List<ReasonNote> notes = new ArrayList<>(extraNotes);
        if (totals.coverage() != CoverageStatus.COMPLETE) {
            notes.add(ReasonNote.of(RecommendationReason.NUTRITION_DATA_INCOMPLETE));
        }
        if (revalidation.anyConflict()) {
            notes.add(ReasonNote.of(RecommendationReason.PLAN_NEEDS_UPDATE_AFTER_PROFILE_CHANGE,
                    String.valueOf(revalidation.conflictsByItem().size())));
        }

        return new PlanView(plan.getId(), plan.getGenerationStatus(), revalidation.state(),
                plan.getPlanVersion(), plan.getProfileVersion(), plan.getRulesVersion(),
                plan.getStartDate(), plan.getEndDate(), totals.coverage(), views(notes),
                List.copyOf(days));
    }

    /** Các bữa theo thứ tự hiển thị: ngày, rồi buổi trong ngày, rồi số thứ tự bữa phụ. */
    public static List<MealSlotEntity> orderedSlots(MealPlanEntity plan) {
        return plan.getSlots().stream()
                .sorted(Comparator.comparing(MealSlotEntity::getLocalDate)
                        .thenComparing(slot -> slot.getMealType().ordinal())
                        .thenComparing(MealSlotEntity::getSequence))
                .toList();
    }

    /** Tổng chi phí của kế hoạch; null ngay khi <strong>một</strong> mục chưa có giá. */
    public static BigDecimal totalCostOf(MealPlanEntity plan) {
        return costOf(plan, null);
    }

    /**
     * Chi phí của <strong>một ngày</strong>; null ngay khi một mục của ngày đó chưa có giá.
     *
     * <p>Ngân sách người dùng khai là ngân sách <em>ngày</em>, nên kế hoạch bảy ngày phải so theo
     * ngày. Chia tổng tuần cho bảy sẽ giấu mất đúng cái ngày đắt nhất.</p>
     */
    public static BigDecimal dayCostOf(MealPlanEntity plan, LocalDate date) {
        return costOf(plan, date);
    }

    private static BigDecimal costOf(MealPlanEntity plan, LocalDate onlyDate) {
        BigDecimal total = BigDecimal.ZERO;
        for (MealSlotEntity slot : plan.getSlots()) {
            if (onlyDate != null && !onlyDate.equals(slot.getLocalDate())) {
                continue;
            }
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
        return withPreviewId(responseOf(json.readTree(preview.getPayload())), preview.getId(),
                preview.getExpiresAt());
    }

    /**
     * Body xem trước, <strong>trừ</strong> dấu vân tay nằm nhờ trong cùng cột.
     *
     * <p>Gỡ khóa đó ở đúng một chỗ: nó là chuyện nội bộ của idempotency, không phải trường của
     * hợp đồng JSON, và không nơi nào khác được nhìn thấy nó.</p>
     */
    private PreviewResponse responseOf(JsonNode payload) {
        if (payload instanceof ObjectNode body) {
            body.remove(PreviewFingerprint.PAYLOAD_FIELD);
        }
        return json.treeToValue(payload, PreviewResponse.class);
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

    // =========================================================================================
    // Kiểu dữ liệu nội bộ
    // =========================================================================================

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
