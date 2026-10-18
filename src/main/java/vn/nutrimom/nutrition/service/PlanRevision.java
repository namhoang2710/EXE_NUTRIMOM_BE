package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import vn.nutrimom.nutrition.domain.GenerationStatus;
import vn.nutrimom.nutrition.domain.MealPlanEntity;
import vn.nutrimom.nutrition.domain.MealPlanItemEntity;
import vn.nutrimom.nutrition.domain.MealSlotEntity;
import vn.nutrimom.nutrition.domain.MealType;

/**
 * Ảnh chụp một kế hoạch <strong>ngay trước</strong> một thao tác sửa — nguyên liệu của nút Hoàn tác.
 *
 * <h2>Vì sao không có bảng riêng</h2>
 *
 * <p>Nhánh 6 không có migration (_CONTRACT.md mục 2), nên bản hoàn tác nằm nhờ trong
 * {@code app.meal_plan_previews}: đó sẵn là bảng "một thực đơn dạng JSON, có hạn dùng, thuộc về một
 * người, có thể trỏ tới một kế hoạch". Khóa là {@code request_id = "undo:" + plan_id}, nên mỗi kế
 * hoạch giữ đúng <strong>một</strong> bước lùi — "khôi phục phiên bản trước", không phải một ngăn
 * xếp lịch sử. {@code saved_plan_id} trỏ về chính kế hoạch đó, nên bản chụp không sống sót được
 * một kế hoạch đã bị xóa.</p>
 *
 * <p>Chụp cả {@code nutrientSnapshot} chứ không chỉ mã công thức: khôi phục phải trả lại
 * <strong>đúng con số đã hiển thị</strong> trước khi sửa, kể cả khi seed đã đổi công thức trong lúc
 * đó (bất biến 4, BR17). Dựng lại snapshot từ danh mục hiện tại sẽ lặng lẽ viết lại lịch sử.</p>
 *
 * <p>Đây là ảnh chụp của <strong>kế hoạch</strong>, không phải của nhật ký: hoàn tác không bao giờ
 * chạm tới bữa đã ghi (bất biến 3).</p>
 */
public record PlanRevision(int planVersion, int profileVersion, String rulesVersion,
                           GenerationStatus generationStatus, List<RevisionSlot> slots) {

    /** Tiền tố {@code request_id} dành riêng cho bản hoàn tác; người dùng không gửi lên được. */
    public static final String REQUEST_ID_PREFIX = "undo:";

    public PlanRevision {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    public record RevisionSlot(LocalDate localDate, MealType mealType, short sequence,
                               String templateCode, List<RevisionItem> items) {

        public RevisionSlot {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * Một mục đã chụp.
     *
     * @param nutrientSnapshot nguyên văn JSON của cột {@code nutrient_snapshot} — giữ dạng chuỗi
     *                         để khôi phục trả lại đúng byte cũ, không đi qua một vòng
     *                         đọc-rồi-ghi có thể làm đổi cách làm tròn
     * @param costSnapshot     null = <strong>chưa quy đổi được giá</strong>, không phải 0 đồng
     */
    public record RevisionItem(String recipeId, int recipeVersion, BigDecimal servings,
                               boolean locked, String nutrientSnapshot, BigDecimal costSnapshot) {
    }

    /** Khóa của bản hoàn tác thuộc về một kế hoạch. */
    public static String requestIdFor(String planId) {
        return REQUEST_ID_PREFIX + planId;
    }

    /** Chụp lại trạng thái hiện tại của một kế hoạch. */
    public static PlanRevision of(MealPlanEntity plan) {
        List<RevisionSlot> slots = new ArrayList<>();
        for (MealSlotEntity slot : MealPlanService.orderedSlots(plan)) {
            List<RevisionItem> items = new ArrayList<>();
            for (MealPlanItemEntity item : slot.getItems()) {
                items.add(new RevisionItem(item.getRecipeId(), item.getRecipeVersion(),
                        item.getServings(), item.isLocked(), item.getNutrientSnapshot(),
                        item.getCostSnapshot()));
            }
            slots.add(new RevisionSlot(slot.getLocalDate(), slot.getMealType(), slot.getSequence(),
                    slot.getTemplateCode(), items));
        }
        return new PlanRevision(plan.getPlanVersion(), plan.getProfileVersion(),
                plan.getRulesVersion(), plan.getGenerationStatus(), slots);
    }
}
