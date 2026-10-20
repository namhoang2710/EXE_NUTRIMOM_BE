package vn.nutrimom.nutrition.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.nutrition.domain.DiaryEntryEntity;
import vn.nutrimom.nutrition.domain.EligibilityStatus;
import vn.nutrimom.nutrition.domain.MealPlanEntity;
import vn.nutrimom.nutrition.domain.MealPlanItemEntity;
import vn.nutrimom.nutrition.domain.MealSlotEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.PlanLifecycleState;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.repository.DiaryEntryRepository;
import vn.nutrimom.nutrition.repository.MealPlanRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;
import vn.nutrimom.nutrition.service.MealPlanCandidates.CatalogContext;

/**
 * Hồ sơ đổi thì các mục <strong>tương lai</strong> được kiểm lại, còn lịch sử đã ăn giữ nguyên
 * (nhánh 10, UC06).
 *
 * <h2>Ba mức ảnh hưởng, và lớp này chỉ xét một mức</h2>
 *
 * <p>Tài liệu nhánh chia thay đổi hồ sơ làm ba mức, và chúng <strong>không</strong> được xử lý như
 * nhau:</p>
 *
 * <ol>
 *   <li><strong>Dị ứng và giới hạn bắt buộc</strong> — dị ứng, chế độ ăn, nguyên liệu phải tránh.
 *       Đây là thứ duy nhất lớp này kiểm: mục xung đột bị đánh dấu và kế hoạch chuyển
 *       {@link PlanLifecycleState#NEEDS_UPDATE}.</li>
 *   <li><strong>Sở thích hoặc ngân sách</strong> — món không thích, món đã chặn, khẩu vị, ngân
 *       sách, thời gian nấu. Chúng chỉ ảnh hưởng <em>lần gợi ý tiếp theo</em>. Kế hoạch hiện tại
 *       giữ nguyên tới khi người dùng tự bấm "Áp dụng cho thực đơn hiện tại"
 *       ({@code POST .../regenerate} của nhánh 6). Vì vậy
 *       {@link ProfileSnapshot#mandatoryConstraints()} cố ý bỏ hết chúng: nếu bước kiểm nhìn thấy
 *       ngân sách, một lần sửa ngân sách sẽ bắt người dùng xem lại cả thực đơn — đúng thứ mức 2
 *       sinh ra để tránh.</li>
 *   <li><strong>Ngày đã ăn</strong> — không đụng tới (BR17). Xem mục dưới.</li>
 * </ol>
 *
 * <h2>Phạm vi: "hôm nay chưa ăn" và mọi ngày sau</h2>
 *
 * <p>Một bữa nằm trong phạm vi kiểm khi ngày của nó <strong>không ở quá khứ</strong> và
 * {@code app.diary_entries} chưa có dòng nào cho đúng cặp (ngày, loại bữa) đó. Đối chiếu ở mức
 * <em>loại bữa</em> chứ không mức từng món, đúng quy ước nhánh 8 đã đặt cho "bữa chưa ghi": ăn
 * khác kế hoạch vẫn là đã ăn bữa đó, và bắt người dùng đổi một món họ đã ăn xong là vô nghĩa.</p>
 *
 * <p>"Hôm nay" lấy theo <strong>lịch của người dùng</strong> ({@code profile.age().localDate()}),
 * không phải ngày UTC của máy chủ: nửa đêm ở Hà Nội và nửa đêm ở London là hai thời điểm khác
 * nhau, và bữa tối hôm nay không được thành "ngày đã qua" vì máy chủ đã sang ngày mới.</p>
 *
 * <h2>Không có cột nào để ghi cờ xung đột — và đó là chủ ý</h2>
 *
 * <p>Nhánh này <strong>không có migration</strong>, nên cờ xung đột của từng mục được
 * <em>tính lại mỗi lần đọc</em> thay vì lưu vào {@code app.meal_plan_items}. Hóa ra đó mới là
 * hướng đúng: một cờ đã lưu sẽ lạc hậu ngay khi người dùng đổi hồ sơ ở thiết bị khác, và lúc đó
 * giao diện nói một món an toàn là nguy hiểm — hoặc tệ hơn, ngược lại. Thứ duy nhất được ghi là
 * {@code meal_plans.lifecycle_state}, và nó chỉ là bản sao để truy vấn; cái đi ra tới giao diện
 * luôn là kết quả vừa tính.</p>
 *
 * <h2>Lớp này không bao giờ tự sửa kế hoạch</h2>
 *
 * <p>Không mở khóa mục đã khóa (BR10), không thay món, không xóa gì. Mục khóa vi phạm dị ứng mới
 * được <strong>báo là cần đổi</strong>, và người dùng quyết định đổi sang món nào.</p>
 */
@Service
public class PlanRevalidationService {

    private final MealPlanRepository plans;
    private final RecipeRepository recipes;
    private final DiaryEntryRepository diary;
    private final MealPlanCandidates candidates;

