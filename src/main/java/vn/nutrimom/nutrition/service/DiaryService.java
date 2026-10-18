package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.DiaryEntryEntity;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.MealPlanItemEntity;
import vn.nutrimom.nutrition.domain.MealSlotEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.dto.DiaryDtos.CreateDiaryEntryRequest;
import vn.nutrimom.nutrition.dto.DiaryDtos.DiaryEntryView;
import vn.nutrimom.nutrition.dto.DiaryDtos.DiaryView;
import vn.nutrimom.nutrition.dto.DiaryDtos.PatchDiaryEntryRequest;
import vn.nutrimom.nutrition.dto.DiaryDtos.RemainingView;
import vn.nutrimom.nutrition.dto.MealPlanDtos.NutrientView;
import vn.nutrimom.nutrition.dto.RecommendationDtos.ReasonView;
import vn.nutrimom.nutrition.repository.DiaryEntryRepository;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.MealSlotRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;
import vn.nutrimom.nutrition.service.NutritionSnapshots.Portion;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.NutritionTotals.NutrientTotal;

/**
 * Nhật ký đã ăn: ghi món <strong>thực tế</strong>, tách hẳn khỏi kế hoạch.
 *
 * <h2>Bất biến 3 là hình dạng của lớp này, không phải một lời nhắc</h2>
 *
 * <p>Dự kiến và đã ghi đi ra khỏi đây bằng <strong>hai khối riêng</strong> trong cùng một response
 * và không có phép cộng nào nối chúng lại. Mỗi khối được dựng từ nguồn của chính nó — khối dự kiến
 * từ {@code meal_plan_items}, khối đã ghi từ {@code diary_entries} — nên không có đường nào để một
 * mục kế hoạch lọt vào tổng đã ăn. Cụ thể hơn: lớp này <strong>không bao giờ ghi vào</strong>
 * {@code meal_plan_items}, và {@code MealPlanEditService} của nhánh 6 không bao giờ ghi vào
 * {@code diary_entries}. Hai chiều đều đóng.</p>
 *
 * <p>Hệ quả phải giữ: xóa một dòng nhật ký làm tổng đã ghi đổi và để tổng dự kiến y nguyên (TC17);
 * ghi nửa phần của một mục kế hoạch một phần thì nhật ký tính một nửa còn kế hoạch vẫn một phần
 * (TC15); hết ngày mà không ghi gì thì không có dòng nhật ký nào được sinh ra.</p>
 *
 * <h2>Một bộ tính duy nhất</h2>
 *
 * <p>Mọi con số ở đây đến từ {@link NutritionCalculator} chạy trên snapshot đã lưu (bất biến 4).
 * Lớp này không cộng một tổng dinh dưỡng nào, kể cả tổng "phần còn lại" — nó chỉ trừ hai con số mà
 * bộ tính đã trả về.</p>
 */
@Service
public class DiaryService {

    private final DiaryEntryRepository entries;
    private final MealSlotRepository slots;
    private final RecipeRepository recipes;
    private final IngredientRepository ingredients;
    private final NutritionProfileService profiles;
    private final NutritionCalculator calculator;
    private final ObjectMapper json;
    private final Clock clock;

    public DiaryService(DiaryEntryRepository entries, MealSlotRepository slots,
                        RecipeRepository recipes, IngredientRepository ingredients,
                        NutritionProfileService profiles, NutritionCalculator calculator,
                        ObjectMapper json, Clock clock) {
        this.entries = entries;
        this.slots = slots;
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.profiles = profiles;
        this.calculator = calculator;
        this.json = json;
        this.clock = clock;
    }

    // =========================================================================================
    // GET /api/v1/diary?date=
    // =========================================================================================

