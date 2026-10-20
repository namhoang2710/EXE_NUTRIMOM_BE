package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Nhánh 10 — đổi hồ sơ thì mục <strong>tương lai</strong> được kiểm lại, lịch sử đã ăn giữ nguyên.
 *
 * <p>Chạy trên seed thật và trên đúng đường HTTP mà giao diện dùng, vì thứ cần chứng minh ở đây
 * không phải "hàm trả đúng giá trị" mà là một hành vi cắt ngang ba module: lưu hồ sơ, đọc thực đơn
 * và ghi nhật ký phải cùng kể một câu chuyện sau khi mẹ bầu khai thêm một dị ứng.</p>
 *
 * <p><strong>Mọi ngày tính từ "hôm nay" thật.</strong> Repo không cắm {@code Clock} cố định, và
 * phạm vi kiểm lại ("hôm nay chưa ăn và mọi ngày sau") chỉ có nghĩa so với hôm nay — cắm một ngày
 * cố định như các bài kiểm khác của module thì bài này sẽ tự đỏ khi ngày đó trôi qua.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.nutrition.seed-enabled=true")
class ProfileRevalidationIntegrationTest extends ApiIntegrationTestSupport {

    private static final String PLANS = "/api/v1/meal-plans";
    private static final String PROFILE = "/api/v1/me/nutrition-profile";

    /** Hai món rau của bữa trưa trong seed; một món có tôm, một món không. */
    private static final String SOUP_WITH_SHRIMP = "rec.canh-bi-do-tom";
    private static final String PLAIN_GREENS = "rec.rau-muong-luoc";
    private static final String CRUSTACEAN = "alg.giap-xac";

    /** Múi giờ của hồ sơ thai kỳ trong fixture — "hôm nay" của người dùng tính theo lịch này. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired JdbcTemplate jdbc;

    // =========================================================================================
    // Thêm dị ứng mới — phạm vi kiểm lại
    // =========================================================================================

    /**
     * Dòng in đậm của bảng tình huống: thêm dị ứng mới thì mục <strong>tương lai</strong> bị đánh
     * dấu, còn mục <strong>đã ăn</strong> không đổi.
     *
     * <p>Ba kế hoạch một ngày — hôm qua, hôm nay, ngày mai — cùng mang món có tôm, và bữa trưa hôm
     * nay đã được ghi vào nhật ký. Khai dị ứng giáp xác phải chạm đúng <em>một</em> trong ba: ngày
     * mai. Hôm qua là lịch sử; hôm nay đã ăn xong, và bắt đổi một bữa đã nấu là một việc không ai
     * làm được (BR17).</p>
     */
    @Test
    void aNewAllergyMarksTomorrowAndLeavesYesterdayAndTheEatenMealAlone() throws Exception {
        Session session = onboardedMom("0912400001");
        LocalDate today = today();

        Plan yesterday = shrimpLunchPlan(session, today.minusDays(1));
        Plan eatenToday = shrimpLunchPlan(session, today);
        Plan tomorrow = shrimpLunchPlan(session, today.plusDays(1));
        logLunch(session, today, eatenToday.vegetableItemId());

        JsonNode saved = declareCrustaceanAllergy(session);

        List<String> toRevalidate = strings(saved.at("/items_to_revalidate"));
        assertThat(toRevalidate)
                .describedAs("chỉ mục của ngày mai nằm trong phạm vi kiểm lại")
                .containsExactly(tomorrow.vegetableItemId());

        JsonNode marked = fetchPlan(session, tomorrow.id());
        assertThat(marked.at("/lifecycle_state").stringValue()).isEqualTo("NEEDS_UPDATE");
        JsonNode conflicting = item(marked, tomorrow.vegetableItemId());
        assertThat(conflicting.at("/conflict").asBoolean()).isTrue();
        assertThat(conflicting.at("/reason_codes").toString())
                .describedAs("lý do phải nêu thành phần đang xung đột, không chỉ nói không hợp lệ")
                .contains("dị ứng");
        assertThat(planNoteCodes(marked)).contains("PLAN_NEEDS_UPDATE_AFTER_PROFILE_CHANGE");

        for (Plan untouched : List.of(yesterday, eatenToday)) {
            JsonNode plan = fetchPlan(session, untouched.id());
            assertThat(plan.at("/lifecycle_state").stringValue())
                    .describedAs("kế hoạch %s không nằm trong phạm vi kiểm lại", untouched.date())
                    .isEqualTo("SAVED");
            assertThat(item(plan, untouched.vegetableItemId()).at("/conflict").asBoolean())
                    .describedAs("mục của %s không được đánh dấu", untouched.date())
                    .isFalse();
            assertThat(item(plan, untouched.vegetableItemId()).at("/recipe_id").stringValue())
                    .describedAs("và nội dung của nó cũng không bị sửa")
                    .isEqualTo(SOUP_WITH_SHRIMP);
        }
    }

