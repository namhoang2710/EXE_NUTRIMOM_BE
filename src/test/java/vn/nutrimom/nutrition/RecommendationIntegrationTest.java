package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.nutrition.domain.EligibilityStatus;
import vn.nutrimom.nutrition.domain.RecommendationLogEntity;
import vn.nutrimom.nutrition.repository.RecommendationLogRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Gợi ý món chạy thật trên <strong>seed thật</strong>, từ đăng ký tới khi nhận danh sách có lý do.
 *
 * <p>Dùng seed thật chứ không fixture dựng tay vì đây là nơi chứng minh rằng danh mục đang nằm
 * trong repo thực sự chạy được qua cả hai bước: món canh bí đỏ tôm phải biến mất với người dị ứng
 * tôm, món canh rau nêm sẵn phải biến mất vì gia vị chưa rà soát.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.nutrition.seed-enabled=true")
@Import(RecommendationIntegrationTest.FixedTimeConfig.class)
class RecommendationIntegrationTest extends ApiIntegrationTestSupport {

    private static final String PATH = "/api/v1/recommendations";

    @Autowired RecommendationLogRepository logs;

    @Test
    void aFreshProfileGetsRankedLunchesEachWithAtLeastOneReason() throws Exception {
        Session session = onboardedMom("0912370001");

        MvcResult result = recommend(session, """
                {"meal_types":["LUNCH"],"limit":5}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meals[0].meal_type").value("LUNCH"))
                .andExpect(jsonPath("$.data.meals[0].status").value("READY"))
                .andExpect(jsonPath("$.data.meals[0].items").isNotEmpty())
                .andReturn();

        for (JsonNode item : data(result).at("/meals/0/items")) {
            assertThat(item.at("/reasons").size())
                    .describedAs("mỗi món phải có 1–2 lý do (BR15): " + item.at("/recipe_id"))
                    .isBetween(1, 2);
            assertThat(item.at("/reasons/0/message").stringValue()).isNotBlank();
        }
    }

    /** Điểm thuật toán giữ ở backend — không có đường nào để nó thành "điểm sức khỏe" trên UI. */
    @Test
    void theResponseNeverCarriesTheRawScore() throws Exception {
        Session session = onboardedMom("0912370002");

        MvcResult result = recommend(session, """
                {"meal_types":["LUNCH"]}
                """).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("score_total")
                .doesNotContain("\"score\"");
    }

    // ---------------------------------------------------------------------------------------
    // TC01 và TC02
    // ---------------------------------------------------------------------------------------

    /** TC01: dị ứng tôm → món canh có nước dùng tôm biến mất khỏi danh sách, kèm lý do. */
    @Test
    void aShrimpAllergyRemovesTheShrimpSoupAndSaysWhy() throws Exception {
        Session session = onboardedMom("0912370003");
        saveProfile(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.giap-xac"]}
                """);

        MvcResult result = recommend(session, """
                {"meal_types":["LUNCH"],"limit":20}
                """).andExpect(status().isOk()).andReturn();

        assertThat(recipeIds(result, "/meals/0/items")).doesNotContain("rec.canh-bi-do-tom");
        JsonNode excluded = findExcluded(result, "rec.canh-bi-do-tom");
        assertThat(excluded.at("/status").stringValue()).isEqualTo("BLOCKED");
        assertThat(excluded.at("/reasons/0/code").stringValue()).startsWith("ALLERGEN");
    }