    @Transactional(readOnly = true)
    public DiaryView view(String userId, LocalDate date) {
        List<DiaryEntryEntity> logged =
                entries.findByUserIdAndLocalDateOrderByMealTypeAscCreatedAtAsc(userId, date);
        List<MealSlotEntity> planned = slots.findPlannedSlots(userId, date);

        NutritionTotals plannedTotals = calculator.forPortions(plannedPortions(planned));
        NutritionTotals loggedTotals = calculator.forPortions(loggedPortions(logged));

        long offCatalog = logged.stream().filter(entry -> !entry.hasNutritionData()).count();
        long estimated = logged.stream().filter(DiaryEntryEntity::isEstimateIncomplete).count();

        // Mọi chất có thể đủ số mà tổng vẫn chưa đầy đủ: một món ngoài bộ dữ liệu không góp con số
        // nào, và một dòng người dùng đánh dấu "ước tính" thì số của nó là số của công thức gốc chứ
        // không phải của thứ họ đã ăn. Cả hai đều là thiếu dữ liệu, nên cả hai đều hạ coverage.
        CoverageStatus loggedCoverage = offCatalog > 0 || estimated > 0
                ? CoverageStatus.PARTIAL : loggedTotals.coverage();

        List<ReasonNote> notes = new ArrayList<>();
        notes.add(ReasonNote.of(RecommendationReason.DIARY_LOGGED_TOTAL_ONLY));
        int unlogged = unloggedPlannedMeals(planned, logged);
        if (unlogged > 0) {
            notes.add(ReasonNote.of(RecommendationReason.DIARY_PLANNED_MEAL_NOT_LOGGED,
                    String.valueOf(unlogged)));
        }
        if (offCatalog > 0) {
            notes.add(ReasonNote.of(RecommendationReason.DIARY_OFF_CATALOG_ENTRY,
                    String.valueOf(offCatalog)));
        }
        if (estimated > 0) {
            notes.add(ReasonNote.of(RecommendationReason.DIARY_ESTIMATE_INCOMPLETE,
                    String.valueOf(estimated)));
        }

        return new DiaryView(date, entryViews(logged),
                nutritionView(plannedTotals), plannedTotals.coverage(),
                nutritionView(loggedTotals), loggedCoverage,
                remaining(userId, loggedTotals, offCatalog == 0 && estimated == 0),
                loggedTotals.methodNotes(), MealPlanService.views(notes));
    }

