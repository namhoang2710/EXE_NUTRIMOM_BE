package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.nutrition.domain.MealPlanPreviewEntity;
import vn.nutrimom.nutrition.repository.MealPlanPreviewRepository;
import vn.nutrimom.nutrition.repository.MealPlanRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Lập thực đơn chạy thật trên <strong>seed thật</strong>, từ đăng ký tới khi lưu được kế hoạch.
 *
 * <p>Dùng seed thật chứ không fixture dựng tay vì đây là nơi chứng minh rằng danh mục đang nằm
 * trong repo thực sự ghép được thành một ngày ăn, và rằng những thứ nó <em>chưa</em> có — vi chất
 * còn trống, giá còn thiếu — đi ra ngoài API đúng như sự thật chứ không bị lấp bằng số 0.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.nutrition.seed-enabled=true")
class MealPlanIntegrationTest extends ApiIntegrationTestSupport {

    private static final String PREVIEW_PATH = "/api/v1/meal-plans/preview";
    private static final String PLAN_PATH = "/api/v1/meal-plans";

    /** Chia gram cho servingsYield cần đủ chữ số: 100/3 không có biểu diễn hữu hạn. */
    private static final MathContext MATH = new MathContext(16);

    @Autowired MealPlanPreviewRepository previews;
    @Autowired MealPlanRepository plans;

    // =========================================================================================
    // Tạo bản xem trước
    // =========================================================================================

