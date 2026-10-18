package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Danh sách mua sắm, chạy thật trên <strong>seed thật</strong>: tổng hợp nguyên liệu, tick đã có /
 * đã mua, đổi món rồi xem danh sách cập nhật, và các đường bị từ chối.
 *
 * <p>Dùng seed thật vì điều cần chứng minh không phải "hàm cộng đúng" — bài
 * {@link ShoppingAggregatorTest} đã kiểm điều đó bằng fixture thuần, gồm cả TC10 — mà là "một thực
 * đơn có thật biến thành một danh sách đi chợ được, và nó sống sót qua một lượt đổi món".</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.nutrition.seed-enabled=true")
class ShoppingListIntegrationTest extends ApiIntegrationTestSupport {

    private static final String PLANS = "/api/v1/meal-plans";
    private static final String ITEMS = "/api/v1/shopping-list-items";

    /** Gạo tẻ: có giá theo kg nên quy đổi được, và là nguyên liệu duy nhất của món cơm trắng. */
    private static final String RICE = "ing.gao-te";

    // =========================================================================================
    // Tổng hợp
    // =========================================================================================

    /** Mỗi dòng nói đủ ba điều: lượng cần, món sử dụng, trạng thái. */
    @Test
    void theListTellsHowMuchIsNeededAndWhichDishesUseIt() throws Exception {
        Session session = onboardedMom("0912470001");
        Plan plan = savedLunchPlan(session);

        JsonNode list = shoppingList(session, plan.id());
        assertThat(list.at("/shopping_list_version").asInt()).isEqualTo(plan.version());
        assertThat(list.at("/start_date").stringValue()).isEqualTo("2026-10-14");

        Map<String, JsonNode> rows = rows(list);
        assertThat(rows).containsKey(RICE);

        JsonNode rice = rows.get(RICE);
        assertThat(rice.at("/unit").stringValue()).isEqualTo("g");
        assertThat(rice.at("/quantity").decimalValue()).isPositive();
        assertThat(rice.at("/state").stringValue()).isEqualTo("NEEDED");
        assertThat(rice.at("/used_by").size())
                .describedAs("dòng phải nói món nào dùng nguyên liệu này")
                .isPositive();
        assertThat(rice.at("/used_by/0/name").stringValue()).isNotBlank();

        // Lượng cần của dòng đúng bằng tổng các lần dùng — không có đường tính thứ hai.
        BigDecimal fromUsages = BigDecimal.ZERO;
        for (JsonNode usage : rice.at("/used_by")) {
            fromUsages = fromUsages.add(usage.at("/quantity").decimalValue());
        }
        assertThat(rice.at("/quantity").decimalValue()).isEqualByComparingTo(fromUsages);
    }

    /** Nhóm hàng theo thứ tự đi chợ, và không dòng nào lọt ra ngoài một nhóm. */
    @Test
    void linesAreGroupedForWalkingThroughAMarket() throws Exception {
        Session session = onboardedMom("0912470002");
        Plan plan = savedLunchPlan(session);

        JsonNode list = shoppingList(session, plan.id());

        List<String> groups = new ArrayList<>();
        for (JsonNode group : list.at("/groups")) {
            groups.add(group.at("/food_group").stringValue());
            assertThat(group.at("/items").size()).isPositive();
        }
        assertThat(groups).doesNotHaveDuplicates();
        assertThat(rows(list)).isNotEmpty();
    }

    /** Mở lại màn hình không sinh danh sách thứ hai: đồng bộ là phép lặp lại được. */
    @Test
    void openingTheListTwiceReturnsTheSameList() throws Exception {
        Session session = onboardedMom("0912470003");
        Plan plan = savedLunchPlan(session);

        String first = shoppingList(session, plan.id()).at("/list_id").stringValue();
        String second = shoppingList(session, plan.id()).at("/list_id").stringValue();

        assertThat(second).isEqualTo(first);
    }