    /** TC02: đánh dấu đúng món đó là yêu thích cũng không làm nó hợp lệ (BR05). */
    @Test
    void favouritingTheAllergenDishDoesNotBringItBack() throws Exception {
        Session session = onboardedMom("0912370004");
        saveProfile(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.giap-xac"],
                 "favorite_recipe_ids":["rec.canh-bi-do-tom"]}
                """);

        MvcResult result = recommend(session, """
                {"meal_types":["LUNCH"],"limit":20}
                """).andExpect(status().isOk()).andReturn();

        assertThat(recipeIds(result, "/meals/0/items")).doesNotContain("rec.canh-bi-do-tom");
        assertThat(findExcluded(result, "rec.canh-bi-do-tom").at("/status").stringValue())
                .isEqualTo("BLOCKED");
    }

    // ---------------------------------------------------------------------------------------
    // TC03
    // ---------------------------------------------------------------------------------------

    /**
     * TC03: món dùng gia vị nêm sẵn <em>chưa rà soát</em> không vào thực đơn cá nhân hóa, nhưng
     * vẫn tra cứu được ở danh mục công khai.
     */
    @Test
    void anUnscreenedSeasoningKeepsTheDishOutOfSuggestionsButNotOutOfTheCatalog()
            throws Exception {
        Session session = onboardedMom("0912370005");

        MvcResult result = recommend(session, """
                {"meal_types":["LUNCH"],"limit":20}
                """).andExpect(status().isOk()).andReturn();

        assertThat(recipeIds(result, "/meals/0/items")).doesNotContain("rec.canh-rau-nem-san");
        assertThat(findExcluded(result, "rec.canh-rau-nem-san").at("/status").stringValue())
                .isEqualTo(EligibilityStatus.UNKNOWN.name());

        mockMvc.perform(get("/api/v1/recipes/{id}", "rec.canh-rau-nem-san"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------
    // BR01 và trạng thái dữ liệu
    // ---------------------------------------------------------------------------------------

    @Test
    void anUnconfirmedAllergyStatusIsReportedSoTheListIsNotLabelledChecked() throws Exception {
        Session session = onboardedMom("0912370006");

        recommend(session, """
                {"meal_types":["LUNCH"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allergy_checked").value(false))
                .andExpect(jsonPath("$.data.notes[?(@.code == 'ALLERGY_STATUS_UNCONFIRMED')]")
                        .exists());
    }

    @Test
    void confirmingNoAllergiesClearsThatNote() throws Exception {
        Session session = onboardedMom("0912370007");
        saveProfile(session, """
                {"allergy_status":"KNOWN_NONE"}
                """);

        recommend(session, """
                {"meal_types":["LUNCH"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allergy_checked").value(true))
                .andExpect(jsonPath("$.data.notes[?(@.code == 'ALLERGY_STATUS_UNCONFIRMED')]")
                        .doesNotExist());
    }

    /** Seed hiện thiếu nhiều số liệu vi chất → cả bữa phải mang nhãn PARTIAL, không tô đầy. */
    @Test
    void incompleteSeedDataIsReportedAsPartialRatherThanHidden() throws Exception {
        Session session = onboardedMom("0912370008");

        recommend(session, """
                {"meal_types":["LUNCH"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meals[0].nutrition_status").value("PARTIAL"));
    }

    // ---------------------------------------------------------------------------------------
    // Hồ sơ đổi thì danh sách đổi theo
    // ---------------------------------------------------------------------------------------

    @Test
    void turningOnAHardTimeLimitRemovesTheSlowDishes() throws Exception {
        Session session = onboardedMom("0912370009");
        saveProfile(session, """
                {"allergy_status":"KNOWN_NONE","max_minutes_per_meal":15}
                """);

        MvcResult soft = recommend(session, """
                {"meal_types":["LUNCH"],"limit":20}
                """).andExpect(status().isOk()).andReturn();
        MvcResult hard = recommend(session, """
                {"meal_types":["LUNCH"],"limit":20,"strict_time":true}
                """).andExpect(status().isOk()).andReturn();

        // Mềm: món nấu lâu vẫn còn, chỉ bị hạ điểm C (BR13).
        assertThat(recipeIds(soft, "/meals/0/items")).contains("rec.com-trang");
        assertThat(recipeIds(hard, "/meals/0/items")).doesNotContain("rec.com-trang");
        assertThat(findExcluded(hard, "rec.com-trang").at("/reasons/0/code").stringValue())
                .isEqualTo("OVER_TIME_LIMIT");
    }

    @Test
    void blockingADishRemovesItFromTheCandidateSetEntirely() throws Exception {
        Session session = onboardedMom("0912370010");
        saveProfile(session, """
                {"allergy_status":"KNOWN_NONE","blocked_recipe_ids":["rec.com-trang"]}
                """);

        MvcResult result = recommend(session, """
                {"meal_types":["LUNCH"],"limit":20}
                """).andExpect(status().isOk()).andReturn();

        assertThat(findExcluded(result, "rec.com-trang").at("/reasons/0/code").stringValue())
                .isEqualTo("USER_BLOCKED_RECIPE");
    }

    @Test
    void aVeganProfileCanRunOutOfLunchesAndSaysSoWithoutAnError() throws Exception {
        Session session = onboardedMom("0912370011");
        saveProfile(session, """
                {"allergy_status":"KNOWN_NONE","diet_pattern":"VEGAN"}
                """);

        // Hết món vẫn là HTTP 200 kèm lý do — không bao giờ nới chế độ ăn để có kết quả.
        recommend(session, """
                {"meal_types":["BREAKFAST"],"limit":20}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meals[0].status").value("NO_MATCH"))
                .andExpect(jsonPath("$.data.meals[0].notes[?(@.code == 'NO_MATCH')]").exists())
                .andExpect(jsonPath("$.data.meals[0].items").isEmpty());
    }

    @Test
    void askingForTwoMealsReturnsTwoIndependentLists() throws Exception {
        Session session = onboardedMom("0912370012");

        recommend(session, """
                {"meal_types":["LUNCH","SNACK"],"limit":3}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meals.length()").value(2))
                .andExpect(jsonPath("$.data.meals[0].meal_type").value("LUNCH"))
                .andExpect(jsonPath("$.data.meals[1].meal_type").value("SNACK"));
    }

    @Test
    void theSameSeedReturnsTheSameOrderTwice() throws Exception {
        Session session = onboardedMom("0912370013");
        String body = """
                {"meal_types":["LUNCH"],"limit":5,"seed":4242}
                """;

        MvcResult first = recommend(session, body).andExpect(status().isOk()).andReturn();
        MvcResult again = recommend(session, body).andExpect(status().isOk()).andReturn();

        assertThat(recipeIds(first, "/meals/0/items"))
                .isEqualTo(recipeIds(again, "/meals/0/items"));
    }

    // ---------------------------------------------------------------------------------------
    // Nhật ký gợi ý
    // ---------------------------------------------------------------------------------------

    /** Nhật ký phải đủ để dựng lại thứ hạng, và phải ghi cả món bị loại. */
    @Test
    void theLogKeepsEveryCandidateWithItsComponentScores() throws Exception {
        Session session = onboardedMom("0912370014");
        saveProfile(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.giap-xac"]}
                """);
        MvcResult result = recommend(session, """
                {"meal_types":["LUNCH"],"limit":5,"request_id":"req-log-1"}
                """).andExpect(status().isOk()).andReturn();

        // Đi qua log_id trong phản hồi: vừa kiểm nhật ký, vừa kiểm rằng mã trả về cho giao diện
        // thật sự trỏ tới một bản ghi.
        String logId = data(result).at("/meals/0/log_id").stringValue();
        RecommendationLogEntity log = logs.findWithEntries(logId).orElseThrow();

        assertThat(log.getRequestId()).isEqualTo("req-log-1");
        assertThat(log.getRulesVersion()).isNotBlank();
        assertThat(log.getEntries()).isNotEmpty();
        assertThat(log.getEntries())
                .describedAs("món bị loại cũng phải có dòng, kèm lý do")
                .anyMatch(entry -> entry.getEligibility() != EligibilityStatus.ELIGIBLE
                        && !entry.getReasonCodes().isBlank());
        assertThat(log.getEntries())
                .describedAs("món được gợi ý phải có điểm thành phần để tái lập thứ hạng")
                .anyMatch(entry -> entry.getRank() != null && entry.getScoreTotal() != null
                        && entry.getScorePreference() != null);
        // Bất biến 2: món chưa qua lọc không bao giờ có điểm tổng.
        assertThat(log.getEntries())
                .filteredOn(entry -> entry.getEligibility() != EligibilityStatus.ELIGIBLE)
                .allMatch(entry -> entry.getScoreTotal() == null && entry.getRank() == null);
    }

    @Test
    void withoutAnActivePregnancyTheCallAsksToFinishOnboardingFirst() throws Exception {
        Session session = registerViaOtp("0912370015", "Chưa khai thai kỳ");

        recommend(session, """
                {"meal_types":["LUNCH"]}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PREGNANCY_NOT_FOUND"));
    }

    @Test
    void theEndpointRefusesAnonymousCallers() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meal_types\":[\"LUNCH\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anEmptyMealTypeListIsRejected() throws Exception {
        Session session = onboardedMom("0912370016");

        recommend(session, """
                {"meal_types":[]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ---------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------

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

    private ResultActions recommend(Session session, String body) throws Exception {
        return mockMvc.perform(post(PATH)
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private List<String> recipeIds(MvcResult result, String pointer) throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode node : data(result).at(pointer)) {
            ids.add(node.at("/recipe_id").stringValue());
        }
        return ids;
    }

    private JsonNode findExcluded(MvcResult result, String recipeId) throws Exception {
        for (JsonNode node : data(result).at("/meals/0/excluded")) {
            if (recipeId.equals(node.at("/recipe_id").stringValue())) {
                return node;
            }
        }
        throw new AssertionError("Không thấy " + recipeId + " trong danh sách bị loại");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTimeConfig {
        @Bean
        @Primary
        Clock fixedRecommendationClock() {
            return Clock.fixed(Instant.parse("2026-10-15T18:30:00Z"), ZoneOffset.UTC);
        }
    }
}
