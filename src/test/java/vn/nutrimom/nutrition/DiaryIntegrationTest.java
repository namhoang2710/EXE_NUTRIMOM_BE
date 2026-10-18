package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.IngredientNutrientValue;
import vn.nutrimom.nutrition.domain.MealFeedbackEntity;
import vn.nutrimom.nutrition.domain.MealFeedbackSignal;
import vn.nutrimom.nutrition.domain.NutrientStatus;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.MealFeedbackRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;
import vn.nutrimom.nutrition.service.MealFeedbackService;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Nhật ký và phản hồi, chạy thật trên <strong>seed thật</strong>.
 *
 * <p>Điều cần chứng minh ở đây không phải "hàm cộng đúng" — {@link NutritionCalculatorTest} đã kiểm
 * điều đó bằng fixture thuần — mà là <strong>bất biến 3 giữ được qua cả một vòng dùng thật</strong>:
 * lập thực đơn, ăn một nửa, ghi lại, sửa, xóa, và tổng dự kiến không nhúc nhích một lần nào.</p>
 *
 * <p>Số liệu dùng để đối chiếu lấy từ món đơn giản nhất của seed, {@code rec.com-trang}: đúng một
 * nguyên liệu {@code ing.gao-te} 300 g sống, 360 kcal/100 g, chia bốn phần — nên một phần là
 * <strong>270 kcal</strong> và nửa phần là <strong>135 kcal</strong>, không có phép làm tròn trung
 * gian nào xen vào giữa.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.nutrition.seed-enabled=true")
class DiaryIntegrationTest extends ApiIntegrationTestSupport {

    private static final String PLANS = "/api/v1/meal-plans";
    private static final String DIARY = "/api/v1/diary";
    private static final String ENTRIES = "/api/v1/diary-entries";
    private static final String FEEDBACK = "/api/v1/meal-feedback";

    private static final String DATE = "2026-10-14";

    /** Cơm trắng: một nguyên liệu, một trạng thái, một phần = 270 kcal tròn. */
    private static final String RICE = "rec.com-trang";
    private static final String RICE_INGREDIENT = "ing.gao-te";
    private static final BigDecimal ONE_SERVING_KCAL = new BigDecimal("270");

    @Autowired private IngredientRepository ingredients;
    @Autowired private RecipeRepository recipes;
    @Autowired private MealFeedbackRepository feedbacks;
    @Autowired private MealFeedbackService feedbackService;
    @Autowired private PlatformTransactionManager transactions;

    // =========================================================================================
    // TC15 — ăn nửa phần
    // =========================================================================================