    // =========================================================================================
    // TC12 — món giữ vi phạm dị ứng mới
    // =========================================================================================

    /**
     * TC12: món <strong>đang giữ</strong> mà vi phạm dị ứng mới thì được báo là cần đổi — không tự
     * mở khóa, không tự giả định là vẫn hợp lệ, và cũng không tự thay bằng món khác.
     *
     * <p>BR10 nói món khóa vẫn phải được kiểm lại. Đường ngược lại — bỏ qua mục khóa cho yên
     * chuyện — là cách chắc chắn nhất để một món có tôm ở lại trong thực đơn của người vừa khai dị
     * ứng tôm, lại còn mang nhãn "bạn đã chọn giữ món này".</p>
     */
    @Test
    void aHeldDishThatBreaksANewAllergyIsReportedInsteadOfSilentlyUnlocked() throws Exception {
        Session session = onboardedMom("0912400002");
        Plan tomorrow = shrimpLunchPlan(session, today().plusDays(1));
        lock(session, tomorrow);

        JsonNode saved = declareCrustaceanAllergy(session);
        assertThat(strings(saved.at("/items_to_revalidate")))
                .containsExactly(tomorrow.vegetableItemId());

        JsonNode marked = fetchPlan(session, tomorrow.id());
        JsonNode held = item(marked, tomorrow.vegetableItemId());
        assertThat(held.at("/conflict").asBoolean())
                .describedAs("món khóa vẫn được kiểm lại (BR10)")
                .isTrue();
        assertThat(held.at("/locked").asBoolean())
                .describedAs("hệ thống không tự mở khóa")
                .isTrue();
        assertThat(held.at("/recipe_id").stringValue())
                .describedAs("và không tự thay món")
                .isEqualTo(SOUP_WITH_SHRIMP);

        // "Áp dụng cho thực đơn hiện tại" cũng không được lặng lẽ bỏ giữ để chạy trót lọt.
        regenerate(session, tomorrow.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d}
                """.formatted(planVersion(marked), profileVersion(session)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("RECIPE_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.error.fields.item_id")
                        .value(tomorrow.vegetableItemId()));
    }

    // =========================================================================================
    // Đổi sở thích — chỉ ảnh hưởng lần gợi ý sau
    // =========================================================================================

    /**
     * Đổi sở thích <strong>không</strong> đánh dấu thực đơn đang có; nó chờ người dùng bấm "Áp dụng
     * cho thực đơn hiện tại".
     *
     * <p>Đây là ranh giới giữa mức 1 và mức 2 của UC06, và làm sai nó rất dễ: so
     * {@code profile_version} là đủ để mọi lượt lưu hồ sơ biến thực đơn thành "cần cập nhật", kể cả
     * một lần sửa ngân sách. Lúc đó nhãn "cần cập nhật" xuất hiện thường xuyên tới mức không còn ai
     * đọc nó — và lần nó thật sự có nghĩa là lần nói về một món gây dị ứng.</p>
     */
    @Test
    void changingAPreferenceLeavesTheCurrentPlanAloneUntilTheUserAppliesIt() throws Exception {
        Session session = onboardedMom("0912400003");
        Plan tomorrow = shrimpLunchPlan(session, today().plusDays(1));

        MvcResult saved = mockMvc.perform(put(PROFILE)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"blocked_recipe_ids":["%s"],"daily_budget_vnd":40000}
                                """.formatted(SOUP_WITH_SHRIMP)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(strings(data(saved).at("/items_to_revalidate")))
                .describedAs("món đã chặn và ngân sách là ưu tiên mềm, không phải điều kiện bắt buộc")
                .isEmpty();

        JsonNode unchanged = fetchPlan(session, tomorrow.id());
        assertThat(unchanged.at("/lifecycle_state").stringValue()).isEqualTo("SAVED");
        assertThat(planVersion(unchanged))
                .describedAs("lưu hồ sơ không sửa thực đơn")
                .isEqualTo(tomorrow.version());
        assertThat(item(unchanged, tomorrow.vegetableItemId()).at("/conflict").asBoolean()).isFalse();
        assertThat(item(unchanged, tomorrow.vegetableItemId()).at("/recipe_id").stringValue())
                .isEqualTo(SOUP_WITH_SHRIMP);

        // Nút "Áp dụng cho thực đơn hiện tại" dùng lại POST .../regenerate của nhánh 6.
        MvcResult applied = regenerate(session, tomorrow.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d}
                """.formatted(tomorrow.version(), profileVersion(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andReturn();
        assertThat(recipeIds(data(applied).at("/plan")))
                .describedAs("tới lúc này món bị chặn mới rời khỏi thực đơn")
                .doesNotContain(SOUP_WITH_SHRIMP);
    }

    // =========================================================================================
    // Vòng trạng thái
    // =========================================================================================

    /** Đổi hết mục xung đột thì {@code lifecycle_state} tự quay về {@code SAVED}. */
    @Test
    void replacingEveryConflictingItemBringsThePlanBackToSaved() throws Exception {
        Session session = onboardedMom("0912400004");
        Plan tomorrow = shrimpLunchPlan(session, today().plusDays(1));
        declareCrustaceanAllergy(session);

        JsonNode marked = fetchPlan(session, tomorrow.id());
        assertThat(marked.at("/lifecycle_state").stringValue()).isEqualTo("NEEDS_UPDATE");

        MvcResult fixed = patchItem(session, tomorrow.id(), tomorrow.vegetableItemId(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"%s"}
                """.formatted(planVersion(marked), profileVersion(session), PLAIN_GREENS))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(data(fixed).at("/plan/lifecycle_state").stringValue())
                .describedAs("phản hồi của lượt sửa đã nói đúng trạng thái mới")
                .isEqualTo("SAVED");

