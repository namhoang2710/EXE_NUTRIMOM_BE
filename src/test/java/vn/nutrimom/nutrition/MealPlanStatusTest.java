package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.nutrimom.nutrition.domain.GenerationStatus;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.service.MealPlanService;
import vn.nutrimom.nutrition.service.ReasonNote;

/**
 * Luật chọn {@code generation_status} cho một kế hoạch đã hoàn thành.
 *
 * <p>Tách thành bài riêng vì {@link GenerationStatus#READY} <strong>không tái hiện được qua API
 * thật</strong> với danh mục seed hiện tại: seed còn thiếu nhiều số liệu vi chất nên mọi thực đơn
 * thật đều kèm ghi chú dữ liệu và ra {@link GenerationStatus#READY_WITH_NOTES}. Fixture ở đây dựng
 * lại nốt trạng thái thứ ba, còn {@link GenerationStatus#NO_MATCH} nằm ở bài integration.</p>
 */
class MealPlanStatusTest {

    @Test
    void aPlanWithNothingToReviewIsReady() {
        assertThat(MealPlanService.statusOf(List.of())).isEqualTo(GenerationStatus.READY);
    }

    /**
     * Lập một phần ngày là <strong>lựa chọn của người dùng</strong>, không phải khiếm khuyết của kế
     * hoạch, nên nó không hạ trạng thái — nhưng vẫn phải nằm trong ghi chú để giao diện nói rõ
     * (TC23).
     */
    @Test
    void aPartialDayPlanStaysReadyBecauseItIsAChoiceNotADefect() {
        List<ReasonNote> notes = List.of(
                ReasonNote.of(RecommendationReason.PARTIAL_DAY_PLAN, "LUNCH, DINNER"));

        assertThat(MealPlanService.statusOf(notes)).isEqualTo(GenerationStatus.READY);
    }

    /**
     * Giới hạn theo tuần <strong>đã kiểm và còn trong mức</strong> cũng không hạ trạng thái: đó là
     * một kết quả tốt. Hạ vì nó thì mọi kế hoạch có cá đều mang nhãn "cần xem lại", và cái nhãn
     * đó mất hết ý nghĩa.
     */
    @Test
    void aWeeklyLimitThatWasCheckedAndNotReachedStaysReady() {
        List<ReasonNote> notes = List.of(
                ReasonNote.of(RecommendationReason.WEEKLY_FOOD_LIMIT_CHECKED,
                        "cá và hải sản nhóm nên ăn 120/340 g"));

        assertThat(MealPlanService.statusOf(notes)).isEqualTo(GenerationStatus.READY);
    }

    @Test
    void anyOtherNoteMovesThePlanToReadyWithNotes() {
        for (RecommendationReason reason : List.of(
                RecommendationReason.NUTRITION_DATA_INCOMPLETE,
                RecommendationReason.DAY_OVER_BUDGET,
                RecommendationReason.MEAL_OVER_TIME,
                RecommendationReason.STRICT_BUDGET_WITHOUT_PRICE,
                RecommendationReason.WEEKLY_FOOD_LIMIT_REACHED,
                RecommendationReason.REPEATED_RECIPE_ACROSS_DAYS,
                RecommendationReason.SEARCH_LIMIT)) {
            assertThat(MealPlanService.statusOf(List.of(ReasonNote.of(reason, "1"))))
                    .describedAs("ghi chú %s", reason)
                    .isEqualTo(GenerationStatus.READY_WITH_NOTES);
        }
    }

    /** Mọi mã lý do của nhánh này đều có câu tiếng Việt đọc được, không có mã trần nào lọt ra UI. */
    @Test
    void everyPlanLevelReasonCarriesAReadableSentence() {
        for (RecommendationReason reason : List.of(
                RecommendationReason.ROLE_NO_CANDIDATE,
                RecommendationReason.PARTIAL_DAY_PLAN,
                RecommendationReason.NUTRITION_DATA_INCOMPLETE,
                RecommendationReason.DAY_OVER_BUDGET,
                RecommendationReason.MEAL_OVER_TIME,
                RecommendationReason.STRICT_BUDGET_WITHOUT_PRICE,
                RecommendationReason.LOCKED_ITEM_UNPLACEABLE,
                RecommendationReason.WEEKLY_FOOD_LIMIT_CHECKED,
                RecommendationReason.WEEKLY_FOOD_LIMIT_REACHED,
                RecommendationReason.REPEATED_RECIPE_ACROSS_DAYS)) {
            assertThat(ReasonNote.of(reason, "chi tiết").message())
                    .describedAs("câu của mã %s", reason)
                    .isNotBlank()
                    .doesNotContain("null");
            assertThat(reason.kind()).isEqualTo(RecommendationReason.Kind.RESULT);
        }
    }

    /**
     * {@code SEARCH_LIMIT} không được khẳng định là không tồn tại nghiệm (TC19).
     *
     * <p>Câu chữ là thứ duy nhất người dùng đọc được, nên nó nằm trong phạm vi kiểm thử chứ không
     * phải chuyện trình bày.</p>
     */
    @Test
    void theSearchLimitSentenceNeverClaimsThereIsNoSolution() {
        assertThat(RecommendationReason.SEARCH_LIMIT.message())
                .contains("không có nghĩa là không tồn tại");
    }
}
