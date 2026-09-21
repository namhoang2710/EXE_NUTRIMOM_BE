package vn.nutrimom.pregnancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PregnancyProfileDeltaIntegrationTest.TimeTestConfig.class)
class PregnancyProfileDeltaIntegrationTest extends ApiIntegrationTestSupport {
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 19);

    @org.springframework.beans.factory.annotation.Autowired
    PregnancyRepository pregnancies;
    @org.springframework.beans.factory.annotation.Autowired
    MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setDate(BASE_DATE);
    }

    @Test
    void lmpPreviewCalculatesWithoutCreatingPregnancy() throws Exception {
        Session session = registerViaOtp("0912390001", "Preview LMP");

        assertThat(pregnancies.existsByOwnerUserIdAndStatus(session.userId(), PregnancyStatus.ACTIVE))
                .isFalse();
        mockMvc.perform(post("/api/v1/pregnancies/calculate")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"LMP\",\"date\":\"2026-09-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.last_menstrual_period").value("2026-09-01"))
                .andExpect(jsonPath("$.data.estimated_due_date").value("2027-06-08"))
                .andExpect(jsonPath("$.data.calculation_source").value("LMP"));
        assertThat(pregnancies.existsByOwnerUserIdAndStatus(session.userId(), PregnancyStatus.ACTIVE))
                .isFalse();
    }

    @Test
    void conceptionPreviewUses266DaysAndDoesNotPersist() throws Exception {
        Session session = registerViaOtp("0912390002", "Preview Conception");

        mockMvc.perform(post("/api/v1/pregnancies/calculate")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CONCEPTION_DATE\",\"date\":\"2026-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conception_date").value("2026-01-01"))
                .andExpect(jsonPath("$.data.last_menstrual_period").value("2025-12-18"))
                .andExpect(jsonPath("$.data.estimated_due_date").value("2026-09-24"))
                .andExpect(jsonPath("$.data.calculation_source").value("CONCEPTION_DATE"));
        assertThat(pregnancies.existsByOwnerUserIdAndStatus(session.userId(), PregnancyStatus.ACTIVE))
                .isFalse();
    }

    @Test
    void futureDateAndInvalidManualDayReturnValidationErrors() throws Exception {
        Session session = registerViaOtp("0912390003", "Calculation Validation");

        mockMvc.perform(post("/api/v1/pregnancies/calculate")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"LMP\",\"date\":\"2026-09-20\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/pregnancies/calculate")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"MANUAL\",\"gestational_week\":20,\"gestational_day\":7}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.gestational_day").exists());
    }

    @Test
    void manualPregnancyAgeAdvancesOnReadUsingClock() throws Exception {
        Session session = registerViaOtp("0912390004", "Manual Pregnancy");

        String response = mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calculation_source\":\"MANUAL\",\"gestational_week\":20,\"gestational_day\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.gestational_week").value(20))
                .andExpect(jsonPath("$.data.gestational_day").value(3))
                .andExpect(jsonPath("$.data.calculation_source").value("MANUAL"))
                .andReturn().getResponse().getContentAsString();
        String pregnancyId = objectMapper.readTree(response).at("/data/id").stringValue();

        clock.advanceDays(5);

        mockMvc.perform(get("/api/v1/pregnancies/{id}", pregnancyId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gestational_week").value(21))
                .andExpect(jsonPath("$.data.gestational_day").value(1));
    }

    @Test
    void conflictingPreviewInputsAreRejected() throws Exception {
        Session session = registerViaOtp("0912390005", "Conflict Preview");

        mockMvc.perform(post("/api/v1/pregnancies/calculate")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"LMP\",\"date\":\"2026-09-01\","
                                + "\"estimated_due_date\":\"2027-01-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeTestConfig {
        @Bean
        @Primary
        MutableClock testPregnancyClock() {
            return new MutableClock(BASE_DATE);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(LocalDate date) {
            setDate(date);
        }

        void setDate(LocalDate date) {
            instant = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        void advanceDays(long days) {
            instant = instant.plus(days, ChronoUnit.DAYS);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
