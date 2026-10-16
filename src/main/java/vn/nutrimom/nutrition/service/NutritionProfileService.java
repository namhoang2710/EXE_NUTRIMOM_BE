package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.calendar.service.CalendarZone;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.nutrition.domain.AllergyDeclarationStatus;
import vn.nutrimom.nutrition.domain.EatingPreferenceEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.RecipePreferenceType;
import vn.nutrimom.nutrition.domain.UserRecipePreferenceEntity;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.EnergyEstimateView;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.EnergyInputsView;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.NutritionProfileView;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.PregnancyMilestone;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.SaveNutritionProfileRequest;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.SaveNutritionProfileResponse;
import vn.nutrimom.nutrition.dto.NutritionProfileDtos.UnmetView;
import vn.nutrimom.nutrition.repository.AllergenRepository;
import vn.nutrimom.nutrition.repository.EatingPreferenceRepository;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;
import vn.nutrimom.nutrition.repository.UserRecipePreferenceRepository;
import vn.nutrimom.pregnancy.domain.PregnancyEntity;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

/**
 * Đọc và lưu hồ sơ ăn uống của mẹ bầu (nhánh 3).
 *
 * <p><strong>Chủ sở hữu luôn suy từ JWT.</strong> Không có tham số {@code userId} nào đến từ thân
 * yêu cầu, và không có cách nào chỉ định hồ sơ của người khác: hồ sơ được tìm qua thai kỳ đang
 * hoạt động của chính người gọi. Đây là lý do {@code EatingPreferenceRepository} cố ý không có
 * phương thức tra theo user (BR19).</p>
 *
 * <p><strong>Không tạo lại hồ sơ thai kỳ.</strong> Bước 1 của onboarding (ngày dự sinh hoặc tuần +
 * ngày thai) do module thai kỳ phụ trách qua {@code POST /api/v1/pregnancies} — ở đây chỉ đọc mốc
 * đã lưu. Vì thế service này <em>không bao giờ</em> ghi vào {@code app.pregnancies}, kể cả khi
 * ngày dự sinh trông có vẻ sai: "không tự thay ngày dự sinh người dùng đã xác nhận".</p>
 */
@Service
public class NutritionProfileService {

    private final PregnancyRepository pregnancies;
    private final EatingPreferenceRepository preferences;
    private final UserRecipePreferenceRepository recipePreferences;
    private final AllergenRepository allergens;
    private final IngredientRepository ingredients;
    private final RecipeRepository recipes;
    private final EnergyEstimator energyEstimator;
    private final CalendarZone zones;
    private final Clock clock;

