package vn.nutrimom.nutrition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Hồ sơ ăn uống chạy thật, từ lúc đăng ký tới lúc lưu xong năm bước onboarding.
 *
 * <p>Bật lại {@code app.nutrition.seed-enabled} vì bài này cần mã dị ứng, nguyên liệu và món
 * <strong>có thật</strong> trong danh mục: service từ chối mã lạ, và chính việc từ chối đó là thứ
 * giữ cho một mã dị ứng gõ sai không biến mất không ai hay.</p>
 *
 * <p>Đồng hồ cố định ở {@code 2026-10-15T18:30:00Z} — tức <strong>ngày 16/10 giờ Việt Nam</strong>.
 * Chọn đúng thời điểm lệch ngày giữa UTC và giờ người dùng để {@code local_date} nói dối là lộ
 * ngay, thay vì chỉ đúng tình cờ 17 giờ mỗi ngày.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.nutrition.seed-enabled=true")
@Import(NutritionProfileIntegrationTest.FixedTimeConfig.class)
class NutritionProfileIntegrationTest extends ApiIntegrationTestSupport {

    private static final String PATH = "/api/v1/me/nutrition-profile";

    @Test
    void theProfileStartsUnknownAndNeverClaimsTheMotherHasNoAllergy() throws Exception {
        Session session = onboardedMom("0912360001", 13, 6);

        getProfile(session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile_version").value(0))
                .andExpect(jsonPath("$.data.allergy_status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.allergen_ids").isEmpty())
                // BR01: chưa khai thì danh sách gợi ý không được gắn nhãn "đã kiểm tra dị ứng".
                .andExpect(jsonPath("$.data.allergy_checked_label_allowed").value(false))
                .andExpect(jsonPath("$.data.needs_allergy_confirmation").value(true));
    }

    /** TC04 — 13 tuần 6 ngày vẫn là giai đoạn 1. */
    @Test
    void thirteenWeeksSixDaysIsStillTheFirstTrimester() throws Exception {
        Session session = onboardedMom("0912360002", 13, 6);

        getProfile(session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pregnancy.gestational_days").value(97))
                .andExpect(jsonPath("$.data.pregnancy.gestational_week").value(13))
                .andExpect(jsonPath("$.data.pregnancy.gestational_day").value(6))
                .andExpect(jsonPath("$.data.pregnancy.trimester").value(1))
                // Ngày lịch của người dùng, không phải ngày UTC: đồng hồ đang ở 15/10 giờ UTC.
                .andExpect(jsonPath("$.data.pregnancy.local_date").value("2026-10-16"))
                .andExpect(jsonPath("$.data.pregnancy.timezone").value("Asia/Ho_Chi_Minh"));
    }

    /** TC04 — 14 tuần 0 ngày đã là giai đoạn 2, không lệch một ngày. */
    @Test
    void fourteenWeeksZeroDaysIsAlreadyTheSecondTrimester() throws Exception {
        Session session = onboardedMom("0912360003", 14, 0);

        getProfile(session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pregnancy.gestational_days").value(98))
                .andExpect(jsonPath("$.data.pregnancy.gestational_week").value(14))
                .andExpect(jsonPath("$.data.pregnancy.gestational_day").value(0))
                .andExpect(jsonPath("$.data.pregnancy.trimester").value(2));
    }

    /**
     * TC05 — bỏ qua chiều cao và cân nặng vẫn lưu được hồ sơ và vẫn dùng được chế độ nhanh.
     *
     * <p>Điều kiện nghiệm thu thật nằm ở {@code kcal_per_day}: nó phải <strong>null</strong> kèm
     * lý do, chứ không phải một con số mặc định. Có số là giao diện sẽ vẽ phần trăm hoàn thành
     * năng lượng dựa trên một mục tiêu không ai khai (BR20).</p>
     */
    @Test
    void skippingHeightAndWeightStillSavesAndLeavesNoEnergyTarget() throws Exception {
        Session session = onboardedMom("0912360004", 20, 0);

        // Bước 4 xong, bước 5 bấm "Để sau".
        save(session, """
                {"allergy_status":"KNOWN_NONE",
                 "max_minutes_per_meal":30,
                 "daily_budget_vnd":120000,
                 "meal_types":["BREAKFAST","LUNCH","DINNER"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile_version").value(1))
                .andExpect(jsonPath("$.data.profile.energy_inputs").doesNotExist())
                .andExpect(jsonPath("$.data.profile.energy_estimate.kcal_per_day").doesNotExist())
                .andExpect(jsonPath("$.data.profile.max_minutes_per_meal").value(30));

        getProfile(session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.energy_estimate.kcal_per_day").doesNotExist())
                // Báo thiếu theo đúng tên trường để giao diện chỉ được chỗ cần sửa.
                .andExpect(jsonPath("$.data.energy_estimate.unmet[?(@.field == 'height_cm')]")
                        .exists())
                .andExpect(jsonPath(
                        "$.data.energy_estimate.unmet[?(@.field == 'current_weight_kg')]").exists());

        // Và danh mục món vẫn dùng được bình thường với một hồ sơ khai dở.
        mockMvc.perform(get("/api/v1/recipes")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").exists());
    }

    @Test
    void completingStepFiveProducesAnEnergyEstimate() throws Exception {
        Session session = onboardedMom("0912360005", 20, 0);

        save(session, """
                {"age_years":29,"height_cm":160.0,"current_weight_kg":58.0,
                 "pre_pregnancy_weight_kg":52.0,"activity_category":"LOW_ACTIVE"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.energy_estimate.kcal_per_day").isNumber())
                .andExpect(jsonPath("$.data.profile.energy_estimate.unmet").isEmpty())
                .andExpect(jsonPath("$.data.profile.energy_inputs.height_cm").value(160.0));
    }

    /** Lưu hồ sơ làm {@code profile_version} tăng <strong>đúng một</strong>. */
    @Test
    void eachSaveWithRealChangesRaisesTheProfileVersionByExactlyOne() throws Exception {
        Session session = onboardedMom("0912360006", 20, 0);

        save(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.giap-xac"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile_version").value(1))
                .andExpect(jsonPath("$.data.profile.allergy_checked_label_allowed").value(true))
                .andExpect(jsonPath("$.data.profile.needs_allergy_confirmation").value(false))
                // Nhánh này luôn trả rỗng; đánh dấu NEEDS_UPDATE là việc của nhánh 10.
                .andExpect(jsonPath("$.data.items_to_revalidate").isEmpty());

        save(session, """
                {"diet_pattern":"PESCATARIAN"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile_version").value(2));
    }

    /**
     * Gửi lại y nguyên dữ liệu cũ <strong>không</strong> làm tăng phiên bản.
     *
     * <p>Người dùng bấm Lưu hai lần, hoặc mạng gửi lại, không phải là lý do để nhánh 10 bắt họ
     * xem lại toàn bộ thực đơn đang có.</p>
     */
    @Test
    void resendingTheSameBodyDoesNotRaiseTheVersion() throws Exception {
        Session session = onboardedMom("0912360007", 20, 0);
        String body = """
                {"allergy_status":"KNOWN_NONE","max_minutes_per_meal":45,"height_cm":160.00}
                """;

        save(session, body).andExpect(jsonPath("$.data.profile_version").value(1));
        save(session, body).andExpect(jsonPath("$.data.profile_version").value(1));
    }

    /** Lưu theo từng bước: bước sau không được xóa mất những gì bước trước vừa lưu. */
    @Test
    void savingOneStepKeepsWhatTheEarlierStepsStored() throws Exception {
        Session session = onboardedMom("0912360008", 20, 0);

        save(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.giap-xac"]}
                """).andExpect(status().isOk());
        save(session, """
                {"max_minutes_per_meal":25}
                """).andExpect(status().isOk());

        getProfile(session)
                .andExpect(jsonPath("$.data.allergen_ids[0]").value("alg.giap-xac"))
                .andExpect(jsonPath("$.data.max_minutes_per_meal").value(25));
    }

    /** Mảng rỗng là cách duy nhất để xóa một danh sách — phân biệt được với "không gửi". */
    @Test
    void anEmptyArrayClearsAListWhileAnAbsentFieldKeepsIt() throws Exception {
        Session session = onboardedMom("0912360009", 20, 0);

        save(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.giap-xac","alg.ca"]}
                """).andExpect(status().isOk());

        save(session, """
                {"allergy_status":"KNOWN_NONE","allergen_ids":[]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.allergy_status").value("KNOWN_NONE"))
                .andExpect(jsonPath("$.data.profile.allergen_ids").isEmpty());
    }

    @Test
    void favouriteAndBlockedRecipesAreStoredWithoutAnyExpiry() throws Exception {
        Session session = onboardedMom("0912360010", 20, 0);

        save(session, """
                {"favorite_recipe_ids":["rec.com-trang"],
                 "blocked_recipe_ids":["rec.trung-op-la"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.favorite_recipe_ids[0]").value("rec.com-trang"))
                .andExpect(jsonPath("$.data.profile.blocked_recipe_ids[0]").value("rec.trung-op-la"));

        // Đổi thái độ với cùng một món là cập nhật tại chỗ, không sinh dòng thứ hai mâu thuẫn.
        save(session, """
                {"favorite_recipe_ids":[],"blocked_recipe_ids":["rec.trung-op-la","rec.com-trang"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.favorite_recipe_ids").isEmpty())
                .andExpect(jsonPath("$.data.profile.blocked_recipe_ids.length()").value(2));
    }

    @Test
    void aRecipeCannotBeFavouriteAndBlockedInTheSameSave() throws Exception {
        Session session = onboardedMom("0912360011", 20, 0);

        save(session, """
                {"favorite_recipe_ids":["rec.com-trang"],"blocked_recipe_ids":["rec.com-trang"]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anAllergenIdThatIsNotInTheCatalogIsRejectedInsteadOfSilentlyDropped() throws Exception {
        Session session = onboardedMom("0912360012", 20, 0);

        save(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.khong-ton-tai"]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        getProfile(session).andExpect(jsonPath("$.data.profile_version").value(0));
    }

    @Test
    void declaringAListWithNoAllergensIsRejectedBecauseItWouldLookChecked() throws Exception {
        Session session = onboardedMom("0912360013", 20, 0);

        save(session, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":[]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anIngredientCannotBeBothAHardExclusionAndADislike() throws Exception {
        Session session = onboardedMom("0912360014", 20, 0);

        save(session, """
                {"hard_exclusion_ingredient_ids":["ing.tom-su"],
                 "disliked_ingredient_ids":["ing.tom-su"]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /** BR19 — hồ sơ là của riêng chủ tài khoản, và không có cách nào trỏ sang hồ sơ người khác. */
    @Test
    void anotherAccountNeverSeesThisProfile() throws Exception {
        Session mom = onboardedMom("0912360015", 20, 0);
        save(mom, """
                {"allergy_status":"KNOWN_LIST","allergen_ids":["alg.giap-xac"]}
                """).andExpect(status().isOk());

        Session stranger = onboardedMom("0912360016", 10, 0);

        // Người khác chỉ thấy hồ sơ của chính họ, không có tham số nào để trỏ sang hồ sơ của mom.
        getProfile(stranger)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allergy_status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.allergen_ids").isEmpty());

        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutAnActivePregnancyTheProfileAsksToFinishStepOneFirst() throws Exception {
        Session session = registerViaOtp("0912360017", "Chưa khai thai kỳ");

        getProfile(session)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PREGNANCY_NOT_FOUND"));
    }

    @Test
    void snackCountWithoutASnackMealIsRejected() throws Exception {
        Session session = onboardedMom("0912360018", 20, 0);

        save(session, """
                {"meal_types":["BREAKFAST","LUNCH","DINNER"],"snack_count":2}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        save(session, """
                {"meal_types":["BREAKFAST","LUNCH","DINNER","SNACK"],"snack_count":2}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.snack_count").value(2));
    }

    // -------------------------------------------------------------------------------------

    /** Đăng ký, hoàn tất hồ sơ người dùng, rồi khai thai kỳ bằng tuần + ngày (bước 1). */
    private Session onboardedMom(String phone, int week, int day) throws Exception {
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
                        .content("{\"gestational_week\":" + week + ",\"gestational_day\":" + day
                                + ",\"timezone\":\"Asia/Ho_Chi_Minh\"}"))
                .andExpect(status().isCreated());
        return session;
    }

    private ResultActions getProfile(Session session) throws Exception {
        return mockMvc.perform(get(PATH)
                .header("Authorization", "Bearer " + session.accessToken()));
    }

    private ResultActions save(Session session, String body) throws Exception {
        return mockMvc.perform(put(PATH)
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTimeConfig {
        /** 15/10 giờ UTC nhưng đã là 16/10 giờ Việt Nam — cố ý chọn lúc hai lịch lệch ngày. */
        @Bean
        @Primary
        Clock fixedNutritionClock() {
            return Clock.fixed(Instant.parse("2026-10-15T18:30:00Z"), ZoneOffset.UTC);
        }
    }
}
