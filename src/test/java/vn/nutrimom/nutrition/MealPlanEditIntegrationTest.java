package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Sửa thực đơn đã lưu, chạy thật trên <strong>seed thật</strong>: đổi món, giữ món rồi tạo lại,
 * hoàn tác, và các đường bị từ chối.
 *
 * <p>Dùng seed thật vì điều cần chứng minh ở nhánh này không phải là "hàm trả đúng giá trị" mà là
 * "sửa một lựa chọn trên một thực đơn có thật thì phần còn lại của ngày vẫn khớp" — tổng dinh
 * dưỡng, chi phí và {@code plan_version} phải cùng kể một câu chuyện sau mỗi thao tác.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.nutrition.seed-enabled=true")
class MealPlanEditIntegrationTest extends ApiIntegrationTestSupport {

    private static final String PLANS = "/api/v1/meal-plans";

    /** Hai món rau của bữa trưa trong seed; dùng để đổi qua lại một cách xác định. */
    private static final String SOUP_WITH_SHRIMP = "rec.canh-bi-do-tom";
    private static final String PLAIN_GREENS = "rec.rau-muong-luoc";
    private static final String CRUSTACEAN = "alg.giap-xac";

    @Autowired JdbcTemplate jdbc;

    // =========================================================================================
    // Lựa chọn thay thế
    // =========================================================================================

    /** Cùng bữa, cùng vai trò, không gợi ý lại chính món đang có, và nói rõ tác động. */
    @Test
    void alternativesStaySameRoleAndCarryTheirImpact() throws Exception {
        Session session = onboardedMom("0912390001");
        Plan plan = savedLunchPlan(session);
        Item vegetable = plan.item("VEGETABLE");

        MvcResult result = mockMvc.perform(
                        get(PLANS + "/{id}/items/{itemId}/alternatives", plan.id(), vegetable.id())
                                .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("VEGETABLE"))
                .andExpect(jsonPath("$.data.meal_type").value("LUNCH"))
                .andExpect(jsonPath("$.data.current_recipe_id").value(vegetable.recipeId()))
                .andReturn();

        JsonNode data = data(result);
        assertThat(data.at("/alternatives").size())
                .describedAs("seed có nhiều hơn một món rau cho bữa trưa")
                .isPositive();
        for (JsonNode alternative : data.at("/alternatives")) {
            assertThat(alternative.at("/recipe_id").stringValue())
                    .describedAs("không gợi ý lại chính món đang nằm trong bữa")
                    .isNotEqualTo(vegetable.recipeId());
            assertThat(alternative.has("delta_minutes")).isTrue();
        }
        // Tập khẩu phần đi kèm để giao diện dựng đúng bộ chọn, thay vì tự bịa ra bốn nấc.
        List<String> options = new ArrayList<>();
        data.at("/serving_options").forEach(option -> options.add(option.decimalValue()
                .stripTrailingZeros().toPlainString()));
        assertThat(options).containsExactly("0.5", "1", "1.5", "2");
    }