    public NutritionProfileService(PregnancyRepository pregnancies,
                                   EatingPreferenceRepository preferences,
                                   UserRecipePreferenceRepository recipePreferences,
                                   AllergenRepository allergens,
                                   IngredientRepository ingredients,
                                   RecipeRepository recipes,
                                   EnergyEstimator energyEstimator,
                                   CalendarZone zones,
                                   Clock clock) {
        this.pregnancies = pregnancies;
        this.preferences = preferences;
        this.recipePreferences = recipePreferences;
        this.allergens = allergens;
        this.ingredients = ingredients;
        this.recipes = recipes;
        this.energyEstimator = energyEstimator;
        this.zones = zones;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NutritionProfileView get(String userId) {
        PregnancyEntity pregnancy = requireActivePregnancy(userId);
        EatingPreferenceEntity preference = preferences.findById(pregnancy.getId()).orElse(null);
        return toView(userId, pregnancy, preference);
    }

    /**
     * Cùng dữ liệu, dạng phẳng cho các service khác trong module (nhánh 4 trở đi).
     *
     * <p>Tồn tại để chủ sở hữu vẫn được suy từ JWT ở <strong>đúng một chỗ</strong>. Bộ gợi ý đọc
     * hồ sơ qua đây chứ không tự truy vấn {@code EatingPreferenceRepository}, nhờ vậy không mở ra
     * lối nào đọc hồ sơ của người khác (BR19).</p>
     */
    @Transactional(readOnly = true)
    public ProfileSnapshot snapshot(String userId) {
        PregnancyEntity pregnancy = requireActivePregnancy(userId);
        EatingPreferenceEntity preference = preferences.findById(pregnancy.getId()).orElse(null);
        ZoneId zone = zones.resolve(pregnancy.getTimezone(), userId);
        GestationalAge age = gestationalAge(pregnancy, zone);
        List<UserRecipePreferenceEntity> recipePrefs = loadRecipePreferences(userId);

        return new ProfileSnapshot(
                pregnancy.getId(),
                preference == null ? 0 : preference.getProfileVersion(),
                age,
                preference == null ? AllergyDeclarationStatus.UNKNOWN : preference.getAllergyStatus(),
                preference == null ? Set.of() : Set.copyOf(preference.getAllergenIds()),
                preference == null ? null : preference.getDietPattern(),
                preference == null ? Set.of()
                        : Set.copyOf(preference.getHardExclusionIngredientIds()),
                preference == null ? Set.of() : Set.copyOf(preference.getDislikedIngredientIds()),
                preference == null ? Set.of() : Set.copyOf(preference.getToleranceTags()),
                Set.copyOf(recipeIds(recipePrefs, RecipePreferenceType.FAVORITE)),
                Set.copyOf(recipeIds(recipePrefs, RecipePreferenceType.BLOCKED)),
                preference == null ? null : preference.getDailyBudgetVnd(),
                preference == null ? null : preference.getMaxMinutesPerMeal(),
                preference == null ? Set.of() : Set.copyOf(preference.getMealTypes()),
                preference == null ? null : preference.getSnackCount(),
                energyEstimator.estimate(energyInputs(preference, age)));
    }

    /** Đầu vào EER từ hồ sơ; mọi trường cho phép null vì bước 5 onboarding bỏ qua được (TC05). */
    private EnergyInputs energyInputs(EatingPreferenceEntity preference, GestationalAge age) {
        return new EnergyInputs(
                preference == null ? null : preference.getAgeYears(),
                preference == null ? null : preference.getHeightCm(),
                preference == null ? null : preference.getCurrentWeightKg(),
                preference == null ? null : preference.getPrePregnancyWeightKg(),
                age.days(),
                preference == null ? null : preference.getActivityCategory());
    }

    /**
     * Lưu hồ sơ ăn uống.
     *
     * <p>Ngữ nghĩa là <strong>hợp nhất</strong>: trường null giữ nguyên giá trị cũ, danh sách rỗng
     * {@code []} xóa sạch danh sách đó. Onboarding lưu theo từng bước nên "thay thế toàn bộ" sẽ
     * khiến bước 4 xóa mất những gì bước 2 vừa lưu.</p>
     *
     * <p>{@code profile_version} tăng <strong>đúng một</strong>, và chỉ khi thực sự có gì đó đổi —
     * so sánh ảnh chụp trước và sau. Một lượt {@code PUT} gửi lại y nguyên dữ liệu cũ (người dùng
     * bấm Lưu hai lần, hoặc mạng gửi lại) không được làm mọi kế hoạch đang có thành
     * {@code NEEDS_UPDATE} ở nhánh 10.</p>
     */
    @Transactional
    public SaveNutritionProfileResponse save(String userId, SaveNutritionProfileRequest request) {
        PregnancyEntity pregnancy = requireActivePregnancy(userId);
        EatingPreferenceEntity preference = preferences.findById(pregnancy.getId())
                .orElseGet(() -> newProfile(pregnancy.getId()));

        List<String> before = snapshot(preference, loadRecipePreferences(userId));
        apply(preference, request);
        List<UserRecipePreferenceEntity> recipePrefs = applyRecipePreferences(userId, request);
        List<String> after = snapshot(preference, recipePrefs);

        if (!before.equals(after)) {
            preference.setProfileVersion(preference.getProfileVersion() + 1);
        }
        preferences.saveAndFlush(preference);

        // Việc đánh dấu mục kế hoạch tương lai là NEEDS_UPDATE thuộc nhánh 10; trường có mặt từ
        // bây giờ để nhánh đó không phải đổi hình dạng hợp đồng đã công bố.
        return new SaveNutritionProfileResponse(preference.getProfileVersion(), List.of(),
                toView(userId, pregnancy, preference));
    }

    // ---------------------------------------------------------------------------------------
    // Ghi
    // ---------------------------------------------------------------------------------------

    private void apply(EatingPreferenceEntity preference, SaveNutritionProfileRequest request) {
        if (request.allergyStatus() != null) {
            preference.setAllergyStatus(request.allergyStatus());
        }
        if (request.allergenIds() != null) {
            replace(preference.getAllergenIds(),
                    validIds(request.allergenIds(), allergens::findAllById,
                            vn.nutrimom.nutrition.domain.AllergenEntity::getId, "allergen_ids"));
        }
        // Khai dị ứng mà quên đổi trạng thái là lỗi dữ liệu, không phải chuyện để hệ thống đoán:
        // danh sách rỗng đi với KNOWN_LIST sẽ làm giao diện gắn nhãn "đã kiểm tra" trên một hồ sơ
        // chưa có gì để kiểm.
        if (preference.getAllergyStatus() == AllergyDeclarationStatus.KNOWN_LIST
                && preference.getAllergenIds().isEmpty()) {
            throw validation("allergy_status là KNOWN_LIST thì allergen_ids không được rỗng. "
                    + "Chọn KNOWN_NONE nếu không có dị ứng nào, hoặc UNKNOWN nếu chưa rõ.");
        }
        // Chiều ngược lại cũng vậy: đã khai là không có hoặc chưa rõ thì không được còn mã nào sót
        // lại từ lần khai trước.
        if (preference.getAllergyStatus() != AllergyDeclarationStatus.KNOWN_LIST
                && !preference.getAllergenIds().isEmpty()) {
            throw validation("allergen_ids chỉ dùng được khi allergy_status là KNOWN_LIST. "
                    + "Gửi allergen_ids rỗng nếu muốn chuyển sang KNOWN_NONE hoặc UNKNOWN.");
        }

        if (request.dietPattern() != null) {
            preference.setDietPattern(request.dietPattern());
        }
        if (request.hardExclusionIngredientIds() != null) {
            replace(preference.getHardExclusionIngredientIds(),
                    validIds(request.hardExclusionIngredientIds(), ingredients::findAllById,
                            vn.nutrimom.nutrition.domain.IngredientEntity::getId,
                            "hard_exclusion_ingredient_ids"));
        }
        if (request.dislikedIngredientIds() != null) {
            replace(preference.getDislikedIngredientIds(),
                    validIds(request.dislikedIngredientIds(), ingredients::findAllById,
                            vn.nutrimom.nutrition.domain.IngredientEntity::getId,
                            "disliked_ingredient_ids"));
        }
        if (request.toleranceTags() != null) {
            replace(preference.getToleranceTags(), normalizeTags(request.toleranceTags()));
        }
        // Một nguyên liệu không thể vừa là điều kiện cứng vừa là ưu tiên mềm: nhánh 4 sẽ vừa loại
        // món vừa hạ điểm nó, và thông báo cho người dùng sẽ nói hai lý do khác nhau cho cùng một
        // món bị ẩn.
        Set<String> overlap = new LinkedHashSet<>(preference.getHardExclusionIngredientIds());
        overlap.retainAll(preference.getDislikedIngredientIds());
        if (!overlap.isEmpty()) {
            throw validation("Nguyên liệu không thể vừa nằm trong hard_exclusion_ingredient_ids "
                    + "vừa nằm trong disliked_ingredient_ids: " + String.join(", ", overlap) + ".");
        }

        if (request.dailyBudgetVnd() != null) preference.setDailyBudgetVnd(request.dailyBudgetVnd());
        if (request.maxMinutesPerMeal() != null) {
            preference.setMaxMinutesPerMeal(request.maxMinutesPerMeal());
        }
        if (request.mealTypes() != null) {
            preference.getMealTypes().clear();
            preference.getMealTypes().addAll(new LinkedHashSet<>(request.mealTypes()));
        }
        if (request.snackCount() != null) preference.setSnackCount(request.snackCount());
        // snack_count chỉ có nghĩa khi thật sự lập bữa phụ. Giữ một con số treo ở đó sẽ làm nhánh 5
        // chia 15% năng lượng bữa phụ cho những vị trí không tồn tại.
        if (preference.getSnackCount() != null && !preference.getMealTypes().isEmpty()
                && !preference.getMealTypes().contains(MealType.SNACK)) {
            throw validation("snack_count chỉ dùng được khi meal_types có SNACK.");
        }

        if (request.ageYears() != null) preference.setAgeYears(request.ageYears());
        if (request.heightCm() != null) preference.setHeightCm(request.heightCm());
        if (request.prePregnancyWeightKg() != null) {
            preference.setPrePregnancyWeightKg(request.prePregnancyWeightKg());
        }
        if (request.currentWeightKg() != null) preference.setCurrentWeightKg(request.currentWeightKg());
        if (request.activityCategory() != null) {
            preference.setActivityCategory(request.activityCategory());
        }
    }

    /**
     * Ghi lại món yêu thích và món bị chặn.
     *
     * <p>Null = giữ nguyên, giống mọi trường khác. Gửi một trong hai danh sách thì danh sách đó
     * được thay thế <em>trong phạm vi loại của nó</em>: gửi {@code favorite_recipe_ids} không đụng
     * tới các món đang {@code BLOCKED}.</p>
     */
    private List<UserRecipePreferenceEntity> applyRecipePreferences(
            String userId, SaveNutritionProfileRequest request) {
        if (request.favoriteRecipeIds() == null && request.blockedRecipeIds() == null) {
            return loadRecipePreferences(userId);
        }
        Set<String> favorites = request.favoriteRecipeIds() == null ? null
                : validIds(request.favoriteRecipeIds(), recipes::findAllById,
                        vn.nutrimom.nutrition.domain.RecipeEntity::getId, "favorite_recipe_ids");
        Set<String> blocked = request.blockedRecipeIds() == null ? null
                : validIds(request.blockedRecipeIds(), recipes::findAllById,
                        vn.nutrimom.nutrition.domain.RecipeEntity::getId, "blocked_recipe_ids");
        if (favorites != null && blocked != null) {
            Set<String> both = new LinkedHashSet<>(favorites);
            both.retainAll(blocked);
            if (!both.isEmpty()) {
                throw validation("Một món chỉ mang đúng một thái độ; các món sau vừa được đánh dấu "
                        + "yêu thích vừa bị chặn: " + String.join(", ", both) + ".");
            }
        }

        List<UserRecipePreferenceEntity> current = loadRecipePreferences(userId);
        for (UserRecipePreferenceEntity existing : current) {
            Set<String> wanted = existing.getPreferenceType() == RecipePreferenceType.FAVORITE
                    ? favorites : blocked;
            if (wanted != null && !wanted.contains(existing.getRecipeId())) {
                recipePreferences.delete(existing);
            }
        }
        recipePreferences.flush();
        upsertRecipePreferences(userId, favorites, RecipePreferenceType.FAVORITE);
        upsertRecipePreferences(userId, blocked, RecipePreferenceType.BLOCKED);
        recipePreferences.flush();
        return loadRecipePreferences(userId);
    }

    private void upsertRecipePreferences(String userId, Set<String> recipeIds,
                                         RecipePreferenceType type) {
        if (recipeIds == null) return;
        for (String recipeId : recipeIds) {
            UserRecipePreferenceEntity entity = recipePreferences
                    .findById(new vn.nutrimom.nutrition.domain.UserRecipePreferenceId(userId, recipeId))
                    .orElseGet(() -> {
                        UserRecipePreferenceEntity created = new UserRecipePreferenceEntity();
                        created.setUserId(userId);
                        created.setRecipeId(recipeId);
                        return created;
                    });
            entity.setPreferenceType(type);
            recipePreferences.save(entity);
        }
    }

    private EatingPreferenceEntity newProfile(String profileId) {
        EatingPreferenceEntity created = new EatingPreferenceEntity();
        created.setProfileId(profileId);
        // Hồ sơ mới mặc định UNKNOWN: hệ thống không tự kết luận người dùng không có dị ứng (BR01).
        created.setAllergyStatus(AllergyDeclarationStatus.UNKNOWN);
        return created;
    }

    // ---------------------------------------------------------------------------------------
    // Đọc
    // ---------------------------------------------------------------------------------------

    private NutritionProfileView toView(String userId, PregnancyEntity pregnancy,
                                        EatingPreferenceEntity preference) {
        ZoneId zone = zones.resolve(pregnancy.getTimezone(), userId);
        GestationalAge age = gestationalAge(pregnancy, zone);
        // Múi giờ của hồ sơ thai kỳ được ưu tiên hơn tuỳ chọn chung của tài khoản: tuần thai phải
        // đổi theo đúng lịch mà người dùng đã khai thai kỳ, kể cả khi họ đang đi công tác.

        EnergyInputsView inputs = preference == null ? null : new EnergyInputsView(
                preference.getAgeYears(), preference.getHeightCm(),
                preference.getPrePregnancyWeightKg(), preference.getCurrentWeightKg(),
                preference.getActivityCategory());
        EnergyEstimate estimate = energyEstimator.estimate(energyInputs(preference, age));

        List<UserRecipePreferenceEntity> recipePrefs = loadRecipePreferences(userId);
        AllergyDeclarationStatus allergyStatus = preference == null
                ? AllergyDeclarationStatus.UNKNOWN : preference.getAllergyStatus();
        boolean declared = allergyStatus != AllergyDeclarationStatus.UNKNOWN;

        return new NutritionProfileView(
                pregnancy.getId(),
                preference == null ? 0 : preference.getProfileVersion(),
                milestone(pregnancy, zone, age),
                allergyStatus,
                sorted(preference == null ? Set.of() : preference.getAllergenIds()),
                // BR01: hồ sơ chưa khai dị ứng vẫn xem được mọi thứ, nhưng danh sách gợi ý không
                // được gắn nhãn "đã kiểm tra theo dị ứng của bạn".
                declared,
                !declared,
                preference == null ? null : preference.getDietPattern(),
                sorted(preference == null ? Set.of() : preference.getHardExclusionIngredientIds()),
                sorted(preference == null ? Set.of() : preference.getDislikedIngredientIds()),
                sorted(preference == null ? Set.of() : preference.getToleranceTags()),
                recipeIds(recipePrefs, RecipePreferenceType.FAVORITE),
                recipeIds(recipePrefs, RecipePreferenceType.BLOCKED),
                preference == null ? null : preference.getDailyBudgetVnd(),
                preference == null ? null : preference.getMaxMinutesPerMeal(),
                preference == null ? List.of()
                        : preference.getMealTypes().stream().sorted().toList(),
                preference == null ? null : preference.getSnackCount(),
                inputs == null || inputs.isEmpty() ? null : inputs,
                energyView(estimate));
    }

    private PregnancyMilestone milestone(PregnancyEntity pregnancy, ZoneId zone,
                                         GestationalAge age) {
        return new PregnancyMilestone(
                pregnancy.getId(), zone.getId(), age.localDate(), age.dueDate(),
                age.days(), age.weeks(), age.dayOfWeek(), age.trimester(),
                age.daysUntilDue(), age.dueDatePassed(),
                // Ngày dự sinh qua đi KHÔNG đồng nghĩa đã sinh, nên hồ sơ vẫn đọc được bình thường
                // và không có gì bị xóa. Chỉ dừng TỰ tạo kế hoạch mới và để giao diện hỏi lại
                // trạng thái — thông tin thai kỳ lúc này có thể không còn phù hợp.
                age.dueDatePassed());
    }

    /**
     * Số ngày thai tại ngày lịch hôm nay của người dùng.
     *
     * <p>Ba nguồn mốc neo, xét theo đúng thứ tự tin cậy mà module thai kỳ đã lưu:</p>
     * <ol>
     *   <li>mốc neo thủ công ({@code gestational_age_anchor_days} tại
     *       {@code gestational_age_anchor_date}) — người dùng tự khai tuần + ngày thai;</li>
     *   <li>ngày đầu kỳ kinh cuối;</li>
     *   <li>ngày dự sinh, quy đổi theo mốc 280 ngày.</li>
     * </ol>
     *
     * <p>Không có nguồn nào thì hồ sơ thai kỳ hỏng ở phía module kia, và tính bừa ra một con số
     * trông như thật còn tệ hơn là dừng lại báo lỗi.</p>
     */
    private GestationalAge gestationalAge(PregnancyEntity pregnancy, ZoneId zone) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate dueDate = pregnancy.getEstimatedDueDate();

        if (pregnancy.getGestationalAgeAnchorDays() != null
                && pregnancy.getGestationalAgeAnchorDate() != null) {
            return GestationalAge.of(
                    GestationalAge.daysFromAnchor(pregnancy.getGestationalAgeAnchorDays(),
                            pregnancy.getGestationalAgeAnchorDate(), today),
                    today, dueDate);
        }
        if (pregnancy.getLastMenstrualPeriod() != null) {
            return GestationalAge.of(
                    GestationalAge.daysFromAnchor(0, pregnancy.getLastMenstrualPeriod(), today),
                    today, dueDate);
        }
        if (dueDate != null) {
            return GestationalAge.of(
                    GestationalAge.daysFromAnchor(0, dueDate.minusDays(GestationalAge.OBSTETRIC_DAYS),
                            today),
                    today, dueDate);
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "Hồ sơ thai kỳ chưa đủ dữ liệu để tính tuổi thai. "
                        + "Vui lòng cập nhật ngày dự sinh hoặc tuần thai.");
    }