    // =========================================================================================
    // Giá
    // =========================================================================================

    /**
     * Nguyên liệu chưa có giá hiện "chưa có giá", và tổng mang nhãn chưa đầy đủ.
     *
     * <p>Seed cố ý có cả hai dạng thiếu: muối chưa có dòng giá, còn dầu ăn có giá theo "lít" —
     * một đơn vị chưa quy đổi được sang gram. Cả hai đều <strong>không</strong> được coi là 0 đồng.</p>
     */
    @Test
    void anIngredientWithoutAPriceIsSaidSoAndTheTotalIsLabelledIncomplete() throws Exception {
        Session session = onboardedMom("0912470004");
        Plan plan = savedLunchPlan(session);

        JsonNode list = shoppingList(session, plan.id());
        Map<String, JsonNode> rows = rows(list);

        List<String> withoutPrice = rows.entrySet().stream()
                .filter(entry -> codes(entry.getValue()).contains("SHOPPING_PRICE_UNAVAILABLE"))
                .map(Map.Entry::getKey)
                .toList();

        assertThat(withoutPrice)
                .describedAs("seed có nguyên liệu chưa có giá quy đổi được")
                .isNotEmpty();
        for (String ingredientId : withoutPrice) {
            assertThat(rows.get(ingredientId).has("estimated_cost_vnd"))
                    .describedAs("%s không được mang một con số tiền bịa ra", ingredientId)
                    .isFalse();
        }
        assertThat(list.at("/cost_coverage").stringValue()).isEqualTo("PARTIAL");
        assertThat(codes(list)).contains("SHOPPING_COST_INCOMPLETE");

        // Dòng có giá vẫn được tính, và tổng đúng bằng tổng của chúng.
        BigDecimal fromRows = BigDecimal.ZERO;
        for (JsonNode row : rows.values()) {
            if (row.has("estimated_cost_vnd")) {
                fromRows = fromRows.add(row.at("/estimated_cost_vnd").decimalValue());
            }
        }
        assertThat(list.at("/estimated_cost_vnd").decimalValue()).isEqualByComparingTo(fromRows);
    }

    // =========================================================================================
    // Đã có / đã mua
    // =========================================================================================

    @Test
    void sayingHowMuchIsAtHomeLeavesOnlyTheRestToBuy() throws Exception {
        Session session = onboardedMom("0912470005");
        Plan plan = savedLunchPlan(session);
        JsonNode rice = rows(shoppingList(session, plan.id())).get(RICE);
        BigDecimal needed = rice.at("/quantity").decimalValue();

        JsonNode updated = data(patchRow(session, rice.at("/item_id").stringValue(), """
                {"state":"HAVE","have_quantity":10}
                """).andExpect(status().isOk()).andReturn());

        JsonNode row = rows(updated).get(RICE);
        assertThat(row.at("/state").stringValue()).isEqualTo("HAVE");
        assertThat(row.at("/have_quantity").decimalValue()).isEqualByComparingTo("10");
        assertThat(row.at("/remaining_quantity").decimalValue())
                .isEqualByComparingTo(needed.subtract(new BigDecimal("10")));
    }