    public PlanRevalidationService(MealPlanRepository plans, RecipeRepository recipes,
                                   DiaryEntryRepository diary, MealPlanCandidates candidates) {
        this.plans = plans;
        this.recipes = recipes;
        this.diary = diary;
        this.candidates = candidates;
    }

    // =========================================================================================
    // Kiểu dữ liệu đi ra
    // =========================================================================================

    /**
     * Kết quả kiểm lại một kế hoạch.
     *
     * @param state          {@link PlanLifecycleState#NEEDS_UPDATE} khi còn mục trong phạm vi kiểm
     *                       đang xung đột. Trạng thái này <strong>không chặn xem kế hoạch</strong>
     * @param conflictsByItem lý do của từng mục xung đột, theo {@code item_id}. Mục ngoài phạm vi
     *                       kiểm (ngày đã qua, bữa đã ghi) không bao giờ có mặt ở đây
     */
    public record Revalidation(PlanLifecycleState state,
                               Map<String, List<ReasonNote>> conflictsByItem) {

        public Revalidation {
            conflictsByItem = conflictsByItem == null ? Map.of() : Map.copyOf(conflictsByItem);
        }

        /** Kế hoạch không có gì phải xem lại — cũng là câu trả lời khi chưa đọc được hồ sơ. */
        public static Revalidation clean() {
            return new Revalidation(PlanLifecycleState.SAVED, Map.of());
        }

        public List<ReasonNote> reasonsFor(String itemId) {
            return conflictsByItem.getOrDefault(itemId, List.of());
        }

        public boolean conflicts(String itemId) {
            return conflictsByItem.containsKey(itemId);
        }

        public boolean anyConflict() {
            return !conflictsByItem.isEmpty();
        }
    }

    /**
     * Các bữa người dùng <strong>đã ghi nhật ký</strong>, dạng tra nhanh theo bữa của kế hoạch.
     *
     * <p>Tồn tại để cả bước kiểm lại lẫn lượt tạo lại của nhánh 6 trả lời "bữa này đã ăn chưa"
     * bằng <em>đúng một</em> quy tắc. Hai định nghĩa song song sớm muộn sẽ lệch nhau, và lúc đó
     * một bữa đã ăn bị lượt tạo lại viết đè mà không ai thấy.</p>
     */
    public record EatenMeals(Set<String> keys) {

        public EatenMeals {
            keys = keys == null ? Set.of() : Set.copyOf(keys);
        }

        public static EatenMeals none() {
            return new EatenMeals(Set.of());
        }

        public boolean contains(MealSlotEntity slot) {
            return keys.contains(key(slot.getLocalDate(), slot.getMealType()));
        }

        private static String key(LocalDate date, MealType mealType) {
            return date + "|" + mealType.name();
        }
    }

    // =========================================================================================
    // Sau khi lưu hồ sơ
    // =========================================================================================

    /**
     * Kiểm lại mọi kế hoạch <strong>còn hiệu lực</strong> của người dùng và trả về các mục cần đổi.
     *
     * <p>Chỉ lấy kế hoạch có {@code end_date >= hôm nay}: một kế hoạch đã trôi qua hết không còn
     * mục nào trong phạm vi kiểm, và ghi lại {@code lifecycle_state} cho nó là viết vào lịch sử vì
     * một thay đổi không thể ảnh hưởng tới nó nữa (BR17).</p>
     *
     * <p>Danh sách trả về là {@code item_id}, theo thứ tự ngày rồi buổi trong ngày — cùng thứ tự mà
     * {@code GET /api/v1/meal-plans/{id}} hiển thị, nên giao diện duyệt từ trên xuống là gặp đúng
     * thứ tự người dùng đang nhìn.</p>
     */
    @Transactional
    public List<String> afterProfileChange(ProfileSnapshot profile) {
        LocalDate today = profile.age().localDate();
        List<MealPlanEntity> active =
                plans.findByUserIdAndEndDateGreaterThanEqualOrderByStartDateAsc(
                        profile.userId(), today);
        if (active.isEmpty()) {
            return List.of();
        }

        EatenMeals eaten = eatenMealsFrom(profile.userId(), today);
        CatalogContext catalog = candidates.loadCatalog();
        List<String> toRevalidate = new ArrayList<>();

        for (MealPlanEntity plan : active) {
            Revalidation revalidation = check(plan, profile, today, eaten, catalog);
            // Ghi cả khi quay về SAVED: người dùng vừa gỡ một dị ứng thì kế hoạch hết xung đột, và
            // giữ nhãn "cần cập nhật" trên một kế hoạch đã hợp lệ cũng sai như bỏ sót một xung đột.
            plan.setLifecycleState(revalidation.state());
            plans.save(plan);
            for (MealSlotEntity slot : MealPlanService.orderedSlots(plan)) {
                for (MealPlanItemEntity item : slot.getItems()) {
                    if (revalidation.conflicts(item.getId())) {
                        toRevalidate.add(item.getId());
                    }
                }
            }
        }
        return List.copyOf(toRevalidate);
    }