    /**
     * TC15: ghi 0,5 phần từ một mục kế hoạch 1 phần.
     *
     * <p>Nhật ký tính <strong>một nửa</strong>; mục kế hoạch giữ nguyên số phần của nó và tổng dự
     * kiến của ngày không đổi một chữ số. Đây là bất biến 3 ở dạng ngắn nhất kiểm được.</p>
     */
    @Test
    void loggingHalfAServingCountsHalfAndLeavesThePlanAlone() throws Exception {
        Session session = onboardedMom("0912480001");
        Plan plan = savedLunchPlan(session);
        PlanItem rice = plan.item(RICE);

        JsonNode before = diary(session);
        BigDecimal plannedBefore = energy(before, "planned_nutrition");

        logFromPlan(session, "req-half", rice.itemId(), "0.5");

        JsonNode after = diary(session);
        assertThat(after.at("/entries").size()).isEqualTo(1);
        assertThat(after.at("/entries/0/servings").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(energy(after, "logged_nutrition"))
                .describedAs("nửa phần cơm trắng là một nửa của 270 kcal")
                .isEqualByComparingTo(ONE_SERVING_KCAL.divide(new BigDecimal("2")));

        assertThat(energy(after, "planned_nutrition"))
                .describedAs("ghi nhật ký không chạm vào tổng dự kiến")
                .isEqualByComparingTo(plannedBefore);

        JsonNode planAfter = planBody(session, plan.id());
        assertThat(itemServings(planAfter, rice.itemId()))
                .describedAs("mục kế hoạch vẫn giữ đúng số phần của nó")
                .isEqualByComparingTo(rice.servings());
    }

    /** Hai tổng là hai khối riêng, và không khối nào là tổng của khối kia cộng gì thêm. */
    @Test
    void thePlannedAndLoggedTotalsAreTwoSeparateBlocks() throws Exception {
        Session session = onboardedMom("0912480002");
        Plan plan = savedLunchPlan(session);

        JsonNode diary = diary(session);
        assertThat(diary.at("/planned_nutrition/ENERGY").isMissingNode()).isFalse();
        assertThat(diary.at("/logged_nutrition/ENERGY").isMissingNode()).isFalse();

        // Chưa ghi gì: tổng đã ghi KHÔNG có số, và nó không mượn con số của kế hoạch.
        assertThat(diary.at("/logged_nutrition/ENERGY/known_value").isNull()).isTrue();
        assertThat(energy(diary, "planned_nutrition")).isPositive();

        assertThat(codes(diary))
                .describedAs("con số đã ghi luôn được gọi đúng tên của nó")
                .contains("DIARY_LOGGED_TOTAL_ONLY");
        assertThat(codes(diary))
                .describedAs("bữa trong kế hoạch chưa ghi thì không tự thành đã ăn")
                .contains("DIARY_PLANNED_MEAL_NOT_LOGGED");
        assertThat(plan.id()).isNotBlank();
    }

    // =========================================================================================
    // TC16 — gửi trùng request_id
    // =========================================================================================

    /** TC16: cùng {@code request_id} hai lần chỉ tạo một dòng, và tổng không gấp đôi. */
    @Test
    void theSameRequestIdLogsExactlyOneEntry() throws Exception {
        Session session = onboardedMom("0912480003");
        Plan plan = savedLunchPlan(session);
        PlanItem rice = plan.item(RICE);

        logFromPlan(session, "req-twice", rice.itemId(), "1");
        logFromPlan(session, "req-twice", rice.itemId(), "1");

        JsonNode diary = diary(session);
        assertThat(diary.at("/entries").size()).isEqualTo(1);
        assertThat(energy(diary, "logged_nutrition")).isEqualByComparingTo(ONE_SERVING_KCAL);
    }

    // =========================================================================================
    // TC17 — xóa
    // =========================================================================================

    /** TC17: xóa đổi tổng <strong>đã ghi</strong>, không đổi tổng <strong>dự kiến</strong>. */
    @Test
    void deletingAnEntryChangesTheLoggedTotalOnly() throws Exception {
        Session session = onboardedMom("0912480004");
        Plan plan = savedLunchPlan(session);
        PlanItem rice = plan.item(RICE);

        logFromPlan(session, "req-delete", rice.itemId(), "1");
        JsonNode logged = diary(session);
        BigDecimal plannedBefore = energy(logged, "planned_nutrition");
        String entryId = logged.at("/entries/0/entry_id").stringValue();

        JsonNode afterDelete = data(mockMvc.perform(delete(ENTRIES + "/{id}", entryId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(afterDelete.at("/entries")).isEmpty();
        assertThat(afterDelete.at("/logged_nutrition/ENERGY/known_value").isNull())
                .describedAs("không còn món nào đã ghi thì không còn con số nào")
                .isTrue();
        assertThat(energy(afterDelete, "planned_nutrition"))
                .describedAs("tổng dự kiến đọc từ bảng khác, xóa nhật ký không chạm tới nó")
                .isEqualByComparingTo(plannedBefore);
    }

    /** Sửa số phần tính lại tổng đã ghi, và vẫn không chạm vào kế hoạch. */
    @Test
    void editingTheServingsRecalculatesOnlyTheLoggedTotal() throws Exception {
        Session session = onboardedMom("0912480005");
        Plan plan = savedLunchPlan(session);
        PlanItem rice = plan.item(RICE);

        logFromPlan(session, "req-edit", rice.itemId(), "1");
        JsonNode logged = diary(session);
        BigDecimal plannedBefore = energy(logged, "planned_nutrition");
        String entryId = logged.at("/entries/0/entry_id").stringValue();

        JsonNode patched = data(mockMvc.perform(patch(ENTRIES + "/{id}", entryId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"servings\":0.5}"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(energy(patched, "logged_nutrition"))
                .isEqualByComparingTo(ONE_SERVING_KCAL.divide(new BigDecimal("2")));
        assertThat(energy(patched, "planned_nutrition")).isEqualByComparingTo(plannedBefore);
        assertThat(itemServings(planBody(session, plan.id()), rice.itemId()))
                .isEqualByComparingTo(rice.servings());
    }

    // =========================================================================================
    // TC21 — seed đổi phiên bản
    // =========================================================================================

    /**
     * TC21: seed sửa công thức, lịch sử nhật ký giữ <strong>snapshot cũ</strong>.
     *
     * <p>Bài này cố ý sửa danh mục thật rồi trả lại nguyên trạng: không có cách nào khác để chứng
     * minh con số của một bữa đã ăn đến từ snapshot chứ từ danh mục hiện tại. Nếu nó đọc danh mục,
     * năng lượng của bữa hôm qua sẽ nhảy theo mỗi lần ai đó sửa một dòng seed — và không ai nhìn ra
     * điều đó cho tới khi một con số trong quá khứ đã sai từ lâu.</p>
     */
    @Test
    void theDiaryKeepsTheSnapshotItWasWrittenWith() throws Exception {
        Session session = onboardedMom("0912480006");
        Plan plan = savedLunchPlan(session);
        PlanItem rice = plan.item(RICE);

        logFromPlan(session, "req-snapshot", rice.itemId(), "1");
        JsonNode before = diary(session);
        int versionBefore = before.at("/entries/0/recipe_version").asInt();
        assertThat(energy(before, "logged_nutrition")).isEqualByComparingTo(ONE_SERVING_KCAL);

        BigDecimal originalEnergy = energyPer100g(RICE_INGREDIENT);
        int originalVersion = recipeVersion(RICE);
        try {
            setEnergyPer100g(RICE_INGREDIENT, originalEnergy.multiply(new BigDecimal("10")));
            setRecipeVersion(RICE, originalVersion + 1);

            JsonNode after = diary(session);
            assertThat(energy(after, "logged_nutrition"))
                    .describedAs("bữa đã ăn giữ đúng con số lúc ăn, dù seed đã đổi")
                    .isEqualByComparingTo(ONE_SERVING_KCAL);
            assertThat(after.at("/entries/0/recipe_version").asInt())
                    .describedAs("phiên bản công thức của dòng nhật ký cũng đóng băng")
                    .isEqualTo(versionBefore);
        } finally {
            setEnergyPer100g(RICE_INGREDIENT, originalEnergy);
            setRecipeVersion(RICE, originalVersion);
        }
    }

    // =========================================================================================
    // Món ngoài bộ dữ liệu
    // =========================================================================================

    /**
     * Ghi một món không có trong danh mục: lưu tên và lượng, <strong>không sinh vi chất</strong>.
     *
     * <p>Dòng ấy không mang một con số dinh dưỡng nào, và tổng của ngày hạ xuống PARTIAL kèm lý do
     * — "chưa đủ dữ liệu" phải nói ra, vì một tổng trông đầy đủ mà thiếu hẳn một bữa là con số dễ
     * tin nhầm nhất trong cả màn hình.</p>
     */
    @Test
    void anOffCatalogEntryNeverInventsNutrients() throws Exception {
        Session session = onboardedMom("0912480007");
        savedLunchPlan(session);

        logEntry(session, """
                {"request_id":"req-off-catalog","local_date":"%s","meal_type":"SNACK",
                 "custom_text":"Bánh mì cô Ba đầu ngõ","amount_text":"một ổ"}
                """.formatted(DATE));

        JsonNode diary = diary(session);
        JsonNode entry = diary.at("/entries/0");
        assertThat(entry.at("/custom_text").stringValue()).isEqualTo("Bánh mì cô Ba đầu ngõ");
        assertThat(entry.at("/amount_text").stringValue()).isEqualTo("một ổ");
        assertThat(entry.has("recipe_id")).isFalse();
        assertThat(entry.has("nutrition"))
                .describedAs("không có công thức thì không có bảng dinh dưỡng nào để trả")
                .isFalse();
        assertThat(entry.at("/coverage").stringValue()).isEqualTo("PARTIAL");

        assertThat(diary.at("/logged_nutrition/ENERGY/known_value").isNull())
                .describedAs("tên món không sinh ra một con số năng lượng nào")
                .isTrue();
        assertThat(diary.at("/logged_coverage").stringValue()).isEqualTo("PARTIAL");
        assertThat(codes(diary)).contains("DIARY_OFF_CATALOG_ENTRY");
    }

    /** Món ngoài danh mục không có công thức để quy ra số phần, nên {@code servings} bị từ chối. */
    @Test
    void servingsAreRejectedForAnOffCatalogEntry() throws Exception {
        Session session = onboardedMom("0912480008");

        logEntryRaw(session, """
                {"request_id":"req-bad-shape","local_date":"%s","meal_type":"SNACK",
                 "custom_text":"Bánh mì","servings":1}
                """.formatted(DATE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.servings").exists());
    }

    /**
     * BR16: số phần phải do người dùng xác nhận, máy chủ không mặc định một phần.
     *
     * <p>Mặc định ở đây sẽ là hệ thống tự ghi thay người dùng — đúng thứ nhánh này cấm, và nó sẽ
     * lặng lẽ cộng vào tổng đã ăn một con số chưa ai xác nhận.</p>
     */
    @Test
    void aCatalogEntryWithoutConfirmedServingsIsRejected() throws Exception {
        Session session = onboardedMom("0912480009");

        logEntryRaw(session, """
                {"request_id":"req-no-servings","local_date":"%s","meal_type":"LUNCH",
                 "recipe_id":"%s"}
                """.formatted(DATE, RICE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.servings").exists());
    }

    /** Ghi được món ngoài thực đơn: nguồn kế hoạch là tùy chọn, không phải điều kiện. */
    @Test
    void aDishOutsideThePlanCanStillBeLogged() throws Exception {
        Session session = onboardedMom("0912480010");

        logEntry(session, """
                {"request_id":"req-no-plan","local_date":"%s","meal_type":"LUNCH",
                 "recipe_id":"%s","servings":1}
                """.formatted(DATE, RICE));

        JsonNode diary = diary(session);
        assertThat(diary.at("/entries").size()).isEqualTo(1);
        assertThat(energy(diary, "logged_nutrition")).isEqualByComparingTo(ONE_SERVING_KCAL);
        assertThat(diary.at("/planned_nutrition/ENERGY/known_value").isNull())
                .describedAs("không có kế hoạch nào cho ngày này thì không có tổng dự kiến")
                .isTrue();
    }

    // =========================================================================================
    // Phần còn lại của ngày (UC08)
    // =========================================================================================

    /** Chưa ghi gì thì không khẳng định gì về khẩu phần cả ngày. */
    @Test
    void anEmptyDiarySaysItHasTooLittleDataInsteadOfGuessing() throws Exception {
        Session session = onboardedMom("0912480011");

        JsonNode remaining = diary(session).at("/remaining");
        assertThat(remaining.at("/by_nutrient")).isEmpty();
        assertThat(codes(remaining)).contains("DIARY_TOO_SPARSE");
    }

    /**
     * Đã ghi một phần ngày: khối "phần còn lại" có số, kèm đúng lời cảnh báo của nó.
     *
     * <p>Chất nào tổng đã ghi còn PARTIAL thì <strong>không</strong> có con số — phần còn lại của
     * nó sẽ lớn hơn thực tế, và một con số lớn hơn thực tế ở đây mời người ta ăn bù thứ họ đã ăn
     * rồi.</p>
     */
    @Test
    void theRemainderIsAnEstimateAndSaysSo() throws Exception {
        Session session = momWithEnergyEstimate("0912480012");

        logEntry(session, """
                {"request_id":"req-remaining","local_date":"%s","meal_type":"LUNCH",
                 "recipe_id":"%s","servings":1}
                """.formatted(DATE, RICE));

        JsonNode remaining = diary(session).at("/remaining");
        assertThat(remaining.at("/energy_target_kcal").decimalValue()).isPositive();

        JsonNode energyLeft = remaining.at("/by_nutrient/ENERGY");
        assertThat(energyLeft.isMissingNode()).isFalse();
        assertThat(energyLeft.at("/known_value").decimalValue())
                .describedAs("còn lại = mục tiêu trừ 270 kcal đã ăn")
                .isEqualByComparingTo(remaining.at("/energy_target_kcal").decimalValue()
                        .subtract(ONE_SERVING_KCAL));
        assertThat(codes(remaining)).contains("DIARY_REMAINING_ESTIMATE");

        for (var entry : remaining.at("/by_nutrient").properties()) {
            if (entry.getValue().at("/coverage").stringValue().equals("PARTIAL")) {
                assertThat(entry.getValue().at("/known_value").isNull())
                        .describedAs("%s chưa đủ dữ liệu thì không có con số phần còn lại",
                                entry.getKey())
                        .isTrue();
            }
        }
    }

    /** Một dòng đánh dấu "ước tính chưa đầy đủ" kéo cả tổng của ngày xuống PARTIAL. */
    @Test
    void anEntryMarkedAsAnIncompleteEstimateDowngradesTheWholeDay() throws Exception {
        Session session = momWithEnergyEstimate("0912480013");

        logEntry(session, """
                {"request_id":"req-estimate","local_date":"%s","meal_type":"LUNCH",
                 "recipe_id":"%s","servings":1,"estimate_incomplete":true}
                """.formatted(DATE, RICE));

        JsonNode diary = diary(session);
        assertThat(diary.at("/logged_coverage").stringValue()).isEqualTo("PARTIAL");
        assertThat(codes(diary)).contains("DIARY_ESTIMATE_INCOMPLETE");
        assertThat(diary.at("/remaining/by_nutrient/ENERGY/known_value").isNull())
                .describedAs("số đã ăn chỉ là ước tính thì phần còn lại không có con số")
                .isTrue();
    }

    // =========================================================================================
    // Phản hồi sau bữa
    // =========================================================================================

    /**
     * {@code HARD_NOW} giảm ưu tiên bảy ngày và <strong>không</strong> thành dị ứng.
     *
     * <p>Hai điều phải cùng đúng: hạn đúng bảy ngày, và món <strong>vẫn còn</strong> trong danh
     * sách gợi ý. Biến một lần bấm "khó ăn lúc này" thành một bộ lọc cứng là lặng lẽ dựng lên một
     * khai báo dị ứng mà người dùng chưa bao giờ khai.</p>
     */
    @Test
    void hardNowExpiresInSevenDaysAndNeverBecomesAnAllergy() throws Exception {
        Session session = onboardedMom("0912480014");

        JsonNode feedback = data(mockMvc.perform(post(FEEDBACK)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipe_id\":\"" + RICE + "\",\"type\":\"HARD_NOW\"}"))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(feedback.at("/type").stringValue()).isEqualTo("HARD_NOW");
        OffsetDateTime created = OffsetDateTime.parse(feedback.at("/created_at").stringValue());
        OffsetDateTime expires = OffsetDateTime.parse(feedback.at("/expires_at").stringValue());
        assertThat(expires).isEqualTo(created.plusDays(7));

        JsonNode lunch = recommendLunch(session);
        assertThat(recipeIds(lunch.at("/items")))
                .describedAs("khó ăn lúc này vẫn là một món ăn được, chỉ xếp sau")
                .contains(RICE);
        assertThat(recipeIds(lunch.at("/excluded")))
                .describedAs("phản hồi khẩu vị không bao giờ loại món như một thành phần dị ứng")
                .doesNotContain(RICE);
    }

    /**
     * Món bị "không thích" vẫn vào được thực đơn khi nó là lựa chọn duy nhất của vai trò.
     *
     * <p>Cơm trắng là món {@code STAPLE} duy nhất của bữa trưa trong seed, nên bài này cô lập được
     * đúng một điều: phản hồi khẩu vị <strong>hạ điểm</strong>, nó không phải một bộ lọc. Biến một
     * lần bấm "không thích" thành điều kiện cứng là lặng lẽ dựng lên một khai báo dị ứng mà người
     * dùng chưa bao giờ khai, và kết quả sẽ là một bữa trưa không có món chính (bất biến 5).</p>
     */
    @Test
    void aDislikedDishStillEntersThePlanWhenItsRoleHasNoOneElse() throws Exception {
        Session session = onboardedMom("0912480020");
        submitFeedback(session, RICE, "DISLIKE");

        Plan plan = savedLunchPlan(session);

        assertThat(plan.item(RICE).recipeId())
                .describedAs("không thích là hạ điểm, không phải loại món")
                .isEqualTo(RICE);
    }

    /**
     * Lập lại thực đơn sau khi bấm "không thích": món đó <strong>bị thay</strong>, không bị loại.
     *
     * <p>Cùng seed, cùng hồ sơ, chỉ khác một dòng phản hồi — nên thứ đổi được là thứ hạng chứ
     * không phải bộ ứng viên. Bài này kiểm cả hai nửa: món mới khác món cũ (phản hồi thật sự tới
     * được bộ sinh), và món cũ <strong>vẫn còn</strong> trong danh sách thay thế của vị trí đó
     * (nó không bị đối xử như một thành phần dị ứng).</p>
     */
    @Test
    void aDislikedDishIsSwappedOutWhenThePlanIsBuiltAgain() throws Exception {
        Session session = onboardedMom("0912480022");
        Plan first = savedLunchPlan(session);
        String disliked = first.itemOfRole("PROTEIN").recipeId();

        submitFeedback(session, disliked, "DISLIKE");

        Plan second = savedLunchPlanOn(session, "2026-10-15", "req-after-dislike");
        PlanItem protein = second.itemOfRole("PROTEIN");

        assertThat(protein.recipeId())
                .describedAs("phản hồi phải tới được bộ sinh, không chỉ màn gợi ý")
                .isNotEqualTo(disliked);
        assertThat(alternatives(session, second.id(), protein.itemId()))
                .describedAs("món bị không thích vẫn là một lựa chọn, chỉ xếp sau")
                .contains(disliked);
    }

    /**
     * Món vừa bị "không thích" không còn đứng đầu danh sách <strong>Đổi món</strong>.
     *
     * <p>Đó đúng là lúc người dùng đang tìm cách thoát khỏi món ấy, nên dọn lại chính nó lên đầu là
     * trả lời sai câu họ vừa hỏi. Món vẫn <em>ở trong</em> danh sách — nó chỉ tụt xuống.</p>
     */
    @Test
    void aDislikedDishIsNoLongerFirstInTheSwapList() throws Exception {
        Session session = onboardedMom("0912480021");
        Plan plan = savedLunchPlan(session);
        PlanItem protein = plan.itemOfRole("PROTEIN");

        List<String> before = alternatives(session, plan.id(), protein.itemId());
        assertThat(before)
                .describedAs("seed phải có ít nhất hai món cùng vai trò để nói về thứ tự")
                .hasSizeGreaterThan(1);
        String top = before.get(0);

        submitFeedback(session, top, "DISLIKE");

        List<String> after = alternatives(session, plan.id(), protein.itemId());
        assertThat(after)
                .describedAs("phản hồi khẩu vị không loại món khỏi danh sách thay thế")
                .contains(top);
        assertThat(after.get(0))
                .describedAs("món vừa bị không thích không được dọn lên đầu nữa")
                .isNotEqualTo(top);
        assertThat(after.indexOf(top)).isGreaterThan(before.indexOf(top));
    }

    /** "Hợp khẩu vị" và "không thích" sống tới khi người dùng đổi ý — chúng không có hạn. */
    @Test
    void alastingFeedbackCarriesNoExpiry() throws Exception {
        Session session = onboardedMom("0912480015");

        JsonNode feedback = data(mockMvc.perform(post(FEEDBACK)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipe_id\":\"" + RICE + "\",\"type\":\"DISLIKE\"}"))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(feedback.has("expires_at")).isFalse();
    }

    /**
     * Chỉ <strong>một</strong> phản hồi gần nhất còn hiệu lực đi vào điểm P (QĐ-03).
     *
     * <p>Và một {@code HARD_NOW} đã hết hạn thì biến mất khỏi xếp hạng <em>mà không cần</em> job
     * dọn dẹp nào chạy đúng giờ: hạn được lọc ngay trong câu truy vấn. Dòng cũ vẫn nằm trong bảng
     * — nó là lịch sử đã xảy ra, chỉ thôi ảnh hưởng tới thứ hạng hôm nay.</p>
     */
    @Test
    void onlyTheLatestValidFeedbackReachesTheRanker() throws Exception {
        Session session = onboardedMom("0912480016");

        submitFeedback(session, RICE, "TASTY");
        submitFeedback(session, RICE, "DISLIKE");
        assertThat(feedbackService.latestSignals(session.userId()))
                .containsEntry(RICE, MealFeedbackSignal.DISLIKE);

        inTransaction(() -> {
            MealFeedbackEntity expired = new MealFeedbackEntity();
            expired.setUserId(session.userId());
            expired.setRecipeId("rec.ga-kho-gung");
            expired.setType(MealFeedbackSignal.HARD_NOW);
            expired.setCreatedAt(OffsetDateTime.now().minusDays(30));
            expired.setExpiresAt(OffsetDateTime.now().minusDays(23));
            feedbacks.save(expired);
        });

        assertThat(feedbackService.latestSignals(session.userId()))
                .describedAs("cơn nghén của tháng trước không còn xếp hạng cho hôm nay")
                .doesNotContainKey("rec.ga-kho-gung");
        assertThat(feedbacks.findAll().stream()
                .anyMatch(row -> row.getRecipeId().equals("rec.ga-kho-gung")))
                .describedAs("dòng hết hạn vẫn là lịch sử, không bị xóa")
                .isTrue();
    }

    @Test
    void feedbackForAnUnknownRecipeIsRejected() throws Exception {
        Session session = onboardedMom("0912480017");

        mockMvc.perform(post(FEEDBACK)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipe_id\":\"rec.khong-co-that\",\"type\":\"TASTY\"}"))
                .andExpect(status().isNotFound());
    }

    // =========================================================================================
    // Chủ sở hữu — BR19, TC20
    // =========================================================================================

    /** Tài khoản khác hỏi đúng mã dòng vẫn nhận 404: chủ sở hữu nằm trong câu truy vấn. */
    @Test
    void anotherAccountCannotReadEditOrDeleteMyEntry() throws Exception {
        Session owner = onboardedMom("0912480018");
        Session stranger = onboardedMom("0912480019");

        logEntry(owner, """
                {"request_id":"req-owner","local_date":"%s","meal_type":"LUNCH",
                 "recipe_id":"%s","servings":1}
                """.formatted(DATE, RICE));
        String entryId = diary(owner).at("/entries/0/entry_id").stringValue();

        mockMvc.perform(patch(ENTRIES + "/{id}", entryId)
                        .header("Authorization", "Bearer " + stranger.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"servings\":99}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(ENTRIES + "/{id}", entryId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound());

        assertThat(diary(stranger).at("/entries")).isEmpty();
        assertThat(diary(owner).at("/entries").size()).isEqualTo(1);
    }

    @Test
    void theDiaryNeedsAToken() throws Exception {
        mockMvc.perform(get(DIARY).param("date", DATE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(ENTRIES).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(FEEDBACK).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================================
    // Hạ tầng
    // =========================================================================================

    private JsonNode diary(Session session) throws Exception {
        return data(mockMvc.perform(get(DIARY)
                        .param("date", DATE)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private void logFromPlan(Session session, String requestId, String planItemId, String servings)
            throws Exception {
        logEntry(session, """
                {"request_id":"%s","local_date":"%s","meal_type":"LUNCH",
                 "source_plan_item_id":"%s","servings":%s}
                """.formatted(requestId, DATE, planItemId, servings));
    }

    private void logEntry(Session session, String body) throws Exception {
        logEntryRaw(session, body).andExpect(status().isCreated());
    }

    private ResultActions logEntryRaw(Session session, String body) throws Exception {
        return mockMvc.perform(post(ENTRIES)
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void submitFeedback(Session session, String recipeId, String type) throws Exception {
        mockMvc.perform(post(FEEDBACK)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipe_id\":\"" + recipeId + "\",\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated());
    }

    private JsonNode recommendLunch(Session session) throws Exception {
        JsonNode response = data(mockMvc.perform(post("/api/v1/recommendations")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meal_types\":[\"LUNCH\"],\"limit\":20,\"seed\":17}"))
                .andExpect(status().isOk())
                .andReturn());
        return response.at("/meals/0");
    }

    /** Danh sách món thay thế của một mục, theo đúng thứ tự xếp hạng đã trả về. */
    private List<String> alternatives(Session session, String planId, String itemId)
            throws Exception {
        JsonNode body = data(mockMvc.perform(
                        get(PLANS + "/{id}/items/{itemId}/alternatives", planId, itemId)
                                .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
        List<String> ids = new ArrayList<>();
        for (JsonNode alternative : body.at("/alternatives")) {
            ids.add(alternative.at("/recipe_id").stringValue());
        }
        return ids;
    }

    private JsonNode planBody(Session session, String planId) throws Exception {
        return data(mockMvc.perform(get(PLANS + "/{id}", planId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private Plan savedLunchPlan(Session session) throws Exception {
        return savedLunchPlanOn(session, DATE, "req-diary-" + session.userId());
    }

    private Plan savedLunchPlanOn(Session session, String date, String requestId) throws Exception {
        MvcResult preview = mockMvc.perform(post(PLANS + "/preview")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"request_id":"%s","start_date":"%s",
                                 "meal_types":["LUNCH"],"seed":17}
                                """.formatted(requestId, date)))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult saved = mockMvc.perform(post(PLANS)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preview_id\":\""
                                + data(preview).at("/preview_id").stringValue() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode plan = data(saved);
        List<PlanItem> items = new ArrayList<>();
        for (JsonNode slot : plan.at("/days/0/slots")) {
            for (JsonNode item : slot.at("/items")) {
                items.add(new PlanItem(item.at("/item_id").stringValue(),
                        item.at("/recipe_id").stringValue(),
                        item.at("/role").stringValue(),
                        item.at("/servings").decimalValue()));
            }
        }
        return new Plan(plan.at("/plan_id").stringValue(), List.copyOf(items));
    }

    /**
     * Mẹ bầu đã hoàn thành cả bước 5 onboarding, nên hồ sơ <strong>ước tính được năng lượng</strong>.
     *
     * <p>Chỉ hai bài về "phần còn lại" cần tới nó. Các bài khác cố ý dùng hồ sơ chưa khai đủ, vì
     * đó mới là trạng thái thường gặp — bước 5 bỏ qua được (TC05) — và nhật ký phải chạy đúng cả
     * khi không có mục tiêu năng lượng nào để trừ.</p>
     */
    private Session momWithEnergyEstimate(String phone) throws Exception {
        Session session = onboardedMom(phone);
        mockMvc.perform(put("/api/v1/me/nutrition-profile")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"age_years":29,"height_cm":160.0,"current_weight_kg":58.0,
                                 "pre_pregnancy_weight_kg":52.0,"activity_category":"LOW_ACTIVE"}
                                """))
                .andExpect(status().isOk());
        return session;
    }

    private Session onboardedMom(String phone) throws Exception {
        Session session = registerViaOtp(phone, "Mẹ bầu " + phone);
        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"display_name":"Mẹ bầu","gender":"FEMALE","version":0}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gestational_week":20,"gestational_day":0,
                                 "timezone":"Asia/Ho_Chi_Minh"}
                                """))
                .andExpect(status().isCreated());
        return session;
    }

    // -----------------------------------------------------------------------------------------

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private BigDecimal energy(JsonNode diary, String block) {
        return diary.at("/" + block + "/ENERGY/known_value").decimalValue();
    }

    private BigDecimal itemServings(JsonNode plan, String itemId) {
        for (JsonNode slot : plan.at("/days/0/slots")) {
            for (JsonNode item : slot.at("/items")) {
                if (item.at("/item_id").stringValue().equals(itemId)) {
                    return item.at("/servings").decimalValue();
                }
            }
        }
        throw new AssertionError("Không còn mục " + itemId + " trong kế hoạch");
    }

    private List<String> codes(JsonNode node) {
        List<String> codes = new ArrayList<>();
        for (JsonNode note : node.at("/notes")) {
            codes.add(note.at("/code").stringValue());
        }
        return codes;
    }

    private List<String> recipeIds(JsonNode items) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(item.at("/recipe_id").stringValue());
        }
        return ids;
    }

    // -----------------------------------------------------------------------------------------
    // Sửa seed cho TC21, và trả lại nguyên trạng
    // -----------------------------------------------------------------------------------------

    private BigDecimal energyPer100g(String ingredientId) {
        return new TransactionTemplate(transactions).execute(status ->
                ingredients.findById(ingredientId).orElseThrow().getNutrients().stream()
                        .filter(value -> value.getNutrientCode().equals("ENERGY"))
                        .findFirst().orElseThrow()
                        .getAmountPer100g());
    }

    private void setEnergyPer100g(String ingredientId, BigDecimal amount) {
        inTransaction(() -> {
            IngredientEntity ingredient = ingredients.findById(ingredientId).orElseThrow();
            List<IngredientNutrientValue> updated = ingredient.getNutrients().stream()
                    .map(value -> value.getNutrientCode().equals("ENERGY")
                            ? new IngredientNutrientValue("ENERGY", amount, value.getUnit(),
                                    NutrientStatus.ESTIMATED, value.getSourceId())
                            : value)
                    .toList();
            ingredient.setNutrients(updated);
            ingredients.save(ingredient);
        });
    }

    private int recipeVersion(String recipeId) {
        return recipes.findById(recipeId).orElseThrow().getVersion();
    }

    private void setRecipeVersion(String recipeId, int version) {
        inTransaction(() -> {
            RecipeEntity recipe = recipes.findById(recipeId).orElseThrow();
            recipe.setVersion(version);
            recipes.save(recipe);
        });
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }

    private record Plan(String id, List<PlanItem> items) {

        PlanItem item(String recipeId) {
            return items.stream()
                    .filter(item -> item.recipeId().equals(recipeId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Bữa trưa của seed phải có món " + recipeId));
        }

        PlanItem itemOfRole(String role) {
            return items.stream()
                    .filter(item -> role.equals(item.role()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Bữa trưa của seed phải có vai trò " + role));
        }
    }

    private record PlanItem(String itemId, String recipeId, String role, BigDecimal servings) {
    }
}