    /**
     * Số bữa trong kế hoạch của ngày <strong>chưa được ghi</strong>.
     *
     * <p>Đối chiếu ở mức loại bữa, không ở mức từng món: người dùng ăn bữa trưa nhưng ghi bằng một
     * món khác món đã lên kế hoạch thì bữa trưa <em>đã</em> được ghi. Đếm theo món sẽ báo "chưa ghi"
     * cho một bữa họ vừa ghi xong, chỉ vì họ ăn khác kế hoạch — mà ăn khác kế hoạch chính là điều
     * bảng nhật ký sinh ra để ghi lại.</p>
     */
    private int unloggedPlannedMeals(List<MealSlotEntity> planned, List<DiaryEntryEntity> logged) {
        Set<MealType> loggedTypes = logged.stream()
                .map(DiaryEntryEntity::getMealType)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(MealType.class)));
        Set<MealType> plannedTypes = planned.stream()
                .filter(slot -> !slot.getItems().isEmpty())
                .map(MealSlotEntity::getMealType)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(MealType.class)));
        plannedTypes.removeAll(loggedTypes);
        return plannedTypes.size();
    }

    // =========================================================================================
    // Phần còn lại của ngày (UC08)
    // =========================================================================================

    /**
     * Khoảng cách giữa mức tham chiếu cả ngày và những gì đã ghi.
     *
     * <p>Đây là <strong>dữ liệu cho khu vực gợi ý</strong>, không phải một đơn thuốc. Ba giới hạn
     * được ép ngay trong hàm này, không để lớp hiển thị tự nhớ:</p>
     *
     * <ol>
     *   <li><strong>Chất chưa đủ dữ liệu thì không có con số.</strong> Tổng đã ghi của chất đó còn
     *       PARTIAL nghĩa là phần đã ăn đang bị tính thiếu, nên "phần còn lại" sẽ lớn hơn thực tế —
     *       và một con số lớn hơn thực tế ở đây mời người ta ăn bù thứ họ đã ăn rồi. Chất đó vẫn
     *       xuất hiện với giá trị null và PARTIAL, để giao diện nói "chưa đủ dữ liệu" (bất biến 1).
     *       Xét <strong>từng chất một</strong>: danh mục chưa có số iốt không phải lý do để giấu đi
     *       khoảng cách năng lượng vốn đã tính được đầy đủ.</li>
     *   <li><strong>Chất không có mức tham chiếu thì không xuất hiện.</strong> Không có gì để trừ
     *       thì không có khoảng cách nào để nói.</li>
     *   <li><strong>Nhật ký quá thiếu dữ liệu thì không nói gì về khẩu phần cả ngày.</strong> Không
     *       dòng nào có số liệu thì cả khối chỉ còn {@code DIARY_TOO_SPARSE}, và khu vực gợi ý quay
     *       về gợi ý đa dạng thông thường.</li>
     * </ol>
     *
     * @param everyEntryHasFullData false khi trong ngày có món ngoài bộ dữ liệu hoặc dòng được đánh
     *                              dấu ước tính chưa đầy đủ. Đó là thiếu dữ liệu ở mức
     *                              <em>cả tập đã ghi</em>, khác với thiếu ở mức một chất: nó thổi
     *                              phồng khoảng cách của <strong>mọi</strong> chất cùng lúc, nên
     *                              không chất nào được mang con số
     *
     * <p>Hàm này cố ý <strong>không</strong> gọi bộ gợi ý và không đề xuất món nào: nó trả về
     * khoảng cách, còn việc chọn món vẫn đi qua {@code POST /api/v1/recommendations} của nhánh 4 —
     * đường duy nhất có bước lọc dị ứng. Và không có nhánh nào ở đây sinh ra lời khuyên tăng liều
     * bổ sung.</p>
     */
    private RemainingView remaining(String userId, NutritionTotals logged,
                                    boolean everyEntryHasFullData) {
        BigDecimal energyTarget = profiles.snapshot(userId).energyEstimate().kcalPerDay();
        boolean sparse = logged.byNutrient().values().stream()
                .allMatch(total -> total.knownValue() == null);

        List<ReasonNote> notes = new ArrayList<>();
        if (sparse) {
            notes.add(ReasonNote.of(RecommendationReason.DIARY_TOO_SPARSE));
            return new RemainingView(energyTarget, Map.of(), MealPlanService.views(notes));
        }

        Map<String, NutrientView> byNutrient = new LinkedHashMap<>();
        boolean anyNumber = false;
        for (Map.Entry<String, BigDecimal> target : targets(energyTarget).entrySet()) {
            NutrientTotal eaten = logged.nutrient(target.getKey()).orElse(null);
            String unit = eaten == null
                    ? calculator.rules().unitFor(target.getKey()).orElse(null) : eaten.unit();

            boolean usable = eaten != null && eaten.knownValue() != null
                    && eaten.coverageStatus() == CoverageStatus.COMPLETE
                    && everyEntryHasFullData;
            if (!usable) {
                byNutrient.put(target.getKey(), new NutrientView(null, unit, CoverageStatus.PARTIAL,
                        eaten == null ? List.of() : eaten.missingIngredientIds()));
                continue;
            }

            // Không âm: ăn quá mức tham chiếu thì phần còn lại là 0, không phải một con số âm mà
            // giao diện sẽ phải tự nghĩ ra cách đọc.
            BigDecimal left = target.getValue().subtract(eaten.knownValue()).max(BigDecimal.ZERO);
            byNutrient.put(target.getKey(),
                    new NutrientView(left, unit, CoverageStatus.COMPLETE, List.of()));
            anyNumber = true;
        }

        if (anyNumber) {
            notes.add(ReasonNote.of(RecommendationReason.DIARY_REMAINING_ESTIMATE));
        } else {
            notes.add(ReasonNote.of(RecommendationReason.DIARY_TOO_SPARSE));
        }
        return new RemainingView(energyTarget, Map.copyOf(byNutrient), MealPlanService.views(notes));
    }

    /** Mức tham chiếu cả ngày; chất chưa có mức thì không vào bảng, không mượn một con số nào. */
    private Map<String, BigDecimal> targets(BigDecimal energyTarget) {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        if (energyTarget != null) {
            targets.put(NutritionCalculator.ENERGY, energyTarget);
        }
        for (var target : calculator.rules().micronutrientTargets()) {
            if (target.amountPerDay() != null) {
                targets.put(target.code(), target.amountPerDay());
            }
        }
        return targets;
    }

    // =========================================================================================
    // POST /api/v1/diary-entries
    // =========================================================================================

    /**
     * Ghi một món đã ăn.
     *
     * <p>Trả về <strong>cả ngày đã tính lại</strong> chứ không riêng dòng vừa ghi: ghi một món làm
     * đổi tổng đã ghi và phần còn lại của ngày, và để giao diện tự cộng lại là tạo nguồn sự thật
     * thứ hai cho đúng những con số mà bất biến 4 muốn chỉ có một nguồn.</p>
     */
    @Transactional
    public DiaryView create(String userId, CreateDiaryEntryRequest request) {
        Optional<DiaryEntryEntity> replay = entries.findByUserIdAndRequestId(userId,
                request.requestId());
        if (replay.isPresent()) {
            // Gửi lại cùng request_id trả lại đúng bản ghi cũ (TC16). Ghi trùng ở đây không chỉ
            // thừa một dòng — nó làm tổng đã ăn của ngày gấp đôi.
            return view(userId, replay.get().getLocalDate());
        }

        requireOneShape(request);

        DiaryEntryEntity entry = new DiaryEntryEntity();
        entry.setUserId(userId);
        entry.setLocalDate(request.localDate());
        entry.setMealType(request.mealType());
        entry.setRequestId(request.requestId());
        entry.setEstimateIncomplete(request.estimateIncompleteEnabled());
        entry.setCreatedAt(now());
        entry.setUpdatedAt(now());

        if (request.referencesCatalog()) {
            MealPlanItemEntity source = planItem(userId, request.sourcePlanItemId());
            if (source != null) {
                entry.setSourcePlanItemId(source.getId());
            }
            applyCatalogSource(entry, source, request.recipeId());
            entry.setActualServings(request.servings());
        } else {
            entry.setCustomText(request.customText().trim());
            entry.setAmountText(request.amountText());
        }

        try {
            entries.saveAndFlush(entry);
        } catch (DataIntegrityViolationException duplicateRequestId) {
            // Hai lượt gửi song song cùng request_id: unique (user_id, request_id) chặn bản thứ
            // hai. Đọc lại bản thắng — đúng tinh thần idempotency, không phải lỗi.
            DiaryEntryEntity winner = entries.findByUserIdAndRequestId(userId, request.requestId())
                    .orElseThrow(() -> duplicateRequestId);
            return view(userId, winner.getLocalDate());
        }
        return view(userId, entry.getLocalDate());
    }

    /**
     * Số liệu của một dòng trỏ tới danh mục, và lý do có hai nguồn snapshot.
     *
     * <p><strong>Ăn đúng món đã lên kế hoạch</strong> thì snapshot lấy từ chính mục kế hoạch: đó là
     * bộ số đã trình bày cho người dùng lúc lập thực đơn, nên tổng đã ghi và tổng dự kiến của cùng
     * một món khớp nhau tới từng chữ số, và cả hai cùng sống sót qua một lần seed đổi công thức
     * (TC21).</p>
     *
     * <p><strong>Ăn một biến thể khác</strong> — lối "nguyên liệu đổi đáng kể, chọn biến thể có
     * sẵn" của UC05 — thì snapshot lấy theo công thức thật sự đã ăn. Giữ số của món gốc trong
     * trường hợp này là coi số liệu của một món là chính xác cho một món khác, đúng điều nhánh
     * cấm.</p>
     */
    private void applyCatalogSource(DiaryEntryEntity entry, MealPlanItemEntity source,
                                    String requestedRecipeId) {
        boolean fromPlan = source != null
                && (requestedRecipeId == null || requestedRecipeId.equals(source.getRecipeId()));
        if (fromPlan) {
            entry.setRecipeId(source.getRecipeId());
            entry.setRecipeVersion(source.getRecipeVersion());
            entry.setRecipeSnapshot(source.getNutrientSnapshot());
            return;
        }

        RecipeEntity recipe = requireRecipe(requestedRecipeId);
        entry.setRecipeId(recipe.getId());
        entry.setRecipeVersion(recipe.getVersion());
        entry.setRecipeSnapshot(json.writeValueAsString(
                NutritionSnapshots.snapshotOf(recipe, ingredientsOf(recipe))));
    }

    /**
     * Đúng một dạng dòng, kiểm ở lớp dịch vụ chứ không chờ database từ chối.
     *
     * <p>{@code ck_diary_entries_shape} vẫn là chốt cuối, nhưng nó trả về một lỗi ràng buộc mà
     * giao diện không gắn được vào ô nhập nào. Ở đây lỗi nêu đúng tên trường người dùng gửi
     * sai.</p>
     */
    private void requireOneShape(CreateDiaryEntryRequest request) {
        boolean catalog = request.referencesCatalog();
        boolean custom = request.hasCustomText();

        if (catalog && custom) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Một dòng nhật ký hoặc là món trong danh mục, hoặc là món bạn tự ghi — "
                            + "không thể vừa là cả hai.",
                    Map.of("custom_text", "không gửi kèm recipe_id hay source_plan_item_id"));
        }
        if (!catalog && !custom) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Hãy chọn một món trong danh mục, hoặc tự ghi tên món bạn đã ăn.",
                    Map.of("recipe_id", "thiếu recipe_id, source_plan_item_id hoặc custom_text"));
        }
        if (catalog && request.servings() == null) {
            // BR16: số phần phải do người dùng xác nhận. Mặc định một phần ở đây là hệ thống tự ghi
            // thay họ, đúng thứ nhánh này cấm.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Hãy xác nhận bạn đã ăn bao nhiêu phần.",
                    Map.of("servings", "không được rỗng khi ghi một món trong danh mục"));
        }
        if (!catalog && request.servings() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Món ngoài bộ dữ liệu không có công thức để quy ra số phần — "
                            + "hãy ghi lượng đã ăn bằng chữ.",
                    Map.of("servings", "chỉ dùng cho món trong danh mục"));
        }
    }

    // =========================================================================================
    // PATCH /api/v1/diary-entries/{id}
    // =========================================================================================

    /**
     * Sửa một dòng đã ghi.
     *
     * <p>Sửa trong <strong>đúng dạng của dòng</strong>: một dòng món danh mục đổi được món và số
     * phần, một dòng tự ghi đổi được chữ. Đổi hẳn dạng thì xóa rồi ghi lại — "ghi sai chỉnh hoặc
     * xóa bằng thao tác rõ ràng", và một thao tác lặng lẽ biến một dòng không có số liệu thành một
     * dòng có số liệu là thứ khó nhìn ra nhất khi tổng của ngày đổi.</p>
     *
     * <p>Không thao tác nào ở đây chạm vào {@code meal_plan_items}: sửa nhật ký không sửa kế
     * hoạch.</p>
     */
    @Transactional
    public DiaryView patch(String userId, String entryId, PatchDiaryEntryRequest request) {
        DiaryEntryEntity entry = requireEntry(userId, entryId);
        if (request.changesNothing()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Yêu cầu không thay đổi gì.",
                    Map.of("servings", "cần ít nhất một trường để sửa"));
        }
        requireSameShape(entry, request);

        if (request.localDate() != null) {
            entry.setLocalDate(request.localDate());
        }
        if (request.mealType() != null) {
            entry.setMealType(request.mealType());
        }
        if (request.estimateIncomplete() != null) {
            entry.setEstimateIncomplete(request.estimateIncomplete());
        }
        if (request.recipeId() != null) {
            RecipeEntity recipe = requireRecipe(request.recipeId());
            entry.setRecipeId(recipe.getId());
            entry.setRecipeVersion(recipe.getVersion());
            entry.setRecipeSnapshot(json.writeValueAsString(
                    NutritionSnapshots.snapshotOf(recipe, ingredientsOf(recipe))));
            // Món đổi thì nguồn không còn đúng nữa: dòng này không còn là lần ghi của mục kế hoạch
            // đó. Giữ lại sẽ nói sai về việc đã xảy ra.
            entry.setSourcePlanItemId(null);
        }
        if (request.servings() != null) {
            entry.setActualServings(request.servings());
        }
        if (request.customText() != null) {
            entry.setCustomText(request.customText().trim());
        }
        if (request.amountText() != null) {
            entry.setAmountText(request.amountText());
        }
        entry.setUpdatedAt(now());

        entries.saveAndFlush(entry);
        return view(userId, entry.getLocalDate());
    }

    private void requireSameShape(DiaryEntryEntity entry, PatchDiaryEntryRequest request) {
        boolean catalogEntry = entry.getRecipeId() != null;
        if (catalogEntry && (request.customText() != null || request.amountText() != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Dòng này là một món trong danh mục. Muốn đổi thành món tự ghi thì hãy xóa "
                            + "dòng này rồi ghi lại.",
                    Map.of("custom_text", "không dùng được cho dòng món trong danh mục"));
        }
        if (!catalogEntry && (request.recipeId() != null || request.servings() != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Dòng này là món bạn tự ghi, không có công thức để quy ra số phần. Muốn đổi "
                            + "thành món trong danh mục thì hãy xóa dòng này rồi ghi lại.",
                    Map.of("recipe_id", "không dùng được cho dòng món tự ghi"));
        }
    }

    // =========================================================================================
    // DELETE /api/v1/diary-entries/{id}
    // =========================================================================================

    /**
     * Xóa một dòng đã ghi.
     *
     * <p>Xóa thật, không đánh dấu: một bữa ghi nhầm chưa từng xảy ra, và giữ nó lại dưới dạng ẩn
     * chỉ tạo cơ hội cho nó quay lại một phép tính nào đó. Tổng <strong>đã ghi</strong> đổi theo;
     * tổng <strong>dự kiến</strong> không đổi một chữ số nào, vì nó đọc từ bảng khác (TC17).</p>
     */
    @Transactional
    public DiaryView delete(String userId, String entryId) {
        DiaryEntryEntity entry = requireEntry(userId, entryId);
        LocalDate date = entry.getLocalDate();
        entries.delete(entry);
        entries.flush();
        return view(userId, date);
    }

    // =========================================================================================
    // Dựng khối hiển thị
    // =========================================================================================

    private List<DiaryEntryView> entryViews(List<DiaryEntryEntity> logged) {
        List<DiaryEntryView> views = new ArrayList<>();
        for (DiaryEntryEntity entry : logged) {
            List<ReasonNote> notes = new ArrayList<>();
            if (entry.isEstimateIncomplete()) {
                notes.add(ReasonNote.of(RecommendationReason.DIARY_ESTIMATE_INCOMPLETE, "1"));
            }

            if (!entry.hasNutritionData()) {
                notes.add(ReasonNote.of(RecommendationReason.DIARY_OFF_CATALOG_ENTRY, "1"));
                views.add(new DiaryEntryView(entry.getId(), entry.getLocalDate(),
                        entry.getMealType(), null, entry.getCustomText(), null, null,
                        entry.getCustomText(), entry.getAmountText(), entry.isEstimateIncomplete(),
                        entry.getSourcePlanItemId(), null, CoverageStatus.PARTIAL,
                        entry.getCreatedAt(), MealPlanService.views(notes)));
                continue;
            }

            RecipeSnapshot snapshot = snapshotOf(entry);
            NutritionTotals totals = calculator.forPortion(snapshot, entry.getActualServings());
            CoverageStatus coverage = entry.isEstimateIncomplete()
                    ? CoverageStatus.PARTIAL : totals.coverage();
            views.add(new DiaryEntryView(entry.getId(), entry.getLocalDate(), entry.getMealType(),
                    entry.getRecipeId(), snapshot.name(), entry.getRecipeVersion(),
                    entry.getActualServings(), null, null, entry.isEstimateIncomplete(),
                    entry.getSourcePlanItemId(), nutritionView(totals), coverage,
                    entry.getCreatedAt(), MealPlanService.views(notes)));
        }
        return List.copyOf(views);
    }

    /** Khẩu phần đã ghi — nguồn duy nhất của tổng "đã ghi". */
    private List<Portion> loggedPortions(List<DiaryEntryEntity> logged) {
        List<Portion> portions = new ArrayList<>();
        for (DiaryEntryEntity entry : logged) {
            if (entry.hasNutritionData()) {
                portions.add(new Portion(snapshotOf(entry), entry.getActualServings()));
            }
        }
        return portions;
    }

    /** Khẩu phần dự kiến — nguồn duy nhất của tổng "dự kiến", đọc từ bảng kế hoạch. */
    private List<Portion> plannedPortions(List<MealSlotEntity> planned) {
        List<Portion> portions = new ArrayList<>();
        for (MealSlotEntity slot : planned) {
            for (MealPlanItemEntity item : slot.getItems()) {
                portions.add(new Portion(
                        json.readValue(item.getNutrientSnapshot(), RecipeSnapshot.class),
                        item.getServings()));
            }
        }
        return portions;
    }

    private RecipeSnapshot snapshotOf(DiaryEntryEntity entry) {
        return json.readValue(entry.getRecipeSnapshot(), RecipeSnapshot.class);
    }

    /** Cùng hình dạng khối dinh dưỡng với nhánh 5, dựng bằng đúng một chỗ ({@code ENERGY} lên đầu). */
    private Map<String, NutrientView> nutritionView(NutritionTotals totals) {
        Map<String, NutrientView> view = new LinkedHashMap<>();
        totals.nutrient(NutritionCalculator.ENERGY).ifPresent(
                energy -> view.put(NutritionCalculator.ENERGY, nutrientView(energy)));
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

    // =========================================================================================
    // Tra cứu
    // =========================================================================================

    private DiaryEntryEntity requireEntry(String userId, String entryId) {
        return entries.findByIdAndUserId(entryId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy dòng nhật ký này."));
    }

    /** Mục kế hoạch <strong>của đúng người gọi</strong>; null khi yêu cầu không nêu nguồn nào. */
    private MealPlanItemEntity planItem(String userId, String planItemId) {
        if (planItemId == null || planItemId.isBlank()) {
            return null;
        }
        return slots.findPlanItem(planItemId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy món này trong thực đơn của bạn."));
    }

    private RecipeEntity requireRecipe(String recipeId) {
        return recipes.findById(recipeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy món này trong danh mục."));
    }

    /**
     * Nguyên liệu của một món, nạp một lượt.
     *
     * <p>Thiếu một nguyên liệu là lỗi dữ liệu seed và {@code NutritionSnapshots} ném ngay: tính
     * tiếp với một dòng bị bỏ qua sẽ cho ra tổng thấp hơn thực tế mà nhìn vẫn như số đầy đủ.</p>
     */
    private Map<String, IngredientEntity> ingredientsOf(RecipeEntity recipe) {
        List<String> ids = recipe.getIngredients().stream()
                .map(line -> line.getIngredientId())
                .toList();
        return ingredients.findAllById(ids).stream()
                .collect(Collectors.toMap(IngredientEntity::getId, Function.identity()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