    // =========================================================================================
    // Kiểm một kế hoạch
    // =========================================================================================

    /**
     * Kiểm một kế hoạch theo hồ sơ <strong>như nó đang là</strong>.
     *
     * <p>Đường đọc dùng hàm này; nó không ghi gì, kể cả {@code lifecycle_state}. Nhờ vậy
     * {@code GET} của một kế hoạch luôn nói đúng tình trạng hiện tại ngay cả khi cột đã lạc hậu vì
     * người dùng sửa hồ sơ ở thiết bị khác.</p>
     */
    @Transactional(readOnly = true)
    public Revalidation check(MealPlanEntity plan, ProfileSnapshot profile) {
        LocalDate today = profile.age().localDate();
        if (!inScope(plan, today)) {
            // Kế hoạch đã trôi qua hết: không còn gì để đổi, nên cũng không có gì để kiểm. Nạp danh
            // mục ở đây sẽ là một lượt findAll() cho mỗi lần mở lại một thực đơn cũ.
            return Revalidation.clean();
        }
        return check(plan, profile, today, eatenMealsFrom(profile.userId(), today),
                candidates.loadCatalog());
    }

    private Revalidation check(MealPlanEntity plan, ProfileSnapshot profile, LocalDate today,
                               EatenMeals eaten, CatalogContext catalog) {
        Map<String, List<ReasonNote>> conflicts = new LinkedHashMap<>();

        for (MealSlotEntity slot : MealPlanService.orderedSlots(plan)) {
            if (!revalidates(slot, today, eaten)) {
                continue;
            }
            for (MealPlanItemEntity item : slot.getItems()) {
                RecipeEntity recipe = recipes.findById(item.getRecipeId()).orElse(null);
                if (recipe == null) {
                    // Món đã rời danh mục. Không có gì để đối chiếu với hồ sơ, và gọi nó là xung
                    // đột dị ứng là một lời khẳng định không có dữ liệu nào đỡ. Đường sửa kế hoạch
                    // của nhánh 6 vẫn nói ra chuyện này bằng VERSION_CONFLICT khi người dùng chạm
                    // vào mục đó.
                    continue;
                }
                EligibilityVerdict verdict = candidates.judgeMandatory(recipe, profile, catalog);
                // Chỉ BLOCKED, không lấy UNKNOWN: "chưa rà soát thành phần" là một khoảng trống
                // của danh mục (BR03), không phải một điều kiện người dùng vừa đặt ra. Đánh dấu nó
                // ở đây thì một lượt nhập seed thiếu dữ liệu sẽ bắt mọi kế hoạch đang có phải sửa.
                if (verdict.status() == EligibilityStatus.BLOCKED) {
                    conflicts.put(item.getId(), verdict.reasons());
                }
            }
        }

        return new Revalidation(conflicts.isEmpty()
                ? PlanLifecycleState.SAVED : PlanLifecycleState.NEEDS_UPDATE, conflicts);
    }

    /** Kế hoạch còn ngày nào chưa trôi qua hay không. */
    private boolean inScope(MealPlanEntity plan, LocalDate today) {
        return !plan.getEndDate().isBefore(today);
    }

    /**
     * Bữa này có được kiểm lại không.
     *
     * <p>Hai vế, và cả hai đều là "không đụng tới lịch sử": ngày đã qua thì thôi, và bữa đã có dòng
     * nhật ký thì cũng thôi — kể cả khi nó nằm ở hôm nay hoặc một ngày sau.</p>
     */
    private boolean revalidates(MealSlotEntity slot, LocalDate today, EatenMeals eaten) {
        return !slot.getLocalDate().isBefore(today) && !eaten.contains(slot);
    }

    // =========================================================================================
    // Bữa đã ghi nhật ký
    // =========================================================================================

    /**
     * Các bữa đã ghi từ {@code from} trở đi.
     *
     * <p>Đọc từ {@code app.diary_entries} và <strong>chỉ đọc</strong>: bất biến 3 nói dự kiến và đã
     * ghi là hai tập dữ liệu độc lập, nên lớp này nhìn sang nhật ký để biết đâu là lịch sử, không
     * bao giờ viết vào đó.</p>
     */
    @Transactional(readOnly = true)
    public EatenMeals eatenMealsFrom(String userId, LocalDate from) {
        Set<String> keys = new LinkedHashSet<>();
        for (DiaryEntryEntity entry
                : diary.findByUserIdAndLocalDateGreaterThanEqual(userId, from)) {
            keys.add(EatenMeals.key(entry.getLocalDate(), entry.getMealType()));
        }
        return new EatenMeals(keys);
    }

    /** Các bữa đã ghi trong khoảng ngày của một kế hoạch — dùng cho lượt tạo lại của nhánh 6. */
    @Transactional(readOnly = true)
    public EatenMeals eatenMealsOf(MealPlanEntity plan) {
        return eatenMealsFrom(plan.getUserId(), plan.getStartDate());
    }
}
