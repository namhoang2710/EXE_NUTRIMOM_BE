package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.DietPattern;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.service.RecipeCandidate.IngredientFacts;

/**
 * Bước <strong>lọc điều kiện</strong>: món này có được phép xuất hiện trong thực đơn cá nhân hóa
 * của hồ sơ đang xét hay không.
 *
 * <p>Chạy <strong>trước</strong> {@link RecipeRanker}, nối tiếp chứ không song song. Món chưa qua
 * lọc không bao giờ được chấm điểm (bất biến 2). Lớp này cố ý <em>không</em> nhận bất kỳ ưu tiên
 * mềm nào: yêu thích, món quen, điểm số không có đường nào chạm tới đây, nên cũng không có đường
 * nào vượt qua một điều kiện dị ứng (BR05, TC02).</p>
 *
 * <h2>Duyệt đệ quy qua nguyên liệu con (BR02)</h2>
 *
 * <p>Mọi phép kiểm đều đi qua {@link #walk}, tức là xét cả nguyên liệu con của gia vị và nước dùng
 * hỗn hợp. Nước dùng nấu bằng tôm khô phải chặn món với người dị ứng tôm, dù danh sách nguyên liệu
 * của món không hề viết chữ "tôm" (TC01). Kiểm một tầng là đủ để lọt lưới đúng những món khó thấy
 * nhất.</p>
 *
 * <h2>Thứ tự kiểm — có chủ ý</h2>
 *
 * <ol>
 *   <li>món bị người dùng chặn — loại khỏi ứng viên, không phải trừ điểm (QĐ-03);</li>
 *   <li>dị ứng;</li>
 *   <li>nguyên liệu phải tránh;</li>
 *   <li>chế độ ăn;</li>
 *   <li>nguyên liệu chưa rà soát (BR03);</li>
 *   <li>giới hạn cứng về thời gian và chi phí (BR13).</li>
 * </ol>
 *
 * <p>Một món vừa chứa thành phần gây dị ứng vừa có nguyên liệu chưa rà soát phải ra
 * {@code BLOCKED} chứ không phải {@code UNKNOWN}: đã biết là vi phạm thì không được hạ xuống thành
 * "chưa rõ". Thứ tự trên bảo đảm điều đó.</p>
 */
@Service
public class EligibilityFilter {

    /**
     * Nhóm thực phẩm bị loại theo từng chế độ ăn.
     *
     * <p><strong>Đang chặt hơn mức cần, và biết là vậy.</strong> {@link FoodGroup#MEAT_FISH_EGG}
     * gộp thịt, cá và trứng, nên không tách được "ăn chay có trứng sữa" khỏi "không ăn thịt".
     * Chọn hướng chặt vì đây là điều kiện cùng hạng với dị ứng: thà danh sách ngắn hơn cần còn hơn
     * gợi ý một món người ta không ăn. Lượt gợi ý nào dùng tới bảng này sẽ kèm
     * {@link RecommendationReason#DIET_FILTER_COARSE} để chuyện đó không diễn ra trong im lặng.</p>
     *
     * <p>Gỡ được khi danh mục có nhãn chế độ ăn riêng cho từng nguyên liệu — việc của nhóm nhập
     * liệu seed, không phải của nhánh này.</p>
     */
    private static final Map<DietPattern, Set<FoodGroup>> FORBIDDEN_GROUPS = Map.of(
            DietPattern.OMNIVORE, EnumSet.noneOf(FoodGroup.class),
            DietPattern.PESCATARIAN, EnumSet.of(FoodGroup.MEAT_FISH_EGG),
            DietPattern.LACTO_OVO_VEGETARIAN, EnumSet.of(FoodGroup.MEAT_FISH_EGG),
            DietPattern.VEGAN, EnumSet.of(FoodGroup.MEAT_FISH_EGG, FoodGroup.DAIRY));