    /** Tick "đã mua" mà không gõ số nghĩa là đã mua đủ phần còn cần. */
    @Test
    void tickingPurchasedWithoutANumberMeansTheWholeRemainder() throws Exception {
        Session session = onboardedMom("0912470006");
        Plan plan = savedLunchPlan(session);
        JsonNode rice = rows(shoppingList(session, plan.id())).get(RICE);
        BigDecimal needed = rice.at("/quantity").decimalValue();

        JsonNode updated = data(patchRow(session, rice.at("/item_id").stringValue(), """
                {"state":"PURCHASED"}
                """).andExpect(status().isOk()).andReturn());

        JsonNode row = rows(updated).get(RICE);
        assertThat(row.at("/purchased_quantity").decimalValue()).isEqualByComparingTo(needed);
        assertThat(row.at("/remaining_quantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(codes(row)).doesNotContain("SHOPPING_NEED_MORE");
    }

    /** Bỏ tick thì hai con số đã khai cũng đi theo — không để lại một câu mâu thuẫn trên cùng dòng. */
    @Test
    void untickingClearsTheNumbersThatWentWithIt() throws Exception {
        Session session = onboardedMom("0912470007");
        Plan plan = savedLunchPlan(session);
        String itemId = rows(shoppingList(session, plan.id())).get(RICE).at("/item_id")
                .stringValue();

        patchRow(session, itemId, "{\"state\":\"PURCHASED\"}").andExpect(status().isOk());
        JsonNode updated = data(patchRow(session, itemId, "{\"state\":\"NEEDED\"}")
                .andExpect(status().isOk()).andReturn());

        JsonNode row = rows(updated).get(RICE);
        assertThat(row.at("/state").stringValue()).isEqualTo("NEEDED");
        assertThat(row.has("purchased_quantity")).isFalse();
        assertThat(row.has("have_quantity")).isFalse();
    }

    // =========================================================================================
    // Đơn vị — chỉ hỗ trợ đơn vị đã quy đổi được
    // =========================================================================================

    /**
     * Nguyên liệu nhập theo thìa: <strong>không</strong> tự đổi sang gram.
     *
     * <p>Một con số đoán trong danh sách đi chợ trông hệt như một con số đã biết, nên chỗ duy nhất
     * đúng để dừng lại là ngay tại ô nhập — kèm tên trường để giao diện chỉ đúng chỗ.</p>
     */
    @Test
    void aSpoonIsNotSilentlyTurnedIntoGrams() throws Exception {
        Session session = onboardedMom("0912470008");
        Plan plan = savedLunchPlan(session);
        String itemId = rows(shoppingList(session, plan.id())).get(RICE).at("/item_id")
                .stringValue();

        patchRow(session, itemId, """
                {"state":"HAVE","have_quantity":2,"unit":"thìa"}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.have_quantity").isNotEmpty());

        JsonNode row = rows(shoppingList(session, plan.id())).get(RICE);
        assertThat(row.at("/state").stringValue())
                .describedAs("yêu cầu bị từ chối thì không đổi gì")
                .isEqualTo("NEEDED");
        assertThat(row.has("have_quantity")).isFalse();
    }

    /** Kilôgam thì quy đổi được, nên nhận — danh sách đơn vị đóng chứ không phải khóa cứng gram. */
    @Test
    void aKilogramIsAcceptedBecauseItReallyConvertsToGrams() throws Exception {
        Session session = onboardedMom("0912470009");
        Plan plan = savedLunchPlan(session);
        String itemId = rows(shoppingList(session, plan.id())).get(RICE).at("/item_id")
                .stringValue();

        JsonNode updated = data(patchRow(session, itemId, """
                {"state":"HAVE","have_quantity":0.05,"unit":"kg"}
                """).andExpect(status().isOk()).andReturn());

        assertThat(rows(updated).get(RICE).at("/have_quantity").decimalValue())
                .isEqualByComparingTo("50");
    }

    // =========================================================================================
    // TC22 — đổi món làm tăng lượng đã mua
    // =========================================================================================

    /**
     * TC22 — dòng giữ trạng thái đã mua, phần tăng thêm hiện thành "cần thêm".
     *
     * <p>Không reset cả danh sách: người ta đã đi chợ thật, và xóa dấu đó đi để bắt mua lại từ đầu
     * là nói sai về việc đã xảy ra. Thứ phải nói là phần còn thiếu.</p>
     */
    @Test
    void anEditThatRaisesTheAmountKeepsThePurchaseAndAsksForTheDifference() throws Exception {
        Session session = onboardedMom("0912470010");
        Plan plan = savedLunchPlan(session);
        Item staple = plan.item("STAPLE");

        // Hạ khẩu phần món cơm xuống mức nhỏ nhất rồi mới đi chợ: nhờ vậy bài kiểm không phụ thuộc
        // vào khẩu phần mà bộ sinh tình cờ chọn, và bước tăng sau đó chắc chắn là một bước tăng.
        setServings(session, plan, staple.id(), plan.version(), "0.5");

        JsonNode rice = rows(shoppingList(session, plan.id())).get(RICE);
        BigDecimal boughtAmount = rice.at("/quantity").decimalValue();
        patchRow(session, rice.at("/item_id").stringValue(), "{\"state\":\"PURCHASED\"}")
                .andExpect(status().isOk());

        // Nhánh 6: tăng khẩu phần món cơm — cùng món, nhiều gạo hơn.
        setServings(session, plan, staple.id(), plan.version() + 1, "2");

        JsonNode list = shoppingList(session, plan.id());
        JsonNode updated = rows(list).get(RICE);

        assertThat(list.at("/shopping_list_version").asInt())
                .describedAs("danh sách đi theo plan_version mới")
                .isEqualTo(plan.version() + 2);
        assertThat(updated.at("/state").stringValue())
                .describedAs("không reset dấu đã mua")
                .isEqualTo("PURCHASED");
        assertThat(updated.at("/purchased_quantity").decimalValue())
                .isEqualByComparingTo(boughtAmount);
        assertThat(updated.at("/quantity").decimalValue())
                .describedAs("lượng cần tăng theo khẩu phần mới")
                .isGreaterThan(boughtAmount);
        assertThat(updated.at("/remaining_quantity").decimalValue()).isPositive();
        assertThat(codes(updated)).contains("SHOPPING_NEED_MORE");
    }

    /** Đổi hẳn món thì nguyên liệu riêng của món cũ rời danh sách, nguyên liệu của món mới vào. */
    @Test
    void swappingADishRewritesTheLinesItBroughtWithIt() throws Exception {
        Session session = onboardedMom("0912470011");
        Plan plan = savedLunchPlan(session);
        Item vegetable = plan.item("VEGETABLE");

        Map<String, JsonNode> before = rows(shoppingList(session, plan.id()));
        String replacement = firstAlternative(session, plan, vegetable.id());

        mockMvc.perform(patch(PLANS + "/{id}/items/{itemId}", plan.id(), vegetable.id())
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expected_plan_version":%d,"expected_profile_version":%d,
                                 "recipe_id":"%s"}
                                """.formatted(plan.version(), plan.profileVersion(), replacement)))
                .andExpect(status().isOk());

        Map<String, JsonNode> after = rows(shoppingList(session, plan.id()));
        assertThat(after.keySet())
                .describedAs("đổi món thì tập nguyên liệu phải đổi theo")
                .isNotEqualTo(before.keySet());
        for (JsonNode row : after.values()) {
            for (JsonNode usage : row.at("/used_by")) {
                assertThat(usage.at("/recipe_id").stringValue())
                        .describedAs("không còn lần dùng nào của món đã bị thay")
                        .isNotEqualTo(vegetable.recipeId());
            }
        }
    }

    // =========================================================================================
    // Nhắc đọc nhãn ở mức nguyên liệu
    // =========================================================================================

    /**
     * Gia vị hỗn hợp được nhắc đọc nhãn thành phần; thịt tươi thì không.
     *
     * <p>Bài học từ Mealime: lọc công thức theo dị ứng <strong>không thay thế</strong> việc đọc
     * nhãn khi mua đồ đóng gói. Nước mắm của seed có nguyên liệu con là cá cơm, và nhãn của hãng
     * này khác hãng kia — nên chỗ duy nhất kiểm được là cái nhãn trên tay người mua. Ức gà thì
     * không có gì để đọc, và nhắc tràn lan sẽ làm người ta thôi đọc lời nhắc.</p>
     */
    @Test
    void aMixedSeasoningCarriesAReadTheLabelReminderAndPlainMeatDoesNot() throws Exception {
        Session session = onboardedMom("0912470022");
        Plan plan = savedLunchPlan(session);

        // Chọn đúng món có nước mắm để bài kiểm không phụ thuộc vào món bộ sinh tình cờ chọn.
        mockMvc.perform(patch(PLANS + "/{id}/items/{itemId}", plan.id(),
                        plan.item("PROTEIN").id())
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expected_plan_version":%d,"expected_profile_version":%d,
                                 "recipe_id":"rec.ga-kho-gung"}
                                """.formatted(plan.version(), plan.profileVersion())))
                .andExpect(status().isOk());

        Map<String, JsonNode> rows = rows(shoppingList(session, plan.id()));

        assertThat(codes(rows.get("ing.nuoc-mam")))
                .describedAs("nước mắm có nguyên liệu con nên phải nhắc đọc nhãn")
                .contains("SHOPPING_READ_PRODUCT_LABEL");
        assertThat(codes(rows.get("ing.thit-ga-uc")))
                .describedAs("ức gà không có nhãn thành phần để đọc")
                .doesNotContain("SHOPPING_READ_PRODUCT_LABEL");
    }

    // =========================================================================================
    // Bỏ chọn món
    // =========================================================================================

    @Test
    void droppingADishRemovesItsIngredientsFromThisViewOnly() throws Exception {
        Session session = onboardedMom("0912470012");
        Plan plan = savedLunchPlan(session);
        Item staple = plan.item("STAPLE");

        JsonNode filtered = shoppingList(session, plan.id(), staple.id());
        assertThat(rows(filtered))
                .describedAs("gạo chỉ đến từ món cơm, nên bỏ chọn món đó là bỏ dòng gạo")
                .doesNotContainKey(RICE);

        assertThat(rows(shoppingList(session, plan.id())))
                .describedAs("bỏ chọn là lựa chọn của lần xem, không xóa dòng nào")
                .containsKey(RICE);
    }

    // =========================================================================================
    // Dòng tự nhập
    // =========================================================================================

    /** Dòng tự nhập không ảnh hưởng tổng dinh dưỡng của thực đơn. */
    @Test
    void aCustomLineNeverTouchesTheNutritionTotals() throws Exception {
        Session session = onboardedMom("0912470013");
        Plan plan = savedLunchPlan(session);
        BigDecimal energyBefore = energy(planBody(session, plan.id()));
        BigDecimal costBefore = shoppingList(session, plan.id()).at("/estimated_cost_vnd")
                .decimalValue();

        JsonNode list = data(mockMvc.perform(post(PLANS + "/{id}/shopping-list/items", plan.id())
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Giấy ăn","quantity":2,"unit":"gói"}
                                """))
                .andExpect(status().isCreated())
                .andReturn());

        JsonNode custom = customRows(list).get(0);
        assertThat(custom.at("/custom_text").stringValue()).isEqualTo("Giấy ăn");
        assertThat(custom.has("ingredient_id")).isFalse();
        assertThat(custom.has("estimated_cost_vnd")).isFalse();
        assertThat(codes(custom)).contains("SHOPPING_CUSTOM_LINE");
        assertThat(custom.at("/unit").stringValue())
                .describedAs("đơn vị tự do của dòng tự nhập giữ nguyên, không bị quy đổi")
                .isEqualTo("gói");

        assertThat(energy(planBody(session, plan.id())))
                .describedAs("tổng dinh dưỡng của thực đơn không đổi")
                .isEqualByComparingTo(energyBefore);
        assertThat(list.at("/estimated_cost_vnd").decimalValue())
                .describedAs("dòng ngoài thực đơn không có giá nên không vào tổng")
                .isEqualByComparingTo(costBefore);
    }

    /** Lượt đồng bộ sau khi đổi món không được cuốn mất dòng tự nhập. */
    @Test
    void aCustomLineSurvivesAPlanEdit() throws Exception {
        Session session = onboardedMom("0912470014");
        Plan plan = savedLunchPlan(session);
        addCustomLine(session, plan.id(), "Sữa tắm");

        mockMvc.perform(patch(PLANS + "/{id}/items/{itemId}", plan.id(), plan.item("STAPLE").id())
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expected_plan_version":%d,"expected_profile_version":%d,
                                 "servings":2}
                                """.formatted(plan.version(), plan.profileVersion())))
                .andExpect(status().isOk());

        assertThat(customRows(shoppingList(session, plan.id())))
                .describedAs("dòng tự nhập không đến từ kế hoạch nên không lượt đồng bộ nào xóa nó")
                .hasSize(1);
    }

    @Test
    void onlyACustomLineCanBeDeleted() throws Exception {
        Session session = onboardedMom("0912470015");
        Plan plan = savedLunchPlan(session);
        JsonNode added = addCustomLine(session, plan.id(), "Giấy ăn");
        String customId = customRows(added).get(0).at("/item_id").stringValue();
        String riceId = rows(added).get(RICE).at("/item_id").stringValue();

        mockMvc.perform(delete(ITEMS + "/{itemId}", riceId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.fields.item_id").isNotEmpty());

        JsonNode after = data(mockMvc.perform(delete(ITEMS + "/{itemId}", customId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(customRows(after)).isEmpty();
        assertThat(rows(after)).containsKey(RICE);
    }

    // =========================================================================================
    // Đường bị từ chối
    // =========================================================================================

    @Test
    void aDateRangeOutsideThePlanIsRejected() throws Exception {
        Session session = onboardedMom("0912470016");
        Plan plan = savedLunchPlan(session);

        mockMvc.perform(get(PLANS + "/{id}/shopping-list", plan.id())
                        .header("Authorization", "Bearer " + session.accessToken())
                        .param("start_date", "2026-10-01")
                        .param("end_date", "2026-10-02"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.fields.start_date").isNotEmpty());
    }

    @Test
    void aPatchWithNothingToChangeIsRejected() throws Exception {
        Session session = onboardedMom("0912470017");
        Plan plan = savedLunchPlan(session);
        String itemId = rows(shoppingList(session, plan.id())).get(RICE).at("/item_id")
                .stringValue();

        patchRow(session, itemId, "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void freeTextCannotBeWrittenOntoAnIngredientLine() throws Exception {
        Session session = onboardedMom("0912470018");
        Plan plan = savedLunchPlan(session);
        String itemId = rows(shoppingList(session, plan.id())).get(RICE).at("/item_id")
                .stringValue();

        patchRow(session, itemId, "{\"custom_text\":\"mua loại thơm\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.fields.custom_text").isNotEmpty());
    }

    @Test
    void anotherAccountCanNeitherReadNorTickTheList() throws Exception {
        Session owner = onboardedMom("0912470019");
        Plan plan = savedLunchPlan(owner);
        String itemId = rows(shoppingList(owner, plan.id())).get(RICE).at("/item_id").stringValue();

        Session stranger = onboardedMom("0912470020");

        mockMvc.perform(get(PLANS + "/{id}/shopping-list", plan.id())
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound());
        patchRow(stranger, itemId, "{\"state\":\"PURCHASED\"}")
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(ITEMS + "/{itemId}", itemId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theEndpointsRefuseAnonymousCallers() throws Exception {
        Session session = onboardedMom("0912470021");
        Plan plan = savedLunchPlan(session);
        String itemId = rows(shoppingList(session, plan.id())).get(RICE).at("/item_id")
                .stringValue();

        mockMvc.perform(get(PLANS + "/{id}/shopping-list", plan.id()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(ITEMS + "/{itemId}", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"PURCHASED\"}"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================================
    // Fixture
    // =========================================================================================

    private record Plan(String id, int version, int profileVersion, Map<String, Item> items) {

        Item item(String role) {
            Item item = items.get(role);
            if (item == null) {
                throw new AssertionError("Bữa trưa của seed phải có vai trò " + role);
            }
            return item;
        }
    }

    private record Item(String id, String recipeId) {
    }

    private Plan savedLunchPlan(Session session) throws Exception {
        MvcResult preview = mockMvc.perform(post(PLANS + "/preview")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"request_id":"req-shopping-%s","start_date":"2026-10-14",
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
                                item.at("/recipe_id").stringValue()));
            }
        }
        return new Plan(plan.at("/plan_id").stringValue(), plan.at("/plan_version").asInt(),
                plan.at("/profile_version").asInt(), items);
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

    private JsonNode shoppingList(Session session, String planId, String... excludedItemIds)
            throws Exception {
        var request = get(PLANS + "/{id}/shopping-list", planId)
                .header("Authorization", "Bearer " + session.accessToken());
        for (String itemId : excludedItemIds) {
            request = request.param("exclude_item_ids", itemId);
        }
        return data(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    /** Đổi khẩu phần của một mục kế hoạch qua endpoint sửa thực đơn của nhánh 6. */
    private void setServings(Session session, Plan plan, String itemId, int planVersion,
                             String servings) throws Exception {
        mockMvc.perform(patch(PLANS + "/{id}/items/{itemId}", plan.id(), itemId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expected_plan_version":%d,"expected_profile_version":%d,
                                 "servings":%s}
                                """.formatted(planVersion, plan.profileVersion(), servings)))
                .andExpect(status().isOk());
    }

    private ResultActions patchRow(Session session, String itemId, String body) throws Exception {
        return mockMvc.perform(patch(ITEMS + "/{itemId}", itemId)
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode addCustomLine(Session session, String planId, String text) throws Exception {
        return data(mockMvc.perform(post(PLANS + "/{id}/shopping-list/items", planId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private JsonNode planBody(Session session, String planId) throws Exception {
        return data(mockMvc.perform(get(PLANS + "/{id}", planId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private String firstAlternative(Session session, Plan plan, String itemId) throws Exception {
        JsonNode alternatives = data(mockMvc.perform(
                        get(PLANS + "/{id}/items/{itemId}/alternatives", plan.id(), itemId)
                                .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode first = alternatives.at("/alternatives/0");
        if (first.isMissingNode()) {
            throw new AssertionError("Seed phải có ít nhất một món thay thế cùng vai trò");
        }
        return first.at("/recipe_id").stringValue();
    }

    // -----------------------------------------------------------------------------------------

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    /** Các dòng nguyên liệu của danh sách, tra theo mã nguyên liệu. */
    private Map<String, JsonNode> rows(JsonNode list) {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        for (JsonNode group : list.at("/groups")) {
            for (JsonNode item : group.at("/items")) {
                if (item.has("ingredient_id")) {
                    rows.put(item.at("/ingredient_id").stringValue(), item);
                }
            }
        }
        return rows;
    }

    /** Các dòng tự nhập — nhóm không có {@code food_group}. */
    private List<JsonNode> customRows(JsonNode list) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode group : list.at("/groups")) {
            for (JsonNode item : group.at("/items")) {
                if (!item.has("ingredient_id")) {
                    rows.add(item);
                }
            }
        }
        return rows;
    }

    private List<String> codes(JsonNode node) {
        List<String> codes = new ArrayList<>();
        for (JsonNode note : node.at("/notes")) {
            codes.add(note.at("/code").stringValue());
        }
        return codes;
    }

    private BigDecimal energy(JsonNode plan) {
        return plan.at("/days/0/nutrition/ENERGY/known_value").decimalValue();
    }
}