        JsonNode reread = fetchPlan(session, tomorrow.id());
        assertThat(reread.at("/lifecycle_state").stringValue()).isEqualTo("SAVED");
        assertThat(planNoteCodes(reread)).doesNotContain("PLAN_NEEDS_UPDATE_AFTER_PROFILE_CHANGE");
        for (JsonNode slot : reread.at("/days/0/slots")) {
            for (JsonNode item : slot.at("/items")) {
                assertThat(item.at("/conflict").asBoolean()).isFalse();
            }
        }
    }

    /**
     * {@code NEEDS_UPDATE} <strong>không chặn</strong> việc xem kế hoạch: tổng dinh dưỡng, chi phí
     * và danh sách bữa vẫn đi ra đầy đủ.
     *
     * <p>Một thực đơn bị khóa lại chỉ vì hồ sơ vừa đổi sẽ đẩy người dùng vào đúng chỗ họ không có
     * gì để làm — họ cần nhìn thấy món nào cần đổi thì mới đổi được.</p>
     */
    @Test
    void needsUpdateStillShowsTheWholePlan() throws Exception {
        Session session = onboardedMom("0912400005");
        Plan tomorrow = shrimpLunchPlan(session, today().plusDays(1));
        declareCrustaceanAllergy(session);

        JsonNode marked = fetchPlan(session, tomorrow.id());
        assertThat(marked.at("/days/0/slots/0/items").size()).isPositive();
        assertThat(marked.at("/days/0/nutrition/ENERGY").isObject())
                .describedAs("khối dinh dưỡng của ngày vẫn được dựng")
                .isTrue();
        assertThat(recipeIds(marked)).contains(SOUP_WITH_SHRIMP);
    }

    // =========================================================================================
    // Lịch sử đã ăn
    // =========================================================================================

    /**
     * Đổi hồ sơ <strong>không viết lại</strong> dòng nhật ký đã ghi, kể cả khi món đó nay vi phạm
     * dị ứng mới khai (BR17).
     *
     * <p>So cả dòng nhật ký lẫn tổng "đã ghi" của ngày trước và sau: một con số lệch nghĩa là có
     * đường nào đó tính lại bữa đã ăn theo hồ sơ hôm nay, và lúc đó nhật ký không còn là bản ghi
     * của việc đã xảy ra.</p>
     */
    @Test
    void anEatenDayKeepsItsEntryAndItsTotalsAfterTheProfileChanges() throws Exception {
        Session session = onboardedMom("0912400006");
        LocalDate today = today();
        Plan plan = shrimpLunchPlan(session, today);
        logLunch(session, today, plan.vegetableItemId());

        JsonNode before = diary(session, today);
        declareCrustaceanAllergy(session);
        JsonNode after = diary(session, today);

        assertThat(after.at("/entries").toString())
                .describedAs("dòng nhật ký giữ nguyên tới từng trường: món, phiên bản công thức, "
                        + "số phần đã xác nhận và ghi chú của chính nó")
                .isEqualTo(before.at("/entries").toString());
        assertThat(after.at("/logged_nutrition").toString())
                .describedAs("tổng đã ghi không được tính lại theo hồ sơ hôm nay")
                .isEqualTo(before.at("/logged_nutrition").toString());
        assertThat(after.at("/planned_nutrition").toString())
                .isEqualTo(before.at("/planned_nutrition").toString());

        // Và mục kế hoạch của bữa đã ăn cũng không bị đụng tới.
        JsonNode eaten = item(fetchPlan(session, plan.id()), plan.vegetableItemId());
        assertThat(eaten.at("/recipe_id").stringValue()).isEqualTo(SOUP_WITH_SHRIMP);
        assertThat(eaten.at("/conflict").asBoolean()).isFalse();
    }

    /**
     * Không có đường nào từ lượt lưu hồ sơ sửa được bản ghi của ngày đã ăn.
     *
     * <p>Kiểm ở mức database chứ không ở mức response: so <strong>nguyên văn</strong> mọi dòng của
     * {@code app.diary_entries} và {@code app.meal_plan_items} trước và sau một lượt
     * {@code PUT} khai thêm dị ứng. Một bài kiểm đọc qua API chỉ chứng minh API đang trả đúng; cái
     * cần chứng minh là không có câu {@code UPDATE} nào chạm tới hai bảng đó — kể cả một câu viết
     * đúng giá trị cũ, vì câu tiếp theo ai đó thêm vào sẽ không còn như vậy.</p>
     */
    @Test
    void savingTheProfileRewritesNeitherTheDiaryNorAnyPlanItem() throws Exception {
        Session session = onboardedMom("0912400012");
        LocalDate today = today();
        shrimpLunchPlan(session, today.minusDays(1));
        Plan eatenToday = shrimpLunchPlan(session, today);
        shrimpLunchPlan(session, today.plusDays(1));
        logLunch(session, today, eatenToday.vegetableItemId());

        String diaryBefore = rows("diary_entries");
        String itemsBefore = rows("meal_plan_items");

        declareCrustaceanAllergy(session);

        assertThat(rows("diary_entries"))
                .describedAs("thay đổi hồ sơ không viết lại lịch sử đã ăn (BR17)")
                .isEqualTo(diaryBefore);
        assertThat(rows("meal_plan_items"))
                .describedAs("đánh dấu là một phép tính lúc đọc, không phải một câu UPDATE — "
                        + "kể cả mục đang xung đột cũng không bị sửa, vì đổi món là việc của "
                        + "người dùng")
                .isEqualTo(itemsBefore);
    }

    /**
     * BR11: lượt tạo lại <strong>không sửa bữa đã ăn</strong>, và nói ra là nó đã giữ bữa nào.
     *
     * <p>Kế hoạch ba ngày, bữa trưa hôm nay đã ghi. Bấm "Tạo lại" thì hôm nay phải còn nguyên từng
     * mục — cùng {@code item_id}, cùng món, cùng số phần — trong khi các ngày sau được lập lại.</p>
     */
    @Test
    void regeneratingNeverTouchesAMealThatIsAlreadyLogged() throws Exception {
        Session session = onboardedMom("0912400007");
        LocalDate today = today();
        Plan plan = lunchPlan(session, today, 3);
        logLunch(session, today, plan.vegetableItemId());

        String eatenDayBefore = dayAt(fetchPlan(session, plan.id()), today).toString();

        MvcResult regenerated = regenerate(session, plan.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"seed":4242}
                """.formatted(plan.version(), plan.profileVersion()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andReturn();

        assertThat(noteCodes(data(regenerated).at("/notes")))
                .describedAs("bữa không đổi mà không ai giải thích trông như một lỗi")
                .contains("PLAN_KEPT_EATEN_MEAL");
        assertThat(dayAt(data(regenerated).at("/plan"), today).toString())
                .describedAs("ngày đã ghi nhật ký còn nguyên từng mục sau lượt tạo lại")
                .isEqualTo(eatenDayBefore);
    }

    // =========================================================================================
    // Bảng tình huống — các dòng còn lại mà nhánh này chạm tới
    // =========================================================================================

    /**
     * "Chưa có kế hoạch": lưu hồ sơ vẫn chạy bình thường, và ngày hôm nay vẫn có thứ để làm — bữa
     * gợi ý và một màn nhật ký mở được, không phải một màn hình trắng.
     */
    @Test
    void withoutAnyPlanTheDayStillOffersSomethingToDo() throws Exception {
        Session session = onboardedMom("0912400008");

        assertThat(strings(declareCrustaceanAllergy(session).at("/items_to_revalidate")))
                .describedAs("không có kế hoạch nào thì cũng không có gì phải kiểm lại")
                .isEmpty();

        MvcResult suggestions = mockMvc.perform(post("/api/v1/recommendations")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"meal_types":["LUNCH"],"limit":5}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(data(suggestions).at("/meals/0/items").size())
                .describedAs("vẫn có bữa để gợi ý cho hôm nay")
                .isPositive();

        JsonNode diary = diary(session, today());
        assertThat(diary.at("/planned_nutrition").isObject()).isTrue();
        assertThat(diary.at("/entries").size()).isZero();
    }

    /**
     * Khẩu vị tạm thời do khó ăn là một <strong>ưu tiên có thời hạn</strong>, không phải một dị ứng.
     *
     * <p>Phản hồi {@code HARD_NOW} hạ điểm món trong bảy ngày và chỉ thế thôi: nó không khai thêm
     * dị ứng nào vào hồ sơ, và không biến một mục đang có trong thực đơn thành mục cần đổi.</p>
     */
    @Test
    void aTemporaryDislikeNeverBecomesAnAllergy() throws Exception {
        Session session = onboardedMom("0912400009");
        Plan tomorrow = shrimpLunchPlan(session, today().plusDays(1));

        mockMvc.perform(post("/api/v1/meal-feedback")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipe_id":"%s","type":"HARD_NOW"}
                                """.formatted(SOUP_WITH_SHRIMP)))
                .andExpect(status().isCreated());

        JsonNode profile = data(mockMvc.perform(get(PROFILE)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(profile.at("/allergen_ids").size())
                .describedAs("một lần bấm \"khó ăn lúc này\" không khai dị ứng cho người dùng")
                .isZero();

        JsonNode plan = fetchPlan(session, tomorrow.id());
        assertThat(plan.at("/lifecycle_state").stringValue()).isEqualTo("SAVED");
        assertThat(item(plan, tomorrow.vegetableItemId()).at("/conflict").asBoolean()).isFalse();
    }

    /** Tài khoản khác đổi hồ sơ không chạm được vào thực đơn của ai (BR19). */
    @Test
    void anotherAccountsProfileChangeNeverMarksThisPlan() throws Exception {
        Session owner = onboardedMom("0912400010");
        Session stranger = onboardedMom("0912400011");
        Plan tomorrow = shrimpLunchPlan(owner, today().plusDays(1));

        assertThat(strings(declareCrustaceanAllergy(stranger).at("/items_to_revalidate")))
                .isEmpty();

        JsonNode plan = fetchPlan(owner, tomorrow.id());
        assertThat(plan.at("/lifecycle_state").stringValue()).isEqualTo("SAVED");
        assertThat(item(plan, tomorrow.vegetableItemId()).at("/conflict").asBoolean()).isFalse();
    }

    // =========================================================================================
    // Fixture
    // =========================================================================================

    /** Một kế hoạch bữa trưa đã lưu, kèm mục rau mà các bài kiểm trỏ tới. */
    private record Plan(String id, LocalDate date, int version, int profileVersion,
                        String vegetableItemId) {
    }

    private LocalDate today() {
        return LocalDate.now(USER_ZONE);
    }

    /** Kế hoạch bữa trưa một ngày, với món rau đã được đổi thành món có tôm. */
    private Plan shrimpLunchPlan(Session session, LocalDate date) throws Exception {
        Plan plan = lunchPlan(session, date, 1);
        JsonNode fetched = fetchPlan(session, plan.id());
        if (SOUP_WITH_SHRIMP.equals(item(fetched, plan.vegetableItemId())
                .at("/recipe_id").stringValue())) {
            return plan;
        }
        MvcResult patched = patchItem(session, plan.id(), plan.vegetableItemId(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"%s"}
                """.formatted(plan.version(), plan.profileVersion(), SOUP_WITH_SHRIMP))
                .andExpect(status().isOk())
                .andReturn();
        return new Plan(plan.id(), plan.date(), planVersion(data(patched).at("/plan")),
                plan.profileVersion(), plan.vegetableItemId());
    }

    private Plan lunchPlan(Session session, LocalDate startDate, int dayCount) throws Exception {
        MvcResult preview = mockMvc.perform(post(PLANS + "/preview")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"request_id":"req-reval-%s-%s","start_date":"%s",
                                 "day_count":%d,"meal_types":["LUNCH"],"seed":17}
                                """.formatted(session.userId(), startDate, startDate, dayCount)))
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
        return new Plan(plan.at("/plan_id").stringValue(), startDate,
                plan.at("/plan_version").asInt(), plan.at("/profile_version").asInt(),
                itemIdOf(dayAt(plan, startDate), "VEGETABLE"));
    }

    /** Khóa mục rau — người dùng nói họ muốn giữ đúng món đó. */
    private void lock(Session session, Plan plan) throws Exception {
        patchItem(session, plan.id(), plan.vegetableItemId(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"locked":true}
                """.formatted(plan.version(), plan.profileVersion()))
                .andExpect(status().isOk());
    }

    private JsonNode declareCrustaceanAllergy(Session session) throws Exception {
        MvcResult saved = mockMvc.perform(put(PROFILE)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allergy_status":"KNOWN_LIST","allergen_ids":["%s"]}
                                """.formatted(CRUSTACEAN)))
                .andExpect(status().isOk())
                .andReturn();
        return data(saved);
    }

    private void logLunch(Session session, LocalDate date, String planItemId) throws Exception {
        mockMvc.perform(post("/api/v1/diary-entries")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"request_id":"diary-%s-%s","local_date":"%s","meal_type":"LUNCH",
                                 "source_plan_item_id":"%s","servings":1}
                                """.formatted(session.userId(), date, date, planItemId)))
                .andExpect(status().isCreated());
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

    private ResultActions patchItem(Session session, String planId, String itemId, String body)
            throws Exception {
        return mockMvc.perform(patch(PLANS + "/{id}/items/{itemId}", planId, itemId)
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions regenerate(Session session, String planId, String body) throws Exception {
        return mockMvc.perform(post(PLANS + "/{id}/regenerate", planId)
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode fetchPlan(Session session, String planId) throws Exception {
        return data(mockMvc.perform(get(PLANS + "/{id}", planId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode diary(Session session, LocalDate date) throws Exception {
        return data(mockMvc.perform(get("/api/v1/diary").param("date", date.toString())
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private int profileVersion(Session session) throws Exception {
        return data(mockMvc.perform(get(PROFILE)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn())
                .at("/profile_version").asInt();
    }

    // -----------------------------------------------------------------------------------------

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private int planVersion(JsonNode plan) {
        return plan.at("/plan_version").asInt();
    }

    private JsonNode dayAt(JsonNode plan, LocalDate date) {
        for (JsonNode day : plan.at("/days")) {
            if (date.toString().equals(day.at("/local_date").stringValue())) {
                return day;
            }
        }
        throw new AssertionError("Kế hoạch không có ngày " + date);
    }

    private String itemIdOf(JsonNode day, String role) {
        for (JsonNode slot : day.at("/slots")) {
            for (JsonNode item : slot.at("/items")) {
                if (role.equals(item.at("/role").stringValue())) {
                    return item.at("/item_id").stringValue();
                }
            }
        }
        throw new AssertionError("Bữa trưa của seed phải có vai trò " + role);
    }

    private JsonNode item(JsonNode plan, String itemId) {
        for (JsonNode day : plan.at("/days")) {
            for (JsonNode slot : day.at("/slots")) {
                for (JsonNode item : slot.at("/items")) {
                    if (itemId.equals(item.at("/item_id").stringValue())) {
                        return item;
                    }
                }
            }
        }
        throw new AssertionError("Không còn mục " + itemId + " trong kế hoạch");
    }

    private List<String> recipeIds(JsonNode plan) {
        List<String> ids = new ArrayList<>();
        for (JsonNode day : plan.at("/days")) {
            for (JsonNode slot : day.at("/slots")) {
                for (JsonNode item : slot.at("/items")) {
                    ids.add(item.at("/recipe_id").stringValue());
                }
            }
        }
        return ids;
    }

    private List<String> planNoteCodes(JsonNode plan) {
        return noteCodes(plan.at("/notes"));
    }

    private List<String> noteCodes(JsonNode notes) {
        List<String> codes = new ArrayList<>();
        notes.forEach(note -> codes.add(note.at("/code").stringValue()));
        return codes;
    }

    /**
     * Nguyên văn mọi dòng của một bảng, theo thứ tự ổn định để so được hai lần đọc.
     *
     * <p>Cột JSONB về tới đây dưới dạng {@code byte[]}, và {@code toString} của mảng là mã băm của
     * <em>đối tượng</em> — hai lần đọc cùng một dòng sẽ khác nhau. Đọc ra chữ thì mới so được
     * chính cái cần so: nội dung snapshot.</p>
     */
    private String rows(String table) {
        StringBuilder dump = new StringBuilder();
        for (var row : jdbc.queryForList("select * from app." + table + " order by id")) {
            new java.util.TreeMap<>(row).forEach((column, value) -> dump
                    .append(column).append('=')
                    .append(value instanceof byte[] bytes
                            ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                            : value)
                    .append('\n'));
        }
        return dump.toString();
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.stringValue()));
        return values;
    }
}