    private EnergyEstimateView energyView(EnergyEstimate estimate) {
        return new EnergyEstimateView(estimate.kcalPerDay(), estimate.trimesterGroup(),
                estimate.prePregnancyBmi(), estimate.rulesVersion(),
                estimate.unmet().stream()
                        .map(unmet -> new UnmetView(unmet.field(), unmet.kind().name()))
                        .toList());
    }

    private List<UserRecipePreferenceEntity> loadRecipePreferences(String userId) {
        return recipePreferences.findByUserIdOrderByRecipeId(userId);
    }

    private PregnancyEntity requireActivePregnancy(String userId) {
        return pregnancies.findByOwnerUserIdAndStatus(userId, PregnancyStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVE_PREGNANCY_NOT_FOUND,
                        "Chưa có hồ sơ thai kỳ đang hoạt động. "
                                + "Hoàn tất bước thai kỳ trước khi khai hồ sơ ăn uống."));
    }

    // ---------------------------------------------------------------------------------------
    // Tiện ích
    // ---------------------------------------------------------------------------------------

    /**
     * Ảnh chụp những trường <strong>ảnh hưởng gợi ý</strong>, để biết lượt lưu này có đổi gì không.
     *
     * <p>Hiện mọi trường của hồ sơ đều ảnh hưởng gợi ý nên danh sách này là toàn bộ. Thêm một
     * trường thuần hiển thị về sau thì đừng đưa vào đây, nếu không nhánh 10 sẽ bắt người dùng xem
     * lại thực đơn vì một thay đổi vô hại.</p>
     */
    private List<String> snapshot(EatingPreferenceEntity preference,
                                  List<UserRecipePreferenceEntity> recipePrefs) {
        List<String> parts = new ArrayList<>();
        parts.add(String.valueOf(preference.getAllergyStatus()));
        parts.add(String.join(",", sorted(preference.getAllergenIds())));
        parts.add(String.valueOf(preference.getDietPattern()));
        parts.add(String.join(",", sorted(preference.getHardExclusionIngredientIds())));
        parts.add(String.join(",", sorted(preference.getDislikedIngredientIds())));
        parts.add(String.join(",", sorted(preference.getToleranceTags())));
        parts.add(String.valueOf(preference.getDailyBudgetVnd()));
        parts.add(String.valueOf(preference.getMaxMinutesPerMeal()));
        parts.add(preference.getMealTypes().stream().map(Enum::name).sorted()
                .reduce((left, right) -> left + "," + right).orElse(""));
        parts.add(String.valueOf(preference.getSnackCount()));
        parts.add(String.valueOf(preference.getAgeYears()));
        parts.add(scale(preference.getHeightCm()));
        parts.add(scale(preference.getPrePregnancyWeightKg()));
        parts.add(scale(preference.getCurrentWeightKg()));
        parts.add(String.valueOf(preference.getActivityCategory()));
        parts.add(recipePrefs.stream()
                .map(pref -> pref.getRecipeId() + "=" + pref.getPreferenceType())
                .sorted()
                .reduce((left, right) -> left + "," + right).orElse(""));
        return parts;
    }

    /**
     * So sánh số đo theo <em>giá trị</em>, không theo cách viết.
     *
     * <p>{@code 60} và {@code 60.00} là cùng một cân nặng nhưng khác {@code BigDecimal#toString},
     * nên không chuẩn hóa thì gửi lại y nguyên dữ liệu cũ cũng làm {@code profile_version} tăng.
     */
    private String scale(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    /** Thay nội dung tại chỗ: Hibernate theo dõi chính tập này, gán tập mới sẽ mất liên kết. */
    private void replace(Set<String> target, Collection<String> values) {
        target.clear();
        target.addAll(values);
    }

    private List<String> sorted(Collection<String> values) {
        return new ArrayList<>(new TreeSet<>(values));
    }

    private List<String> recipeIds(List<UserRecipePreferenceEntity> prefs,
                                   RecipePreferenceType type) {
        return prefs.stream()
                .filter(pref -> pref.getPreferenceType() == type)
                .map(UserRecipePreferenceEntity::getRecipeId)
                .toList();
    }

    private Set<String> normalizeTags(List<String> tags) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            normalized.add(tag.trim().toUpperCase());
        }
        return normalized;
    }

    /**
     * Giữ lại các mã có thật trong danh mục, và <strong>từ chối</strong> lượt lưu nếu có mã lạ.
     *
     * <p>Bỏ qua mã lạ cho êm chuyện là cách chắc chắn nhất để một mã dị ứng gõ sai biến mất không
     * ai hay, rồi người dùng tin rằng mình đã khai xong (BR01). Khóa ngoại trong database cũng
     * chặn, nhưng lúc đó lỗi là 500 chứ không chỉ được cho người dùng sai ở trường nào.</p>
     */
    private <T> Set<String> validIds(List<String> requested,
                                     Function<Iterable<String>, List<T>> loader,
                                     Function<T, String> idOf, String field) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String id : requested) {
            if (id != null && !id.isBlank()) wanted.add(id.trim());
        }
        if (wanted.isEmpty()) return wanted;

        Set<String> found = new LinkedHashSet<>();
        for (T entity : loader.apply(wanted)) {
            found.add(idOf.apply(entity));
        }
        Set<String> unknown = new LinkedHashSet<>(wanted);
        unknown.removeAll(found);
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Không tìm thấy mã trong danh mục: " + String.join(", ", unknown) + ".",
                    java.util.Map.of(field, "Mã không tồn tại: " + String.join(", ", unknown)));
        }
        return wanted;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
