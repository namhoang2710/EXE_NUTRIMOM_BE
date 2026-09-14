package vn.nutrimom.pregnancy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.support.ApiIntegrationTestSupport;
import vn.nutrimom.pregnancy.domain.PregnancyWeekContentEntity;
import vn.nutrimom.pregnancy.repository.PregnancyAuditRepository;
import vn.nutrimom.pregnancy.repository.PregnancyWeekContentRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PregnancyIntegrationTest extends ApiIntegrationTestSupport {
        @Autowired
        PregnancyAuditRepository pregnancyAudits;
        @Autowired
        PregnancyWeekContentRepository weekContents;

    @BeforeEach
    void seedWeeklyContentFixture() {
        weekContents.deleteAll();
        PregnancyWeekContentEntity content = new PregnancyWeekContentEntity();
        content.setWeek(20);
        content.setTitle("Tuần thai kỳ 20");
        content.setSummary("Nội dung tổng quan đang chờ thẩm định y khoa.");
        content.setBabyDevelopment("Nội dung phát triển em bé đang chờ thẩm định y khoa.");
        content.setMotherChanges("Nội dung thay đổi của mẹ đang chờ thẩm định y khoa.");
        content.setCareTips("Tham khảo bác sĩ để được tư vấn phù hợp.");
        content.setWarningSigns("Liên hệ cơ sở y tế nếu có triệu chứng bất thường.");
        content.setSources("[{\"name\":\"WHO\",\"url\":\"https://www.who.int/health-topics/pregnancy\"}]");
        content.setDisclaimer("Nội dung tham khảo, không thay thế tư vấn của bác sĩ.");
        weekContents.saveAndFlush(content);
    }

    @Test
    void createByDueDateCompletesOnboardingAndDerivesMomPersona() throws Exception {
        Session session = registerViaOtp("0912353001", "Mom Due Date");
        completeProfile(session);
        LocalDate dueDate = today().plusDays(100);

        MvcResult created = mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBody(dueDate, null, null, "Central Clinic", "Dr An"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.estimated_due_date").value(dueDate.toString()))
                .andExpect(jsonPath("$.data.last_menstrual_period")
                        .value(dueDate.minusDays(280).toString()))
                .andExpect(jsonPath("$.data.calculation_source").value("EDD"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();
        String pregnancyId = responseData(created).at("/id").stringValue();

        mockMvc.perform(get("/api/v1/pregnancies/current")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(pregnancyId));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarding_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.role").value("MOM"));

        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBody(dueDate.plusDays(7), null, null, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PREGNANCY_EXISTS"));

        mockMvc.perform(patch("/api/v1/pregnancies/{id}", pregnancyId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"care_facility_name":"City Hospital",
                                 "calculation_source":"ULTRASOUND","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.care_facility_name").value("City Hospital"))
                .andExpect(jsonPath("$.data.calculation_source").value("ULTRASOUND"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(patch("/api/v1/pregnancies/{id}", pregnancyId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"care_provider_name\":\"Dr B\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void createByLmpCalculatesWeekDayTrimesterAndDueDate() throws Exception {
        Session session = registerViaOtp("0912353002", "Mom LMP");
        LocalDate lmp = today().minusDays(15 * 7L + 3);

        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBody(null, lmp, null, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.last_menstrual_period").value(lmp.toString()))
                .andExpect(jsonPath("$.data.estimated_due_date")
                        .value(lmp.plusDays(280).toString()))
                .andExpect(jsonPath("$.data.gestational_week").value(15))
                .andExpect(jsonPath("$.data.gestational_day").value(3))
                .andExpect(jsonPath("$.data.trimester").value(2))
                .andExpect(jsonPath("$.data.calculation_source").value("LMP"));
    }

    @Test
    void createRequiresAtLeastOnePregnancyDate() throws Exception {
        Session session = registerViaOtp("0912353003", "Missing Dates");

        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void pregnancyReadAndWriteAreRestrictedToOwner() throws Exception {
        Session owner = registerViaOtp("0912353004", "Pregnancy Owner");
        Session other = registerViaOtp("0912353005", "Pregnancy Other");
        LocalDate dueDate = today().plusDays(120);
        MvcResult created = mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBody(dueDate, null, null, null, null))))
                .andExpect(status().isCreated())
                .andReturn();
        String pregnancyId = responseData(created).at("/id").stringValue();

        mockMvc.perform(get("/api/v1/pregnancies/{id}", pregnancyId)
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/pregnancies/{id}", pregnancyId)
                        .header("Authorization", "Bearer " + other.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"care_facility_name\":\"Unauthorized\",\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void weeklyContentReturnsAllFieldsAndMissingWeekReturnsResourceNotFound() throws Exception {
        Session session = registerViaOtp("0912353006", "Weekly Content");

        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/20")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.week").value(20))
                .andExpect(jsonPath("$.data.title").isNotEmpty())
                .andExpect(jsonPath("$.data.summary").isNotEmpty())
                .andExpect(jsonPath("$.data.baby_development").isNotEmpty())
                .andExpect(jsonPath("$.data.mother_changes").isNotEmpty())
                .andExpect(jsonPath("$.data.care_tips").isNotEmpty())
                .andExpect(jsonPath("$.data.warning_signs").isNotEmpty())
                .andExpect(jsonPath("$.data.sources").isNotEmpty())
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty())
                .andExpect(jsonPath("$.data.created_at").isNotEmpty())
                .andExpect(jsonPath("$.data.updated_at").isNotEmpty());

        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/19")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void weeklyContentRejectsWeeksOutsideSupportedRange() throws Exception {
        Session session = registerViaOtp("0912353009", "Weekly Validation");

        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/43")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updatingDueDateKeepsDatesConsistentAndCreatesAudit() throws Exception {
        Session session = registerViaOtp("0912353007", "Date Sync");
        LocalDate originalDueDate = today().plusDays(120);
        MvcResult created = mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBody(originalDueDate, null, null, null, null))))
                .andExpect(status().isCreated())
                .andReturn();
        String pregnancyId = responseData(created).at("/id").stringValue();
        LocalDate updatedDueDate = originalDueDate.plusDays(14);

        mockMvc.perform(patch("/api/v1/pregnancies/{id}", pregnancyId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateBody(updatedDueDate, null, 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimated_due_date").value(updatedDueDate.toString()))
                .andExpect(jsonPath("$.data.last_menstrual_period")
                        .value(updatedDueDate.minusDays(280).toString()));

        org.assertj.core.api.Assertions.assertThat(pregnancyAudits.count()).isEqualTo(1);
    }

    @Test
    void inconsistentPregnancyDatesAreRejected() throws Exception {
        Session session = registerViaOtp("0912353008", "Invalid Dates");
        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBody(
                                today().plusDays(100), today().minusDays(100), null, null, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private void completeProfile(Session session) throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"display_name":"Mom Due Date","gender":"FEMALE","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarding_status").value("CONTEXT_REQUIRED"));
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private record CreateBody(
            LocalDate estimatedDueDate,
            LocalDate lastMenstrualPeriod,
            String calculationSource,
            String careFacilityName,
            String careProviderName) { }

        private record UpdateBody(LocalDate estimatedDueDate, LocalDate lastMenstrualPeriod, long version) { }
}