    /**
     * Xét một món.
     *
     * @param catalog  toàn bộ nguyên liệu theo mã, để đi được xuống nguyên liệu con. Thiếu một mã
     *                 con thì {@link #walk} dừng ở đó — xem ghi chú trong phương thức đó
     * @param costVnd  chi phí ước tính của một khẩu phần, rỗng khi chưa quy đổi được giá. Chỉ dùng
     *                 khi người dùng bật giới hạn ngân sách cứng
     */
    public EligibilityVerdict judge(RecipeCandidate candidate, ProfileConstraints profile,
                                    Map<String, IngredientFacts> catalog,
                                    Optional<BigDecimal> costVnd) {
        if (profile.blockedRecipeIds().contains(candidate.recipeId())) {
            return EligibilityVerdict.blocked(candidate,
                    ReasonNote.of(RecommendationReason.USER_BLOCKED_RECIPE));
        }

        Optional<ReasonNote> allergy = firstAllergenHit(candidate, profile, catalog);
        if (allergy.isPresent()) {
            return EligibilityVerdict.blocked(candidate, allergy.get());
        }

        Optional<ReasonNote> excluded = firstHardExclusion(candidate, profile, catalog);
        if (excluded.isPresent()) {
            return EligibilityVerdict.blocked(candidate, excluded.get());
        }

        Optional<ReasonNote> diet = firstDietConflict(candidate, profile, catalog);
        if (diet.isPresent()) {
            return EligibilityVerdict.blocked(candidate, diet.get());
        }

        Optional<ReasonNote> unscreened = firstUnscreenedIngredient(candidate, catalog);
        if (unscreened.isPresent()) {
            // BR03: chưa rõ KHÁC có hại. Món vẫn tra cứu được ở GET /api/v1/recipes, chỉ không vào
            // thực đơn cá nhân hóa.
            return EligibilityVerdict.unknown(candidate, unscreened.get());
        }

        if (profile.strictMaxMinutes() != null && candidate.minutes() > profile.strictMaxMinutes()) {
            return EligibilityVerdict.blocked(candidate,
                    ReasonNote.of(RecommendationReason.OVER_TIME_LIMIT,
                            String.valueOf(profile.strictMaxMinutes())));
        }
        if (profile.strictBudgetVnd() != null && costVnd.isPresent()
                && costVnd.get().compareTo(profile.strictBudgetVnd()) > 0) {
            return EligibilityVerdict.blocked(candidate,
                    ReasonNote.of(RecommendationReason.OVER_BUDGET));
        }
        // Giá chưa quy đổi được thì giới hạn ngân sách cứng KHÔNG loại món: loại vì thiếu dữ liệu
        // là phạt người dùng cho một khoảng trống của danh mục. Món đi tiếp và điểm B sẽ tự không
        // hoạt động ở bước chấm điểm.

        return EligibilityVerdict.eligible(candidate);
    }

    // -------------------------------------------------------------------------------------

    private Optional<ReasonNote> firstAllergenHit(RecipeCandidate candidate,
                                                  ProfileConstraints profile,
                                                  Map<String, IngredientFacts> catalog) {
        if (profile.allergenIds().isEmpty()) {
            return Optional.empty();
        }
        for (IngredientFacts line : candidate.ingredients()) {
            // Nguyên liệu viết thẳng trong công thức: nói tên chính nó.
            if (hits(line, profile.allergenIds())) {
                return Optional.of(ReasonNote.of(RecommendationReason.ALLERGEN_MATCH,
                        line.canonicalName()));
            }
            // Nguyên liệu con: nói tên nguyên liệu CHA mà người dùng nhìn thấy trong công thức,
            // vì "nước dùng tôm" mới là thứ họ đọc được, không phải "tôm khô" nằm bên trong.
            boolean viaComponent = walk(line, catalog, facts -> hits(facts, profile.allergenIds()));
            if (viaComponent) {
                return Optional.of(ReasonNote.of(RecommendationReason.ALLERGEN_VIA_COMPONENT,
                        line.canonicalName()));
            }
        }
        return Optional.empty();
    }

    private boolean hits(IngredientFacts facts, Set<String> allergenIds) {
        for (String allergenId : facts.allergenIds()) {
            if (allergenIds.contains(allergenId)) return true;
        }
        return false;
    }