    /** Hồ sơ đổi thì danh sách lựa chọn đổi theo — không tin kết quả lọc của lúc lập thực đơn. */
    @Test
    void alternativesAreRefilteredAgainstTheProfileAsItIsNow() throws Exception {
        Session session = onboardedMom("0912390002");
        Plan plan = savedLunchPlan(session);
        Item vegetable = plan.item("VEGETABLE");

        saveProfile(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["%s"]}
                """.formatted(CRUSTACEAN));

        MvcResult result = mockMvc.perform(
                        get(PLANS + "/{id}/items/{itemId}/alternatives", plan.id(), vegetable.id())
                                .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        for (JsonNode alternative : data(result).at("/alternatives")) {
            assertThat(alternative.at("/recipe_id").stringValue())
                    .describedAs("món có tôm không được xuất hiện sau khi khai dị ứng giáp xác")
                    .isNotEqualTo(SOUP_WITH_SHRIMP);
        }
    }

    // =========================================================================================
    // TC13 — đổi món thành công
    // =========================================================================================

    /**
     * TC13: sau khi đổi món, <strong>tổng, nguyên liệu và {@code plan_version}</strong> nhất quán.
     *
     * <p>"Nhất quán" ở đây có nghĩa kiểm được: phản hồi của lượt sửa và lần đọc kế hoạch ngay sau
     * đó phải trùng nhau từng con số. Lệch nhau nghĩa là có nơi thứ hai cộng tổng (bất biến 4).</p>
     */
    @Test
    void changingADishKeepsTotalsAndPlanVersionConsistent() throws Exception {
        Session session = onboardedMom("0912390003");
        Plan plan = savedLunchPlan(session);
        Item vegetable = plan.item("VEGETABLE");
        String replacement = vegetable.recipeId().equals(PLAIN_GREENS)
                ? SOUP_WITH_SHRIMP : PLAIN_GREENS;

        MvcResult edited = patchItem(session, plan, vegetable.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,
                 "recipe_id":"%s","recipe_version":1}
                """.formatted(plan.version(), plan.profileVersion(), replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.plan.plan_version").value(plan.version() + 1))
                .andExpect(jsonPath("$.data.shopping_list_version").value(plan.version() + 1))
                .andExpect(jsonPath("$.data.undo_available").value(true))
                .andReturn();

        JsonNode after = data(edited).at("/plan");
        assertThat(recipeIds(after)).contains(replacement);
        assertThat(recipeIds(after)).doesNotContain(vegetable.recipeId());
        // Các mục khác giữ nguyên: sửa một lựa chọn, không lập lại cả ngày.
        assertThat(recipeIds(after)).contains(plan.item("STAPLE").recipeId());

        JsonNode fetched = data(mockMvc.perform(get(PLANS + "/{id}", plan.id())
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(fetched.at("/plan_version").asInt()).isEqualTo(plan.version() + 1);
        assertThat(energy(fetched)).isEqualByComparingTo(energy(after));
        assertThat(cost(fetched)).isEqualTo(cost(after));
        assertThat(recipeIds(fetched)).isEqualTo(recipeIds(after));
    }

    /** Đổi khẩu phần cũng là một thao tác sửa: tổng ngày phải đổi theo ngay. */
    @Test
    void changingServingsUpdatesTheDayTotalImmediately() throws Exception {
        Session session = onboardedMom("0912390004");
        Plan plan = savedLunchPlan(session);
        Item staple = plan.item("STAPLE");
        // Bộ sinh tự chọn khẩu phần, nên bài kiểm đi về phía còn trống của miền 0,5–2 thay vì cắm
        // một con số cố định rồi tình cờ trùng với khẩu phần đang có.
        boolean increase = staple.servings().compareTo(new BigDecimal("2")) < 0;
        String target = increase ? "2" : "0.5";

        MvcResult edited = patchItem(session, plan, staple.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"servings":%s}
                """.formatted(plan.version(), plan.profileVersion(), target))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode after = data(edited).at("/plan");
        assertThat(servingsOf(after, staple.id())).isEqualByComparingTo(target);
        assertThat(energy(after))
                .describedAs("đổi khẩu phần tinh bột thì năng lượng ngày phải đổi theo ngay")
                .matches(value -> increase ? value.compareTo(plan.energy()) > 0
                        : value.compareTo(plan.energy()) < 0);
    }

    /** Nhập theo gram quy về số phần bằng khối lượng một phần của chính món đó. */
    @Test
    void enteringGramsConvertsToServingsUsingTheDishesOwnPortionWeight() throws Exception {
        Session session = onboardedMom("0912390005");
        Plan plan = savedLunchPlan(session);
        Item staple = plan.item("STAPLE");

        // Cơm trắng: một phần 180 g, nên 270 g là 1,5 phần.
        MvcResult edited = patchItem(session, plan, staple.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"grams":270}
                """.formatted(plan.version(), plan.profileVersion()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(servingsOf(data(edited).at("/plan"), staple.id()))
                .isEqualByComparingTo("1.50");
    }

    // =========================================================================================
    // TC14 — phiên bản cũ
    // =========================================================================================

    /** TC14: {@code expected_plan_version} cũ trả <strong>409</strong> và không thay dữ liệu. */
    @Test
    void aStalePlanVersionIsRejectedAndChangesNothing() throws Exception {
        Session session = onboardedMom("0912390006");
        Plan plan = savedLunchPlan(session);
        Item vegetable = plan.item("VEGETABLE");
        String first = vegetable.recipeId().equals(PLAIN_GREENS) ? SOUP_WITH_SHRIMP : PLAIN_GREENS;

        patchItem(session, plan, vegetable.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"%s"}
                """.formatted(plan.version(), plan.profileVersion(), first))
                .andExpect(status().isOk());

        // Thiết bị thứ hai vẫn cầm phiên bản cũ.
        patchItem(session, plan, vegetable.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"%s"}
                """.formatted(plan.version(), plan.profileVersion(), vegetable.recipeId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

        JsonNode fetched = data(mockMvc.perform(get(PLANS + "/{id}", plan.id())
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn());
        assertThat(fetched.at("/plan_version").asInt())
                .describedAs("yêu cầu bị từ chối không được tăng phiên bản")
                .isEqualTo(plan.version() + 1);
        assertThat(recipeIds(fetched))
                .describedAs("dữ liệu giữ nguyên kết quả của lượt sửa đầu tiên")
                .contains(first);
    }

    /** Hồ sơ đã đổi cũng chặn thao tác sửa: lựa chọn người dùng vừa nhìn thấy đã dựa trên hồ sơ cũ. */
    @Test
    void aStaleProfileVersionIsRejectedToo() throws Exception {
        Session session = onboardedMom("0912390007");
        Plan plan = savedLunchPlan(session);

        saveProfile(session, """
                {"allergy_status":"KNOWN_NONE"}
                """);

        patchItem(session, plan, plan.item("STAPLE").id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"servings":1.5}
                """.formatted(plan.version(), plan.profileVersion()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    // =========================================================================================
    // TC24 — số phần
    // =========================================================================================

    /** TC24: số phần 0, âm, hoặc ngoài miền của cấu trúc bữa — báo tại trường, không lưu gì. */
    @Test
    void zeroNegativeOrOutOfRangeServingsAreRejectedAtTheField() throws Exception {
        Session session = onboardedMom("0912390008");
        Plan plan = savedLunchPlan(session);
        String itemId = plan.item("STAPLE").id();

        for (String servings : List.of("0", "-1", "3")) {
            MvcResult rejected = patchItem(session, plan, itemId, """
                    {"expected_plan_version":%d,"expected_profile_version":%d,"servings":%s}
                    """.formatted(plan.version(), plan.profileVersion(), servings))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andReturn();
            assertThat(rejected.getResponse().getContentAsString())
                    .describedAs("số phần %s phải báo lỗi tại trường servings", servings)
                    .contains("servings");
        }

        JsonNode fetched = data(mockMvc.perform(get(PLANS + "/{id}", plan.id())
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn());
        assertThat(fetched.at("/plan_version").asInt()).isEqualTo(plan.version());
        assertThat(servingsOf(fetched, itemId)).isEqualByComparingTo(plan.item("STAPLE").servings());
    }

    /** Một yêu cầu không nêu thay đổi nào vẫn bị từ chối: tăng phiên bản suông sẽ gây 409 giả. */
    @Test
    void aPatchWithNothingToChangeIsRejected() throws Exception {
        Session session = onboardedMom("0912390009");
        Plan plan = savedLunchPlan(session);

        patchItem(session, plan, plan.item("STAPLE").id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d}
                """.formatted(plan.version(), plan.profileVersion()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // =========================================================================================
    // Món không hợp hồ sơ
    // =========================================================================================

    /** Nút Thêm bị chặn, và lý do nói thẳng <strong>thành phần nào</strong> đang xung đột. */
    @Test
    void addingADishWithADeclaredAllergenExplainsTheConflictingIngredient() throws Exception {
        Session session = onboardedMom("0912390010");
        Plan plan = savedLunchPlan(session);
        saveProfile(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["%s"]}
                """.formatted(CRUSTACEAN));
        int profileVersion = currentProfileVersion(session);

        MvcResult rejected = patchItem(session, plan, plan.item("VEGETABLE").id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"%s"}
                """.formatted(plan.version(), profileVersion, SOUP_WITH_SHRIMP))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("RECIPE_NOT_ELIGIBLE"))
                .andReturn();

        assertThat(rejected.getResponse().getContentAsString())
                .describedAs("phải nêu thành phần đang xung đột, không chỉ nói không hợp lệ")
                .contains("dị ứng");
    }

    /** BR06: đổi món phải cùng vai trò — thay rau bằng món tinh bột là đổi cấu trúc bữa. */
    @Test
    void aReplacementFromAnotherRoleIsRejected() throws Exception {
        Session session = onboardedMom("0912390011");
        Plan plan = savedLunchPlan(session);

        patchItem(session, plan, plan.item("VEGETABLE").id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"rec.com-trang"}
                """.formatted(plan.version(), plan.profileVersion()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.recipe_id").isNotEmpty());
    }

    // =========================================================================================
    // TC11 — giữ món rồi tạo lại
    // =========================================================================================

    /** TC11: tạo lại giữ đúng {@code recipe_version} và số phần của món đã giữ. */
    @Test
    void regeneratingKeepsTheHeldDishItsVersionAndItsServings() throws Exception {
        Session session = onboardedMom("0912390012");
        Plan plan = savedLunchPlan(session);
        Item staple = plan.item("STAPLE");

        patchItem(session, plan, staple.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,
                 "servings":1.5,"locked":true}
                """.formatted(plan.version(), plan.profileVersion()))
                .andExpect(status().isOk());

        MvcResult regenerated = regenerate(session, plan.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"seed":42}
                """.formatted(plan.version() + 1, plan.profileVersion()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.plan.plan_version").value(plan.version() + 2))
                .andReturn();

        JsonNode held = itemOf(data(regenerated).at("/plan"), staple.id());
        assertThat(held.at("/recipe_id").stringValue()).isEqualTo(staple.recipeId());
        assertThat(held.at("/recipe_version").asInt()).isEqualTo(staple.recipeVersion());
        assertThat(held.at("/servings").decimalValue()).isEqualByComparingTo("1.50");
        assertThat(held.at("/locked").asBoolean()).isTrue();
    }

    /**
     * Mục đã giữ không còn hợp lệ sau khi hồ sơ đổi: <strong>báo mục đó cần đổi</strong>, chứ
     * không âm thầm bỏ giữ để lượt tạo lại chạy trót lọt.
     */
    @Test
    void anIneligibleHeldDishBlocksRegenerateInsteadOfBeingSilentlyUnlocked() throws Exception {
        Session session = onboardedMom("0912390013");
        Plan plan = savedLunchPlan(session);
        Item vegetable = plan.item("VEGETABLE");

        patchItem(session, plan, vegetable.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,
                 "recipe_id":"%s","locked":true}
                """.formatted(plan.version(), plan.profileVersion(), SOUP_WITH_SHRIMP))
                .andExpect(status().isOk());
        saveProfile(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["%s"]}
                """.formatted(CRUSTACEAN));
        int profileVersion = currentProfileVersion(session);

        regenerate(session, plan.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d}
                """.formatted(plan.version() + 1, profileVersion))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("RECIPE_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.error.fields.item_id").value(vegetable.id()));

        JsonNode fetched = data(mockMvc.perform(get(PLANS + "/{id}", plan.id())
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn());
        assertThat(fetched.at("/plan_version").asInt())
                .describedAs("yêu cầu bị từ chối không đụng vào kế hoạch")
                .isEqualTo(plan.version() + 1);
        assertThat(itemOf(fetched, vegetable.id()).at("/locked").asBoolean())
                .describedAs("món vẫn đang được giữ, hệ thống không tự mở khóa")
                .isTrue();
    }

    // =========================================================================================
    // Hoàn tác
    // =========================================================================================

    /** Hoàn tác trả lại món cũ, nhưng {@code plan_version} vẫn đi tới, không lùi. */
    @Test
    void undoRestoresThePreviousDishAndStillMovesThePlanVersionForward() throws Exception {
        Session session = onboardedMom("0912390014");
        Plan plan = savedLunchPlan(session);
        Item vegetable = plan.item("VEGETABLE");
        String replacement = vegetable.recipeId().equals(PLAIN_GREENS)
                ? SOUP_WITH_SHRIMP : PLAIN_GREENS;

        patchItem(session, plan, vegetable.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"%s"}
                """.formatted(plan.version(), plan.profileVersion(), replacement))
                .andExpect(status().isOk());

        MvcResult undone = undo(session, plan.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d}
                """.formatted(plan.version() + 1, plan.profileVersion()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.plan.plan_version").value(plan.version() + 2))
                .andExpect(jsonPath("$.data.undo_available").value(false))
                .andReturn();

        JsonNode after = data(undone).at("/plan");
        assertThat(recipeIds(after)).contains(vegetable.recipeId());
        assertThat(recipeIds(after)).doesNotContain(replacement);
        assertThat(energy(after))
                .describedAs("tổng quay về đúng con số trước khi sửa")
                .isEqualByComparingTo(plan.energy());

        // Một bước lùi, không phải một ngăn xếp lịch sử.
        undo(session, plan.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d}
                """.formatted(plan.version() + 2, plan.profileVersion()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * Hoàn tác <strong>kiểm tra lại điều kiện hiện tại</strong>: món từng hợp lệ mà nay vi phạm dị
     * ứng thì không được quay lại kế hoạch chỉ vì nó đã từng ở đó.
     */
    @Test
    void undoRechecksTheCurrentConditionsAfterTheProfileChanged() throws Exception {
        Session session = onboardedMom("0912390015");
        Plan plan = savedLunchPlan(session);
        Item vegetable = plan.item("VEGETABLE");

        patchItem(session, plan, vegetable.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"%s"}
                """.formatted(plan.version(), plan.profileVersion(), SOUP_WITH_SHRIMP))
                .andExpect(status().isOk());
        patchItem(session, plan, vegetable.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"%s"}
                """.formatted(plan.version() + 1, plan.profileVersion(), PLAIN_GREENS))
                .andExpect(status().isOk());

        saveProfile(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["%s"]}
                """.formatted(CRUSTACEAN));
        int profileVersion = currentProfileVersion(session);

        MvcResult rejected = undo(session, plan.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d}
                """.formatted(plan.version() + 2, profileVersion))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("RECIPE_NOT_ELIGIBLE"))
                .andReturn();

        assertThat(rejected.getResponse().getContentAsString()).contains("dị ứng");

        JsonNode fetched = data(mockMvc.perform(get(PLANS + "/{id}", plan.id())
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn());
        assertThat(recipeIds(fetched))
                .describedAs("kế hoạch giữ nguyên món an toàn, không bị lùi về món có tôm")
                .contains(PLAIN_GREENS);
    }

    @Test
    void aPlanThatHasNeverBeenEditedHasNothingToUndo() throws Exception {
        Session session = onboardedMom("0912390016");
        Plan plan = savedLunchPlan(session);

        undo(session, plan.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d}
                """.formatted(plan.version(), plan.profileVersion()))
                .andExpect(status().isNotFound());
    }

    // =========================================================================================
    // Bất biến 3 — kế hoạch và nhật ký là hai tập dữ liệu
    // =========================================================================================

    /**
     * Sửa kế hoạch chỉ ghi vào <strong>bảng của kế hoạch</strong>.
     *
     * <p>Đây là cách kiểm được bất biến 3 hôm nay: nhật ký là bảng của nhánh 8 và chưa tồn tại, nên
     * thay vì so một bảng chưa có, bài kiểm đếm số dòng của <em>mọi</em> bảng trong schema
     * {@code app} trước và sau một lượt đổi món, rồi đòi chỉ bốn bảng kế hoạch được phép đổi. Ngày
     * {@code app.diary_entries} ra đời, bài kiểm này tự động canh luôn nó — nếu đường sửa kế hoạch
     * lỡ viết vào nhật ký, nó đỏ mà không ai phải nhớ quay lại sửa test.</p>
     */
    @Test
    void editingThePlanNeverWritesOutsideThePlanTables() throws Exception {
        Session session = onboardedMom("0912390017");
        Plan plan = savedLunchPlan(session);
        Item vegetable = plan.item("VEGETABLE");
        String replacement = vegetable.recipeId().equals(PLAIN_GREENS)
                ? SOUP_WITH_SHRIMP : PLAIN_GREENS;

        Map<String, Long> before = rowCounts();
        patchItem(session, plan, vegetable.id(), """
                {"expected_plan_version":%d,"expected_profile_version":%d,"recipe_id":"%s"}
                """.formatted(plan.version(), plan.profileVersion(), replacement))
                .andExpect(status().isOk());
        Map<String, Long> after = rowCounts();

        List<String> mayChange = List.of("MEAL_PLANS", "MEAL_SLOTS", "MEAL_PLAN_ITEMS",
                "MEAL_PLAN_PREVIEWS");
        for (var entry : before.entrySet()) {
            if (mayChange.contains(entry.getKey())) {
                continue;
            }
            assertThat(after.get(entry.getKey()))
                    .describedAs("sửa kế hoạch không được ghi vào bảng %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    // =========================================================================================
    // Chủ sở hữu
    // =========================================================================================

    /** TC20: tài khoản khác không đọc, không sửa, không tạo lại và không hoàn tác được. */
    @Test
    void anotherAccountCanNeitherReadNorEditThePlan() throws Exception {
        Session owner = onboardedMom("0912390018");
        Session stranger = onboardedMom("0912390019");
        Plan plan = savedLunchPlan(owner);
        String itemId = plan.item("STAPLE").id();
        String versions = """
                "expected_plan_version":%d,"expected_profile_version":%d"""
                .formatted(plan.version(), plan.profileVersion());

        mockMvc.perform(get(PLANS + "/{id}/items/{itemId}/alternatives", plan.id(), itemId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound());
        patchItem(stranger, plan, itemId, "{" + versions + ",\"servings\":1.5}")
                .andExpect(status().isNotFound());
        regenerate(stranger, plan.id(), "{" + versions + "}")
                .andExpect(status().isNotFound());
        undo(stranger, plan.id(), "{" + versions + "}")
                .andExpect(status().isNotFound());
    }

    @Test
    void theEndpointsRefuseAnonymousCallers() throws Exception {
        mockMvc.perform(get(PLANS + "/{id}/items/{itemId}/alternatives", "p", "i"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(PLANS + "/{id}/items/{itemId}", "p", "i")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expected_plan_version":1,"expected_profile_version":0,
                                 "servings":1}
                                """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PLANS + "/{id}/regenerate", "p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expected_plan_version":1,"expected_profile_version":0}
                                """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PLANS + "/{id}/undo", "p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expected_plan_version":1,"expected_profile_version":0}
                                """))
                .andExpect(status().isUnauthorized());
    }

    /** Tiền tố dành cho bản hoàn tác không được để lọt vào {@code request_id} của người dùng. */
    @Test
    void theReservedUndoRequestIdPrefixIsRefused() throws Exception {
        Session session = onboardedMom("0912390020");

        mockMvc.perform(post(PLANS + "/preview")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"request_id":"undo:abc","start_date":"2026-10-14",
                                 "meal_types":["LUNCH"]}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.fields.request_id").isNotEmpty());
    }

    // =========================================================================================
    // Kế hoạch nhiều ngày (nhánh 9)
    // =========================================================================================

    /**
     * Tạo lại một kế hoạch ba ngày giữ nguyên <strong>ba ngày</strong>.
     *
     * <p>Bữa trưa của ba ngày mang cùng loại bữa và cùng {@code sequence}; chỉ có ngày tách chúng
     * ra. Thiếu ngày trong khóa định vị bữa thì lượt tạo lại ghi cả ba ngày bằng kết quả của một
     * ngày — không lỗi nào nổ ra, chỉ là hai ngày lặng lẽ biến mất.</p>
     */
    @Test
    void regeneratingAThreeDayPlanKeepsAllThreeDays() throws Exception {
        Session session = onboardedMom("0912390021");
        MvcResult preview = mockMvc.perform(post(PLANS + "/preview")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"request_id":"req-edit-multiday","start_date":"2026-10-14",
                                 "day_count":3,"meal_types":["LUNCH"],"seed":17}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult saved = mockMvc.perform(post(PLANS)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preview_id\":\""
                                + data(preview).at("/preview_id").stringValue() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String planId = data(saved).at("/plan_id").stringValue();
        int planVersion = data(saved).at("/plan_version").asInt();
        int profileVersion = data(saved).at("/profile_version").asInt();

        MvcResult regenerated = regenerate(session, planId, """
                {"expected_plan_version":%d,"expected_profile_version":%d,"seed":42}
                """.formatted(planVersion, profileVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.days.length()").value(3))
                .andReturn();

        List<String> dates = new ArrayList<>();
        for (JsonNode day : data(regenerated).at("/plan/days")) {
            dates.add(day.at("/local_date").stringValue());
            assertThat(day.at("/slots").size())
                    .describedAs("ngày %s vẫn phải có bữa trưa của nó", day.at("/local_date"))
                    .isEqualTo(1);
            assertThat(day.at("/slots/0/items").size()).isPositive();
        }
        assertThat(dates).containsExactly("2026-10-14", "2026-10-15", "2026-10-16");
    }

    // =========================================================================================
    // Fixture
    // =========================================================================================

    /** Một thực đơn bữa trưa đã lưu, kèm những gì bài kiểm cần trỏ tới. */
    private record Plan(String id, int version, int profileVersion, BigDecimal energy,
                        Map<String, Item> items) {

        Item item(String role) {
            Item item = items.get(role);
            if (item == null) {
                throw new AssertionError("Bữa trưa của seed phải có vai trò " + role);
            }
            return item;
        }
    }

    private record Item(String id, String recipeId, int recipeVersion, BigDecimal servings) {
    }

    private Plan savedLunchPlan(Session session) throws Exception {
        MvcResult preview = mockMvc.perform(post(PLANS + "/preview")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"request_id":"req-edit-%s","start_date":"2026-10-14",
                                 "meal_types":["LUNCH"],"seed":17}
                                """.formatted(session.userId())))
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
        Map<String, Item> items = new LinkedHashMap<>();
        for (JsonNode slot : plan.at("/days/0/slots")) {
            for (JsonNode item : slot.at("/items")) {
                items.put(item.at("/role").stringValue(),
                        new Item(item.at("/item_id").stringValue(),
                                item.at("/recipe_id").stringValue(),
                                item.at("/recipe_version").asInt(),
                                item.at("/servings").decimalValue()));
            }
        }
        return new Plan(plan.at("/plan_id").stringValue(), plan.at("/plan_version").asInt(),
                plan.at("/profile_version").asInt(), energy(plan), items);
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

    private void saveProfile(Session session, String body) throws Exception {
        mockMvc.perform(put("/api/v1/me/nutrition-profile")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private int currentProfileVersion(Session session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me/nutrition-profile")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return data(result).at("/profile_version").asInt();
    }

    private ResultActions patchItem(Session session, Plan plan, String itemId, String body)
            throws Exception {
        return mockMvc.perform(patch(PLANS + "/{id}/items/{itemId}", plan.id(), itemId)
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

    private ResultActions undo(Session session, String planId, String body) throws Exception {
        return mockMvc.perform(post(PLANS + "/{id}/undo", planId)
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // -----------------------------------------------------------------------------------------

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private BigDecimal energy(JsonNode plan) {
        return plan.at("/days/0/nutrition/ENERGY/known_value").decimalValue();
    }

    private String cost(JsonNode plan) {
        return plan.at("/days/0/estimated_cost_vnd").toString();
    }

    private List<String> recipeIds(JsonNode plan) {
        List<String> ids = new ArrayList<>();
        for (JsonNode slot : plan.at("/days/0/slots")) {
            for (JsonNode item : slot.at("/items")) {
                ids.add(item.at("/recipe_id").stringValue());
            }
        }
        return ids;
    }

    private JsonNode itemOf(JsonNode plan, String itemId) {
        for (JsonNode slot : plan.at("/days/0/slots")) {
            for (JsonNode item : slot.at("/items")) {
                if (itemId.equals(item.at("/item_id").stringValue())) {
                    return item;
                }
            }
        }
        throw new AssertionError("Không còn mục " + itemId + " trong kế hoạch");
    }

    private BigDecimal servingsOf(JsonNode plan, String itemId) {
        return itemOf(plan, itemId).at("/servings").decimalValue();
    }

    /** Số dòng của mọi bảng trong schema {@code app}, để so trước và sau một thao tác. */
    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'APP' "
                        + "and table_type = 'BASE TABLE' order by table_name",
                String.class);
        for (String table : tables) {
            counts.put(table, jdbc.queryForObject(
                    "select count(*) from app.\"" + table + "\"", Long.class));
        }
        return counts;
    }
}
