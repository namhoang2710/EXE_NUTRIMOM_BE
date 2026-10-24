package vn.nutrimom.care;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import vn.nutrimom.assistant.service.AiProviderClient;
import vn.nutrimom.consultation.video.LiveKitGateway;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Hồ sơ y tế đọc xuyên thai kỳ, lịch thì không (handoff 23/10/2026).
 *
 * <p>Màn quản lý hồ sơ dùng để so sánh lần mang thai này với lần trước, nên danh sách không truyền
 * {@code pregnancy_id} phải trả hồ sơ của MỌI thai kỳ. Các test dưới đây khoá hợp đồng đó, cùng API
 * liệt kê thai kỳ mà FE cần để gắn nhãn từng hồ sơ.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MedicalRecordPregnancyScopeIntegrationTest extends ApiIntegrationTestSupport {
    @MockitoBean AiProviderClient assistantProvider;
    @MockitoBean LiveKitGateway videoProvider;

    @Test
    void theRecordListSpansEveryPregnancyUnlessOneIsAskedFor() throws Exception {
        Session mom = registerViaOtp("0912397101", "Second Pregnancy Mom");
        Session stranger = registerViaOtp("0912397102", "Stranger");
        String first = createPregnancy(mom);
        String firstRecord = createRecord(mom, first, "Siêu âm 12 tuần", "2026-10-20T03:00:00Z");
        closePregnancy(mom, first);
        String second = createPregnancy(mom);
        String secondRecord = createRecord(mom, second, "Khám thai tuần 21", "2026-10-23T03:00:00Z");

        mockMvc.perform(get("/api/v1/medical-records").header("Authorization", bearer(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].id").value(secondRecord))
                .andExpect(jsonPath("$.data.items[0].pregnancy_id").value(second))
                .andExpect(jsonPath("$.data.items[1].id").value(firstRecord))
                .andExpect(jsonPath("$.data.items[1].pregnancy_id").value(first));

        mockMvc.perform(get("/api/v1/medical-records").header("Authorization", bearer(mom))
                        .queryParam("pregnancy_id", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].id").value(firstRecord));

        mockMvc.perform(get("/api/v1/medical-records").header("Authorization", bearer(stranger))
                        .queryParam("pregnancy_id", first))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/medical-records").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void aClosedPregnancyKeepsItsRecordsButTakesNoNewOnes() throws Exception {
        Session mom = registerViaOtp("0912397103", "Closed Pregnancy Mom");
        String first = createPregnancy(mom);
        String oldRecord = createRecord(mom, first, "Xét nghiệm máu", "2026-10-01T03:00:00Z");
        closePregnancy(mom, first);

        mockMvc.perform(post("/api/v1/medical-records").header("Authorization", bearer(mom))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateRecord(first, "LAB_RESULT", "Thêm muộn", "2026-10-02T03:00:00Z"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREGNANCY_NOT_ACTIVE"));

        mockMvc.perform(post("/api/v1/medical-records").header("Authorization", bearer(mom))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRecord(
                                "00000000-0000-0000-0000-000000000000", "LAB_RESULT", "Không có",
                                "2026-10-02T03:00:00Z"))))
                .andExpect(status().isNotFound());

        // Hồ sơ cũ vẫn sửa được: đóng thai kỳ không khoá lịch sử.
        mockMvc.perform(patch("/api/v1/medical-records/{id}", oldRecord).header("Authorization", bearer(mom))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Đã xem lại\",\"version\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void theDateFilterFollowsTheVietnameseCalendarDay() throws Exception {
        Session mom = registerViaOtp("0912397104", "Date Filter Mom");
        String pregnancy = createPregnancy(mom);
        // FE lưu "ngày 23/10" thành 00:00 giờ Việt Nam = 17:00 UTC ngày 22/10.
        String startOfDay = createRecord(mom, pregnancy, "Đầu ngày", "2026-10-22T17:00:00Z");
        String endOfDay = createRecord(mom, pregnancy, "Cuối ngày", "2026-10-23T16:59:00Z");
        createRecord(mom, pregnancy, "Hôm sau", "2026-10-23T17:00:00Z");

        mockMvc.perform(get("/api/v1/medical-records").header("Authorization", bearer(mom))
                        .queryParam("from", "2026-10-23").queryParam("to", "2026-10-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].id").value(endOfDay))
                .andExpect(jsonPath("$.data.items[1].id").value(startOfDay));

        mockMvc.perform(get("/api/v1/medical-records").header("Authorization", bearer(mom))
                        .queryParam("to", "2026-10-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void pregnanciesAreListedNewestFirstAndClosedOnesCarryNoRunningAge() throws Exception {
        Session mom = registerViaOtp("0912397105", "Pregnancy List Mom");
        Session stranger = registerViaOtp("0912397106", "Pregnancy List Stranger");
        String first = createPregnancy(mom);
        closePregnancy(mom, first);
        String second = createPregnancy(mom);

        mockMvc.perform(get("/api/v1/pregnancies").header("Authorization", bearer(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(second))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].gestational_week").isNumber())
                .andExpect(jsonPath("$.data[1].id").value(first))
                .andExpect(jsonPath("$.data[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[1].estimated_due_date").isNotEmpty())
                .andExpect(jsonPath("$.data[1].gestational_week").doesNotExist())
                .andExpect(jsonPath("$.data[1].gestational_day").doesNotExist())
                .andExpect(jsonPath("$.data[1].trimester").doesNotExist())
                .andExpect(jsonPath("$.data[1].days_until_due").doesNotExist());

        mockMvc.perform(get("/api/v1/pregnancies").header("Authorization", bearer(mom))
                        .queryParam("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(first));

        mockMvc.perform(get("/api/v1/pregnancies").header("Authorization", bearer(mom))
                        .queryParam("status", "FINISHED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_PARAMETER"));

        mockMvc.perform(get("/api/v1/pregnancies").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        // Dashboard mở được thai kỳ đã đóng mà không vỡ vì các trường tuần thai giờ là null.
        mockMvc.perform(get("/api/v1/dashboard/mom").header("Authorization", bearer(mom))
                        .queryParam("pregnancy_id", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pregnancy_summary.id").value(first))
                .andExpect(jsonPath("$.data.pregnancy_summary.gestational_week").doesNotExist());
        mockMvc.perform(get("/api/v1/pregnancies/current").header("Authorization", bearer(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(second))
                .andExpect(jsonPath("$.data.days_until_due").isNumber());
    }

    private String createPregnancy(Session session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pregnancies").header("Authorization", bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PregnancyBody(LocalDate.now(ZoneOffset.UTC).plusDays(100)))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/id").stringValue();
    }

    private void closePregnancy(Session session, String pregnancyId) throws Exception {
        mockMvc.perform(patch("/api/v1/pregnancies/{id}", pregnancyId).header("Authorization", bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\",\"version\":0}"))
                .andExpect(status().isOk());
    }

    private String createRecord(Session session, String pregnancyId, String title, String occurredAt)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/medical-records").header("Authorization", bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateRecord(pregnancyId, "PRENATAL_VISIT", title, occurredAt))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/id").stringValue();
    }

    private String bearer(Session session) { return "Bearer " + session.accessToken(); }

    private record PregnancyBody(LocalDate estimatedDueDate) { }
    private record CreateRecord(String pregnancyId, String category, String title, String occurredAt) { }
}