    private Optional<ReasonNote> firstHardExclusion(RecipeCandidate candidate,
                                                    ProfileConstraints profile,
                                                    Map<String, IngredientFacts> catalog) {
        if (profile.hardExclusionIds().isEmpty()) {
            return Optional.empty();
        }
        for (IngredientFacts line : candidate.ingredients()) {
            if (profile.hardExclusionIds().contains(line.ingredientId())
                    || walk(line, catalog,
                            facts -> profile.hardExclusionIds().contains(facts.ingredientId()))) {
                return Optional.of(ReasonNote.of(RecommendationReason.HARD_EXCLUSION,
                        line.canonicalName()));
            }
        }
        return Optional.empty();
    }

    private Optional<ReasonNote> firstDietConflict(RecipeCandidate candidate,
                                                   ProfileConstraints profile,
                                                   Map<String, IngredientFacts> catalog) {
        DietPattern pattern = profile.dietPattern();
        if (pattern == null) {
            // Chưa khai KHÁC ăn tạp: không khai thì không có điều kiện nào để áp, chứ không phải
            // hệ thống tự chọn OMNIVORE giúp người dùng.
            return Optional.empty();
        }
        Set<FoodGroup> forbidden = FORBIDDEN_GROUPS.getOrDefault(pattern, Set.of());
        if (forbidden.isEmpty()) {
            return Optional.empty();
        }
        for (IngredientFacts line : candidate.ingredients()) {
            // Đi cả xuống nguyên liệu con: nước mắm mang nhóm SEASONING nhưng bên trong là cá cơm,
            // và một người ăn chay trường không ăn nước mắm.
            if (forbidden.contains(line.foodGroup())
                    || walk(line, catalog, facts -> forbidden.contains(facts.foodGroup()))) {
                return Optional.of(ReasonNote.of(RecommendationReason.DIET_PATTERN_CONFLICT,
                        pattern.name()));
            }
        }
        return Optional.empty();
    }

    private Optional<ReasonNote> firstUnscreenedIngredient(RecipeCandidate candidate,
                                                           Map<String, IngredientFacts> catalog) {
        for (IngredientFacts line : candidate.ingredients()) {
            if (line.allergenStatus() == AllergenStatus.UNKNOWN
                    || walk(line, catalog,
                            facts -> facts.allergenStatus() == AllergenStatus.UNKNOWN)) {
                return Optional.of(ReasonNote.of(RecommendationReason.UNSCREENED_INGREDIENT,
                        line.canonicalName()));
            }
        }
        return Optional.empty();
    }

    /**
     * Duyệt <strong>nguyên liệu con</strong> của một dòng công thức theo chiều sâu.
     *
     * <p>Không xét chính {@code root} — nơi gọi đã xét nó rồi và cần phân biệt "chứa thẳng" với
     * "chứa bên trong" để nói đúng lý do.</p>
     *
     * <p>Có tập {@code seen} vì danh mục là dữ liệu nhập tay: database chặn được nguyên liệu tự
     * nhận mình làm con, nhưng không chặn được vòng hai bước A→B→A, và một vòng như vậy sẽ treo
     * cả tiến trình. Thà dừng sớm còn hơn.</p>
     *
     * <p>Mã con không có trong {@code catalog} thì nhánh đó dừng. Đây là trường hợp
     * <strong>không nên xảy ra</strong> — khóa ngoại của V57 bảo đảm mọi mã con đều tồn tại — nên
     * không ném lỗi làm hỏng cả lượt gợi ý vì một dòng danh mục lạ.</p>
     */
    private boolean walk(IngredientFacts root, Map<String, IngredientFacts> catalog,
                         java.util.function.Predicate<IngredientFacts> matches) {
        Deque<String> pending = new ArrayDeque<>(root.componentIngredientIds());
        Set<String> seen = new LinkedHashSet<>();
        seen.add(root.ingredientId());
        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (!seen.add(id)) continue;
            IngredientFacts facts = catalog.get(id);
            if (facts == null) continue;
            if (matches.test(facts)) return true;
            pending.addAll(facts.componentIngredientIds());
        }
        return false;
    }

    /** Chế độ ăn này có đang bị lọc chặt hơn mức cần vì danh mục chưa đủ chi tiết hay không. */
    public boolean dietFilterIsCoarse(DietPattern pattern) {
        return pattern == DietPattern.PESCATARIAN
                || pattern == DietPattern.LACTO_OVO_VEGETARIAN;
    }
}
