package vn.nutrimom.entitlement;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/** Catalog server-side thay cho bảng giá hardcode của FE (spec mục 17). */
@org.springframework.test.context.bean.override.mockito.MockitoBean(types = {
        vn.nutrimom.assistant.service.GroqAssistantClient.class,
        vn.nutrimom.consultation.video.LiveKitClient.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SubscriptionPlanIntegrationTest extends ApiIntegrationTestSupport {

    /**
     * Không kèm token.
     *
     * <p>Đây là điểm mấu chốt: trang giá phải render được khi chưa đăng nhập, nếu không FE vẫn buộc
     * phải hardcode giá — đúng cái spec mục 17 cấm.</p>
     */
    @Test
    void theCatalogIsReadableWithoutLoggingIn() throws Exception {
        mockMvc.perform(get("/api/v1/subscription-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].id").value("FREE"))
                .andExpect(jsonPath("$.data[1].id").value("PLAN_399K"))
                .andExpect(jsonPath("$.data[2].id").value("PLAN_99K"))
                .andExpect(jsonPath("$.data[1].featured").value(true))
                .andExpect(jsonPath("$.data[2].price").value(99000))
                .andExpect(jsonPath("$.data[2].currency").value("VND"))
                .andExpect(jsonPath("$.data[2].duration_days").value(30));
    }

    /**
     * Ba field thời hạn, và {@code duration_days} phải <strong>luôn có mặt</strong>.
     *
     * <p>{@code default-property-inclusion: non_null} sẽ xoá hẳn field khỏi JSON nếu nó null, nên
     * để nullable là âm thầm đổi hợp đồng của một endpoint công khai. {@code duration_iso} là
     * nguồn chính xác cho gói tính theo tháng lịch; {@code duration_days} chỉ là con số danh nghĩa.</p>
     */
    @Test
    void everyPlanPublishesItsTermInAFormTheClientCanRender() throws Exception {
        mockMvc.perform(get("/api/v1/subscription-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].duration_iso").value("P0D"))
                .andExpect(jsonPath("$.data[0].duration_label").value("Mãi mãi"))
                .andExpect(jsonPath("$.data[0].duration_days").value(0))
                .andExpect(jsonPath("$.data[1].duration_iso").value("P10M"))
                .andExpect(jsonPath("$.data[1].duration_label").value("10 tháng"))
                .andExpect(jsonPath("$.data[1].duration_days").value(300))
                .andExpect(jsonPath("$.data[2].duration_iso").value("P30D"))
                .andExpect(jsonPath("$.data[2].duration_label").value("30 ngày"));
    }

    @Test
    void everyPlanShipsTheWholeFeatureTableSoTheComparisonStaysAligned() throws Exception {
        mockMvc.perform(get("/api/v1/subscription-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].features", hasSize(10)))
                .andExpect(jsonPath("$.data[1].features", hasSize(10)))
                .andExpect(jsonPath("$.data[2].features", hasSize(10)))
                .andExpect(jsonPath("$.data[0].features[0].code").value("FAMILY_SHARING"))
                .andExpect(jsonPath("$.data[0].features[0].label").value("Liên kết gia đình"))
                // FREE: không có liên kết gia đình, cũng không có lịch nhắc nhở.
                .andExpect(jsonPath("$.data[0].features[0].included").value(false))
                .andExpect(jsonPath("$.data[0].features[4].code").value("CALENDAR_REMINDERS"))
                .andExpect(jsonPath("$.data[0].features[4].included").value(false))
                // PLAN_99K có lịch nhắc nhưng vẫn không có liên kết gia đình.
                .andExpect(jsonPath("$.data[2].features[0].included").value(false))
                .andExpect(jsonPath("$.data[2].features[4].included").value(true));
    }

    @Test
    void scanQuotaCarriesItsPeriod() throws Exception {
        mockMvc.perform(get("/api/v1/subscription-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].scan_quota.limit").value(2))
                .andExpect(jsonPath("$.data[0].scan_quota.period").value("LIFETIME"))
                .andExpect(jsonPath("$.data[1].scan_quota.unlimited").value(true))
                .andExpect(jsonPath("$.data[2].scan_quota.period").value("DAILY"));
    }

    @Test
    void mySubscriptionReportsFreeForAFreshAccount() throws Exception {
        Session user = registerViaOtp("0932000001", "Fresh Account");

        mockMvc.perform(get("/api/v1/subscriptions/me")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_id").value("FREE"))
                .andExpect(jsonPath("$.data.paid").value(false))
                .andExpect(jsonPath("$.data.end_date").doesNotExist())
                .andExpect(jsonPath("$.data.features", hasSize(10)));
    }

    @Test
    void mySubscriptionReflectsAPaidPlan() throws Exception {
        Session user = registerViaOtp("0932000002", "Paid Account");
        PlanFixtures.grant(subscriptions, user.userId(), PlanTier.PLAN_399K);

        mockMvc.perform(get("/api/v1/subscriptions/me")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_id").value("PLAN_399K"))
                .andExpect(jsonPath("$.data.paid").value(true))
                .andExpect(jsonPath("$.data.features[0].code").value("FAMILY_SHARING"))
                .andExpect(jsonPath("$.data.features[0].included").value(true));
    }

    @Test
    void mySubscriptionNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/v1/subscriptions/me"))
                .andExpect(status().isUnauthorized());
    }
}