    @Test
    void aMomGetsAFullDayWithEveryMealFilledAndADayTotal() throws Exception {
        Session session = onboardedMom("0912380001");

        long startedAt = System.nanoTime();
        MvcResult result = preview(session, """
                {"request_id":"req-full-day","start_date":"2026-10-14","day_count":1,
                 "meal_types":["BREAKFAST","LUNCH","DINNER","SNACK"],"snack_count":1,"seed":17}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preview_id").isNotEmpty())
                .andExpect(jsonPath("$.data.expires_at").isNotEmpty())
                .andExpect(jsonPath("$.data.days.length()").value(1))
                .andExpect(jsonPath("$.data.days[0].local_date").value("2026-10-14"))
                .andExpect(jsonPath("$.data.days[0].slots.length()").value(4))
                .andReturn();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        // Mục tiêu một ngày ≤ 2 giây, đo trên cả đường HTTP chứ không riêng bộ sinh.
        assertThat(elapsedMillis)
                .describedAs("thời gian tạo bản xem trước một ngày đầy đủ (ms)")
                .isLessThan(2000L);

        JsonNode day = data(result).at("/days/0");
        for (JsonNode slot : day.at("/slots")) {
            assertThat(slot.at("/filled").asBoolean())
                    .describedAs("bữa %s phải được lấp", slot.at("/meal_type"))
                    .isTrue();
            assertThat(slot.at("/items").size()).isPositive();
            assertThat(slot.at("/template_code").stringValue()).contains(":");
            for (JsonNode item : slot.at("/items")) {
                assertThat(item.at("/servings").decimalValue().signum())
                        .describedAs("số phần của %s", item.at("/recipe_id"))
                        .isPositive();
            }
        }
        // Mỗi bữa theo đúng MỘT cấu trúc, không trộn vai trò của hai template (BR06).
        for (JsonNode slot : day.at("/slots")) {
            assertThat(slot.at("/items").size()).isEqualTo(slot.at("/required_roles").size());
        }
        assertThat(day.at("/nutrition/ENERGY/known_value").isNull()).isFalse();
        assertThat(day.at("/nutrition/ENERGY/unit").stringValue()).isEqualTo("kcal");
    }

    /**
     * Seed còn thiếu nhiều số liệu vi chất: chất chưa có dữ liệu vẫn <strong>xuất hiện</strong> với
     * {@code known_value: null}, và cả ngày mang nhãn PARTIAL thay vì được tô đầy (bất biến 1).
     */
    @Test
    void missingMicronutrientDataIsReportedAsNullAndPartialRatherThanZero() throws Exception {
        Session session = onboardedMom("0912380002");

        MvcResult result = preview(session, """
                {"request_id":"req-partial","start_date":"2026-10-14",
                 "meal_types":["LUNCH","DINNER"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nutrition_coverage").value("PARTIAL"))
                .andExpect(jsonPath("$.data.notes[?(@.code == 'NUTRITION_DATA_INCOMPLETE')]")
                        .exists())
                .andReturn();

        JsonNode nutrition = data(result).at("/days/0/nutrition");
        assertThat(nutrition.has("IODINE")).isTrue();
        for (String code : List.of("FOLATE", "IRON", "CALCIUM", "CHOLINE", "IODINE",
                "VITAMIN_D")) {
            JsonNode nutrient = nutrition.at("/" + code);
            assertThat(nutrient.has("known_value"))
                    .describedAs("chất %s phải có trường known_value dù chưa có dữ liệu", code)
                    .isTrue();
            assertThat(nutrient.at("/coverage").stringValue()).isIn("COMPLETE", "PARTIAL");
        }
        assertThat(data(result).at("/status").stringValue()).isEqualTo("READY_WITH_NOTES");
    }

    /** TC23: chỉ lập trưa và tối thì nói rõ đây là kế hoạch một phần ngày. */
    @Test
    void planningOnlyLunchAndDinnerIsLabelledAsAPartialDay() throws Exception {
        Session session = onboardedMom("0912380003");

        preview(session, """
                {"request_id":"req-partial-day","start_date":"2026-10-14",
                 "meal_types":["LUNCH","DINNER"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[?(@.code == 'PARTIAL_DAY_PLAN')]").exists())
                .andExpect(jsonPath("$.data.days[0].slots.length()").value(2));
    }

    @Test
    void threeSnacksEachGetTheirOwnSlot() throws Exception {
        Session session = onboardedMom("0912380004");

        MvcResult result = preview(session, """
                {"request_id":"req-snacks","start_date":"2026-10-14",
                 "meal_types":["SNACK"],"snack_count":3}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[0].slots.length()").value(3))
                .andReturn();

        List<Integer> sequences = new ArrayList<>();
        for (JsonNode slot : data(result).at("/days/0/slots")) {
            sequences.add(slot.at("/sequence").asInt());
        }
        assertThat(sequences).containsExactly(1, 2, 3);
    }

    // =========================================================================================
    // TC18 — không còn ứng viên cho một vai trò
    // =========================================================================================

    /**
     * TC18: nói rõ <strong>vai trò nào, bữa nào, bộ lọc nào</strong>.
     *
     * <p>Hồ sơ thuần chay gặp bữa sáng của seed hiện tại: món hoàn chỉnh duy nhất bị chế độ ăn
     * chặn, còn cấu trúc "tinh bột + đạm" thì danh mục chưa có món tinh bột nào cho bữa sáng. Cả hai
     * lý do phải đi ra ngoài API — chỉ nói "không tìm được thực đơn" thì người dùng không biết nên
     * nới cái gì, mà nới đúng chỗ là việc duy nhất họ làm được.</p>
     */
    @Test
    void aVeganBreakfastSaysWhichRoleRanOutAndWhichFilterCausedIt() throws Exception {
        Session session = onboardedMom("0912380005");
        saveProfile(session, """
                {"allergy_status":"KNOWN_NONE","diet_pattern":"VEGAN"}
                """);

        MvcResult result = preview(session, """
                {"request_id":"req-vegan-breakfast","start_date":"2026-10-14",
                 "meal_types":["BREAKFAST"]}
                """)
                // Hết món vẫn là HTTP 200: đây là kết quả nghiệp vụ, không phải lỗi hệ thống.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.data.notes[?(@.code == 'NO_MATCH')]").exists())
                .andExpect(jsonPath("$.data.notes[?(@.code == 'ROLE_NO_CANDIDATE')]").exists())
                .andExpect(jsonPath("$.data.days[0].slots[0].filled").value(false))
                .andReturn();

        String messages = noteMessages(result);
        assertThat(messages).contains("vai trò");
        assertThat(messages).contains("bữa BREAKFAST");
        assertThat(messages)
                .describedAs("phải chỉ ra bộ lọc nào đã chặn")
                .contains("DIET_PATTERN_CONFLICT");
        // Bản NO_MATCH vẫn lưu được để gửi lại cùng request_id trả về đúng câu trả lời cũ.
        assertThat(data(result).at("/preview_id").stringValue()).isNotBlank();
    }

    /** Không bao giờ nới dị ứng để có kết quả: khóa đúng món đó cũng không đưa nó vào được. */
    @Test
    void lockingADishWithADeclaredAllergenStillKeepsItOut() throws Exception {
        Session session = onboardedMom("0912380006");
        saveProfile(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.giap-xac"]}
                """);

        MvcResult result = preview(session, """
                {"request_id":"req-locked-allergen","start_date":"2026-10-14",
                 "meal_types":["LUNCH"],
                 "locked_items":[{"meal_type":"LUNCH","sequence":1,
                                  "recipe_id":"rec.canh-bi-do-tom","servings":1}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_MATCH"))
                .andReturn();

        assertThat(noteMessages(result)).contains("dị ứng");
    }

    // =========================================================================================
    // Idempotency
    // =========================================================================================

    /** Gửi hai lần cùng {@code request_id}: đúng một bản xem trước, đúng một câu trả lời. */
    @Test
    void theSameRequestIdTwiceReturnsTheSamePreviewAndCreatesNoSecondRow() throws Exception {
        Session session = onboardedMom("0912380007");
        String body = """
                {"request_id":"req-double-click","start_date":"2026-10-14",
                 "meal_types":["LUNCH","DINNER"],"seed":17}
                """;

        MvcResult first = preview(session, body).andExpect(status().isOk()).andReturn();
        MvcResult again = preview(session, body).andExpect(status().isOk()).andReturn();

        String previewId = data(first).at("/preview_id").stringValue();
        assertThat(data(again).at("/preview_id").stringValue()).isEqualTo(previewId);
        assertThat(recipeIds(again)).isEqualTo(recipeIds(first));
        assertThat(previews.findAll().stream()
                .filter(row -> row.getUserId().equals(session.userId()))
                .count()).isEqualTo(1);
    }

    /**
     * Sửa yêu cầu rồi gửi lại bằng {@code request_id} cũ là xung đột, không phải gửi lại.
     *
     * <p>Đây là tình huống bắt được lúc thử tay: lần đầu không khóa món, lần sau thêm
     * {@code locked_items} nhưng quên đổi {@code request_id}. Trả bản cũ ở đây làm client tin rằng
     * món đã được khóa.</p>
     */
    @Test
    void theSameRequestIdWithADifferentBodyIsRejectedAndWritesNothing() throws Exception {
        Session session = onboardedMom("0912380036");
        MvcResult first = preview(session, """
                {"request_id":"req-changed-mind","start_date":"2026-10-14",
                 "meal_types":["LUNCH","DINNER"],"seed":17}
                """).andExpect(status().isOk()).andReturn();
        String lunchRecipe = data(first).at("/days/0/slots/0/items/0/recipe_id").stringValue();

        preview(session, """
                {"request_id":"req-changed-mind","start_date":"2026-10-14",
                 "meal_types":["LUNCH","DINNER"],"seed":17,
                 "locked_items":[{"meal_type":"LUNCH","sequence":1,"recipe_id":"%s",
                                  "servings":2}]}
                """.formatted(lunchRecipe))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(previews.findAll().stream()
                .filter(row -> row.getUserId().equals(session.userId()))
                .count())
                .describedAs("yêu cầu bị từ chối thì không sinh bản ghi nào")
                .isEqualTo(1);
    }

    /** Cách viết khác nhau, nghĩa như nhau: vẫn là một lần gửi lại, không phải xung đột. */
    @Test
    void theSameRequestWrittenDifferentlyIsStillTheSameRequest() throws Exception {
        Session session = onboardedMom("0912380037");
        MvcResult first = preview(session, """
                {"request_id":"req-same-meaning","start_date":"2026-10-14",
                 "meal_types":["LUNCH","DINNER"],"seed":17}
                """).andExpect(status().isOk()).andReturn();

        // Thứ tự bữa đảo lại, và day_count/snack_count ghi rõ đúng giá trị mặc định.
        MvcResult again = preview(session, """
                {"request_id":"req-same-meaning","start_date":"2026-10-14","day_count":1,
                 "meal_types":["DINNER","LUNCH"],"snack_count":1,"seed":17}
                """).andExpect(status().isOk()).andReturn();

        assertThat(data(again).at("/preview_id").stringValue())
                .isEqualTo(data(first).at("/preview_id").stringValue());
    }

    /** {@code request_id} mới thì yêu cầu mới được chạy bình thường. */
    @Test
    void aNewRequestIdCreatesASecondPreview() throws Exception {
        Session session = onboardedMom("0912380038");
        MvcResult first = preview(session, """
                {"request_id":"req-first-try","start_date":"2026-10-14",
                 "meal_types":["LUNCH"],"seed":17}
                """).andExpect(status().isOk()).andReturn();
        MvcResult second = preview(session, """
                {"request_id":"req-second-try","start_date":"2026-10-15",
                 "meal_types":["LUNCH"],"seed":17}
                """).andExpect(status().isOk()).andReturn();

        assertThat(data(second).at("/preview_id").stringValue())
                .isNotEqualTo(data(first).at("/preview_id").stringValue());
        assertThat(data(second).at("/days/0/local_date").stringValue()).isEqualTo("2026-10-15");
        assertThat(previews.findAll().stream()
                .filter(row -> row.getUserId().equals(session.userId()))
                .count()).isEqualTo(2);
    }

    // =========================================================================================
    // Lưu kế hoạch
    // =========================================================================================

    @Test
    void aPreviewBecomesASavedPlanWithTheSameTotals() throws Exception {
        Session session = onboardedMom("0912380008");
        MvcResult preview = preview(session, """
                {"request_id":"req-save","start_date":"2026-10-14",
                 "meal_types":["LUNCH","DINNER"]}
                """).andExpect(status().isOk()).andReturn();

        MvcResult saved = save(session, data(preview).at("/preview_id").stringValue())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lifecycle_state").value("SAVED"))
                .andExpect(jsonPath("$.data.plan_version").value(1))
                .andReturn();

        String planId = data(saved).at("/plan_id").stringValue();
        MvcResult fetched = mockMvc.perform(get(PLAN_PATH + "/{id}", planId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_id").value(planId))
                .andReturn();

        // Bất biến 4: tổng của kế hoạch đã lưu phải bằng đúng tổng của bản xem trước — cùng một bộ
        // tính, dựng lại từ snapshot đã lưu.
        assertThat(energy(fetched)).isEqualByComparingTo(energy(preview));
        assertThat(data(fetched).at("/days/0/slots").size())
                .isEqualTo(data(preview).at("/days/0/slots").size());
        assertThat(recipeIds(fetched)).isEqualTo(recipeIds(preview));
    }

    /** Bấm Lưu hai lần không tạo hai kế hoạch giống hệt nhau. */
    @Test
    void savingTheSamePreviewTwiceReturnsTheSamePlan() throws Exception {
        Session session = onboardedMom("0912380009");
        MvcResult preview = preview(session, """
                {"request_id":"req-save-twice","start_date":"2026-10-14",
                 "meal_types":["LUNCH"]}
                """).andExpect(status().isOk()).andReturn();
        String previewId = data(preview).at("/preview_id").stringValue();

        MvcResult first = save(session, previewId).andExpect(status().isCreated()).andReturn();
        MvcResult again = save(session, previewId).andExpect(status().isCreated()).andReturn();

        assertThat(data(again).at("/plan_id").stringValue())
                .isEqualTo(data(first).at("/plan_id").stringValue());
        assertThat(plans.findAll().stream()
                .filter(plan -> plan.getUserId().equals(session.userId()))
                .count()).isEqualTo(1);
    }

    /** Bản xem trước sống 30 phút; hết hạn thì không lưu được nữa. */
    @Test
    void anExpiredPreviewCannotBeSavedAnyMore() throws Exception {
        Session session = onboardedMom("0912380010");
        MvcResult preview = preview(session, """
                {"request_id":"req-expired","start_date":"2026-10-14",
                 "meal_types":["LUNCH"]}
                """).andExpect(status().isOk()).andReturn();
        String previewId = data(preview).at("/preview_id").stringValue();

        MealPlanPreviewEntity row = previews.findById(previewId).orElseThrow();
        row.setCreatedAt(OffsetDateTime.now().minusHours(2));
        row.setExpiresAt(OffsetDateTime.now().minusMinutes(31));
        previews.saveAndFlush(row);

        save(session, previewId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    /** Hồ sơ đổi sau khi xem trước thì không lưu — kế hoạch kia đã dựng trên dữ liệu cũ. */
    @Test
    void changingTheProfileAfterThePreviewBlocksTheSave() throws Exception {
        Session session = onboardedMom("0912380011");
        MvcResult preview = preview(session, """
                {"request_id":"req-stale-profile","start_date":"2026-10-14",
                 "meal_types":["LUNCH"]}
                """).andExpect(status().isOk()).andReturn();

        saveProfile(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.giap-xac"]}
                """);

        save(session, data(preview).at("/preview_id").stringValue())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    /** Hồ sơ đã đổi ngay lúc xin bản xem trước cũng bị chặn, trước cả khi tìm kiếm. */
    @Test
    void aStaleProfileVersionIsRejectedBeforeAnySearchRuns() throws Exception {
        Session session = onboardedMom("0912380012");

        preview(session, """
                {"request_id":"req-stale-version","start_date":"2026-10-14",
                 "profile_version":99,"meal_types":["LUNCH"]}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void aNoMatchPreviewHasNothingToSave() throws Exception {
        Session session = onboardedMom("0912380013");
        saveProfile(session, """
                {"allergy_status":"KNOWN_NONE","diet_pattern":"VEGAN"}
                """);
        MvcResult preview = preview(session, """
                {"request_id":"req-no-match-save","start_date":"2026-10-14",
                 "meal_types":["BREAKFAST"]}
                """).andExpect(status().isOk()).andReturn();

        save(session, data(preview).at("/preview_id").stringValue())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // =========================================================================================
    // TC20 — chủ sở hữu
    // =========================================================================================

    /** TC20: tài khoản khác không đọc và không lưu được thực đơn của người này. */
    @Test
    void anotherAccountCanNeitherReadThePlanNorSaveThePreview() throws Exception {
        Session owner = onboardedMom("0912380014");
        Session stranger = onboardedMom("0912380015");

        MvcResult preview = preview(owner, """
                {"request_id":"req-owner","start_date":"2026-10-14","meal_types":["LUNCH"]}
                """).andExpect(status().isOk()).andReturn();
        String previewId = data(preview).at("/preview_id").stringValue();
        MvcResult saved = save(owner, previewId).andExpect(status().isCreated()).andReturn();
        String planId = data(saved).at("/plan_id").stringValue();

        mockMvc.perform(get(PLAN_PATH + "/{id}", planId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        save(stranger, previewId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void theEndpointsRefuseAnonymousCallers() throws Exception {
        mockMvc.perform(post(PREVIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"request_id":"req-anon","start_date":"2026-10-14",
                                 "meal_types":["LUNCH"]}
                                """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PLAN_PATH + "/{id}", "any-plan"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================================
    // Nhánh 9 — ba và bảy ngày
    // =========================================================================================

    /**
     * Bảy ngày, đủ bốn bữa, trên seed thật — <strong>trong 5 giây</strong>.
     *
     * <p>Đo trên cả đường HTTP chứ không riêng bộ sinh, vì 5 giây là thứ người dùng chờ chứ không
     * phải thứ bộ sinh tiêu. Số đo thực tế của máy kiểm thử ghi ở
     * {@code docs/NUTRITION_API.md}.</p>
     */
    @Test
    void aSevenDayPlanIsGeneratedInsideTheFiveSecondBudget() throws Exception {
        Session session = onboardedMom("0912380031");

        long startedAt = System.nanoTime();
        MvcResult result = preview(session, """
                {"request_id":"req-seven-days","start_date":"2026-10-14","day_count":7,
                 "meal_types":["BREAKFAST","LUNCH","DINNER","SNACK"],"snack_count":1,"seed":17}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY_WITH_NOTES"))
                .andExpect(jsonPath("$.data.days.length()").value(7))
                .andReturn();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis)
                .describedAs("thời gian tạo bản xem trước bảy ngày đầy đủ (ms), mục tiêu 5000")
                .isLessThan(5000L);

        List<String> dates = new ArrayList<>();
        for (JsonNode day : data(result).at("/days")) {
            dates.add(day.at("/local_date").stringValue());
            assertThat(day.at("/slots").size())
                    .describedAs("ngày %s phải có đủ bốn bữa", day.at("/local_date"))
                    .isEqualTo(4);
            for (JsonNode slot : day.at("/slots")) {
                assertThat(slot.at("/filled").asBoolean()).isTrue();
                assertThat(slot.at("/items").size()).isPositive();
            }
            // Mỗi ngày có tổng của riêng nó, không phải một tổng tuần chia bảy.
            assertThat(day.at("/nutrition/ENERGY/known_value").isNull()).isFalse();
        }
        assertThat(dates).containsExactly("2026-10-14", "2026-10-15", "2026-10-16", "2026-10-17",
                "2026-10-18", "2026-10-19", "2026-10-20");
    }

    /**
     * Quy tắc cá theo tuần kiểm <strong>tổng lượng của cả kế hoạch</strong>, theo nhóm dữ liệu.
     *
     * <p>Seed khai nhóm "cá và hải sản nhóm nên ăn" ở mức 340 g một tuần. Bảy ngày trưa và tối là
     * mười bốn vị trí đạm, thừa chỗ để cá hồi xuất hiện quá mức nếu không ai cộng tổng — nên con
     * số tính lại từ chính các mục trả về là bằng chứng duy nhất đáng tin ở đây.</p>
     */
    @Test
    void theWeeklyFishRuleIsCheckedOnTheWholeSevenDayPlan() throws Exception {
        Session session = onboardedMom("0912380032");

        MvcResult result = preview(session, """
                {"request_id":"req-week-fish","start_date":"2026-10-14","day_count":7,
                 "meal_types":["LUNCH","DINNER"],"seed":5}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[?(@.code == 'WEEKLY_FOOD_LIMIT_CHECKED')]")
                        .exists())
                .andReturn();

        // Gram của nhóm, cộng lại từ chính các mục đã trả về. Một khẩu phần mang lượng nguyên
        // liệu chia cho servingsYield của món, đúng như seed khai: cá hồi áp chảo 240 g cá cho 2
        // phần, canh bí đỏ nấu tôm 100 g tôm cho 3 phần.
        Map<String, BigDecimal> gramPerServing = Map.of(
                "rec.ca-hoi-ap-chao", new BigDecimal("240").divide(new BigDecimal("2"), MATH),
                "rec.canh-bi-do-tom", new BigDecimal("100").divide(new BigDecimal("3"), MATH));

        BigDecimal seafood = BigDecimal.ZERO;
        for (JsonNode day : data(result).at("/days")) {
            for (JsonNode slot : day.at("/slots")) {
                for (JsonNode item : slot.at("/items")) {
                    BigDecimal perServing = gramPerServing.getOrDefault(
                            item.at("/recipe_id").stringValue(), BigDecimal.ZERO);
                    seafood = seafood.add(perServing.multiply(item.at("/servings").decimalValue()));
                }
            }
        }

        assertThat(seafood)
                .describedAs("tổng cá và hải sản của cả bảy ngày (g)")
                .isLessThanOrEqualTo(new BigDecimal("340"));
        assertThat(noteMessages(result)).contains("/340 g");
    }

    /**
     * Danh mục chỉ có một món tinh bột cho bữa trưa, nên bảy ngày buộc phải lặp — và kế hoạch
     * <strong>nói ra</strong> chỗ nó đã phải lặp thay vì để người dùng tự phát hiện.
     */
    @Test
    void aDishThatHasToRepeatAcrossDaysIsReported() throws Exception {
        Session session = onboardedMom("0912380033");

        preview(session, """
                {"request_id":"req-week-repeat","start_date":"2026-10-14","day_count":7,
                 "meal_types":["LUNCH"],"seed":3}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[?(@.code == 'REPEATED_RECIPE_ACROSS_DAYS')]")
                        .exists());
    }

    /** TC23 với bảy ngày: <strong>từng ngày</strong> mang ghi chú kế hoạch một phần ngày. */
    @Test
    void everyDayOfAPartialSevenDayPlanIsLabelledAsAPartialDay() throws Exception {
        Session session = onboardedMom("0912380034");

        MvcResult result = preview(session, """
                {"request_id":"req-week-partial","start_date":"2026-10-14","day_count":7,
                 "meal_types":["LUNCH","DINNER"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[?(@.code == 'PARTIAL_DAY_PLAN')]").exists())
                .andReturn();

        for (JsonNode day : data(result).at("/days")) {
            List<String> codes = new ArrayList<>();
            day.at("/notes").forEach(note -> codes.add(note.at("/code").stringValue()));
            assertThat(codes)
                    .describedAs("ghi chú của ngày %s", day.at("/local_date"))
                    .contains("PARTIAL_DAY_PLAN");
        }
    }

    /** Ba ngày lưu được thành một kế hoạch, và đọc lại ra đúng ba ngày tách bạch. */
    @Test
    void aThreeDayPlanIsSavedAndReadBackDayByDay() throws Exception {
        Session session = onboardedMom("0912380035");

        MvcResult preview = preview(session, """
                {"request_id":"req-three-days","start_date":"2026-10-14","day_count":3,
                 "meal_types":["LUNCH","DINNER"],"seed":9}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days.length()").value(3))
                .andReturn();

        MvcResult saved = save(session, data(preview).at("/preview_id").stringValue())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.start_date").value("2026-10-14"))
                .andExpect(jsonPath("$.data.end_date").value("2026-10-16"))
                .andExpect(jsonPath("$.data.days.length()").value(3))
                .andReturn();
        String planId = data(saved).at("/plan_id").stringValue();

        MvcResult read = mockMvc.perform(get(PLAN_PATH + "/{id}", planId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days.length()").value(3))
                .andReturn();

        List<String> dates = new ArrayList<>();
        for (JsonNode day : data(read).at("/days")) {
            dates.add(day.at("/local_date").stringValue());
            assertThat(day.at("/slots").size()).isEqualTo(2);
        }
        assertThat(dates).containsExactly("2026-10-14", "2026-10-15", "2026-10-16");
    }

    // =========================================================================================
    // TC24 và các ràng buộc đầu vào
    // =========================================================================================

    /** TC24: số phần bằng 0 báo lỗi <strong>tại trường</strong> và không lưu gì. */
    @Test
    void zeroServingsOnALockedItemIsRejectedAtTheField() throws Exception {
        Session session = onboardedMom("0912380016");

        MvcResult result = preview(session, """
                {"request_id":"req-zero-servings","start_date":"2026-10-14",
                 "meal_types":["LUNCH"],
                 "locked_items":[{"meal_type":"LUNCH","sequence":1,
                                  "recipe_id":"rec.com-trang","servings":0}]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("servings");
        assertThat(previews.findAll().stream()
                .filter(row -> row.getUserId().equals(session.userId()))
                .count()).isZero();
    }

    @Test
    void aNegativeServingCountIsRejectedToo() throws Exception {
        Session session = onboardedMom("0912380017");

        preview(session, """
                {"request_id":"req-negative-servings","start_date":"2026-10-14",
                 "meal_types":["LUNCH"],
                 "locked_items":[{"meal_type":"LUNCH","sequence":1,
                                  "recipe_id":"rec.com-trang","servings":-1}]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /** {@code day_count} là một lựa chọn sản phẩm — một ngày, ba ngày, một tuần — không phải một
     * khoảng: 5 nằm giữa 3 và 7 nhưng vẫn bị từ chối, và từ chối tại đúng trường. */
    @Test
    void aDayCountOfFiveIsRejectedAtTheField() throws Exception {
        Session session = onboardedMom("0912380018");

        preview(session, """
                {"request_id":"req-five-days","start_date":"2026-10-14","day_count":5,
                 "meal_types":["LUNCH"]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.day_count").isNotEmpty());
    }

    /** Ngân sách và thời gian đi vào mẫu số của một phép chia, nên 0 bị chặn ngay ở đầu vào. */
    @Test
    void aZeroBudgetOrZeroTimeLimitIsRejected() throws Exception {
        Session session = onboardedMom("0912380019");

        MvcResult result = preview(session, """
                {"request_id":"req-zero-budget","start_date":"2026-10-14","meal_types":["LUNCH"],
                 "constraints":{"daily_budget_vnd":0,"max_minutes_per_meal":0}}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andReturn();

        // Lỗi gắn vào đúng tên trường, dạng 'constraints.daily_budget_vnd'.
        assertThat(result.getResponse().getContentAsString())
                .contains("daily_budget_vnd")
                .contains("max_minutes_per_meal");
    }

    @Test
    void anEmptyMealTypeListIsRejected() throws Exception {
        Session session = onboardedMom("0912380020");

        preview(session, """
                {"request_id":"req-no-meals","start_date":"2026-10-14","meal_types":[]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void aMissingRequestIdIsRejectedBecauseItIsTheIdempotencyKey() throws Exception {
        Session session = onboardedMom("0912380021");

        preview(session, """
                {"start_date":"2026-10-14","meal_types":["LUNCH"]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.fields.request_id").isNotEmpty());
    }

    // =========================================================================================
    // Giới hạn cứng
    // =========================================================================================

    /**
     * Bật giới hạn ngân sách cứng mà danh mục còn món chưa có giá: những món đó không vào kế hoạch,
     * và người dùng được mời bổ sung giá hoặc tắt giới hạn — <strong>họ chọn</strong>.
     */
    @Test
    void aStrictBudgetWithMissingPricesAsksTheUserToChooseInsteadOfGuessing() throws Exception {
        Session session = onboardedMom("0912380022");

        MvcResult result = preview(session, """
                {"request_id":"req-strict-budget","start_date":"2026-10-14",
                 "meal_types":["LUNCH"],
                 "constraints":{"daily_budget_vnd":120000,"strict_budget":true}}
                """).andExpect(status().isOk()).andReturn();

        assertThat(noteCodes(result))
                .describedAs("phải nói ra là có món chưa quy đổi được giá")
                .contains("STRICT_BUDGET_WITHOUT_PRICE");
    }

    @Test
    void theRawPlanScoreNeverLeavesTheBackend() throws Exception {
        Session session = onboardedMom("0912380023");

        MvcResult result = preview(session, """
                {"request_id":"req-no-score","start_date":"2026-10-14",
                 "meal_types":["LUNCH","DINNER"]}
                """).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("objective")
                .doesNotContain("penalt")
                .doesNotContain("score");
    }

    @Test
    void withoutAnActivePregnancyTheCallAsksToFinishOnboardingFirst() throws Exception {
        Session session = registerViaOtp("0912380024", "Chưa khai thai kỳ");

        preview(session, """
                {"request_id":"req-no-pregnancy","start_date":"2026-10-14","meal_types":["LUNCH"]}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PREGNANCY_NOT_FOUND"));
    }

    // =========================================================================================
    // Fixture
    // =========================================================================================

    private Session onboardedMom(String phone) throws Exception {
        Session session = registerViaOtp(phone, "Mẹ bầu " + phone);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/users/me")
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

    private ResultActions preview(Session session, String body) throws Exception {
        return mockMvc.perform(post(PREVIEW_PATH)
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions save(Session session, String previewId) throws Exception {
        return mockMvc.perform(post(PLAN_PATH)
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preview_id\":\"" + previewId + "\"}"));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private java.math.BigDecimal energy(MvcResult result) throws Exception {
        return data(result).at("/days/0/nutrition/ENERGY/known_value").decimalValue();
    }

    private List<String> recipeIds(MvcResult result) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode slot : data(result).at("/days/0/slots")) {
            for (JsonNode item : slot.at("/items")) {
                ids.add(item.at("/recipe_id").stringValue());
            }
        }
        return ids;
    }

    private List<String> noteCodes(MvcResult result) throws Exception {
        List<String> codes = new ArrayList<>();
        for (JsonNode note : data(result).at("/notes")) {
            codes.add(note.at("/code").stringValue());
        }
        return codes;
    }

    private String noteMessages(MvcResult result) throws Exception {
        StringBuilder messages = new StringBuilder();
        for (JsonNode note : data(result).at("/notes")) {
            messages.append(note.at("/message").stringValue()).append('\n');
        }
        for (JsonNode slot : data(result).at("/days/0/slots")) {
            for (JsonNode note : slot.at("/reason_codes")) {
                messages.append(note.at("/message").stringValue()).append('\n');
            }
        }
        return messages.toString();
    }
}
