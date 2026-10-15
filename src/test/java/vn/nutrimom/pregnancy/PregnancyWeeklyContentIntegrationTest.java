package vn.nutrimom.pregnancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.pregnancy.domain.PregnancyWeekContentEntity;
import vn.nutrimom.pregnancy.repository.PregnancyWeekContentRepository;

/** Synthetic fixtures only. The fault test uses its own H2 database, never the shared cloud DB. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pregnancy_weekly_contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS app",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PregnancyIntegrationTest.FixedTimeConfig.class)
class PregnancyWeeklyContentIntegrationTest {
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
    private static final ZoneId PREGNANCY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final OffsetDateTime REVIEWED_AT = OffsetDateTime.parse("2026-09-20T08:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PregnancyWeekContentRepository contents;
    @Autowired UserRepository users;
    @Autowired Clock clock;
    @Autowired DataSource dataSource;
    private String userId;

    @BeforeEach
    void prepareIsolatedFixtures() {
        contents.deleteAll();
        UserEntity user = new UserEntity();
        user.setPhone("092780%04d".formatted(PHONE_SEQUENCE.incrementAndGet()));
        user.setDisplayName("Weekly content test fixture");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        userId = users.saveAndFlush(user).getId();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20, 40, 42})
    void reviewedWeeksReturnContentAndReviewMetadata(int week) throws Exception {
        contents.saveAndFlush(reviewedFixture(week));

        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/{week}", week).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.week").value(week))
                .andExpect(jsonPath("$.data.title").isNotEmpty())
                .andExpect(jsonPath("$.data.summary").isNotEmpty())
                .andExpect(jsonPath("$.data.baby_development").isNotEmpty())
                .andExpect(jsonPath("$.data.mother_changes").isNotEmpty())
                .andExpect(jsonPath("$.data.care_tips").isNotEmpty())
                .andExpect(jsonPath("$.data.warning_signs").isNotEmpty())
                .andExpect(jsonPath("$.data.sources").isString())
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty())
                .andExpect(jsonPath("$.data.created_at").isNotEmpty())
                .andExpect(jsonPath("$.data.updated_at").isNotEmpty())
                .andExpect(jsonPath("$.data.development_summary").value("Test summary for week " + week))
                .andExpect(jsonPath("$.data.maternal_changes[0]").value("Test mother changes"))
                .andExpect(jsonPath("$.data.care_topics[0]").value("Test care tips"))
                .andExpect(jsonPath("$.data.reviewed_by").value("Integration fixture reviewer"))
                .andExpect(jsonPath("$.data.reviewed_at").value(REVIEWED_AT.toInstant().toString()))
                .andExpect(jsonPath("$.data.content_version").value(2))
                .andExpect(jsonPath("$.data.next_review_at").doesNotExist())
                .andExpect(jsonPath("$.data.baby").doesNotExist());
    }

    @Test
    void unreviewedContentWithNoReviewMetadataReturns409() throws Exception {
        contents.saveAndFlush(unreviewedFixture(20));

        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/20").with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_NOT_REVIEWED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reviewed_by", "reviewed_at", "summary", "care_tips", "sources"})
    void reviewedLabelWithMissingRequiredDataIsNotPublished(String missingField) throws Exception {
        contents.saveAndFlush(reviewedWithout(20, missingField));

        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/20").with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_NOT_REVIEWED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void dashboardReturnsNullForReviewedLabelWithoutReviewer() throws Exception {
        createPregnancy(20, 0);
        contents.saveAndFlush(reviewedWithout(20, "reviewed_by"));

        mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pregnancy_summary.gestational_week").value(20))
                .andExpect(jsonPath("$.data.baby_summary").value(nullValue()));
    }

    @Test
    void missingContentReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/20").with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 43})
    void unsupportedWeeksReturn422(int week) throws Exception {
        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/{week}", week).with(userJwt()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void weeklyContentStillRequiresAuthentication() throws Exception {
        contents.saveAndFlush(reviewedFixture(20));

        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/20"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void approvedOptionalMeasurementsAndNextReviewKeepTheirWireFormat() throws Exception {
        PregnancyWeekContentEntity content = reviewedFixture(20);
        content.setBabyLengthCmMin(new BigDecimal("1.25"));
        content.setBabyLengthCmMax(new BigDecimal("2.50"));
        content.setBabyWeightGMin(1);
        content.setBabyWeightGMax(2);
        content.setComparisonLabel("TEST ONLY");
        content.setNextReviewAt(REVIEWED_AT.plusMonths(6));
        contents.saveAndFlush(content);

        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/20").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baby.length_cm_range[0]").value(1.25))
                .andExpect(jsonPath("$.data.baby.length_cm_range[1]").value(2.5))
                .andExpect(jsonPath("$.data.baby.weight_g_range[0]").value(1))
                .andExpect(jsonPath("$.data.baby.weight_g_range[1]").value(2))
                .andExpect(jsonPath("$.data.baby.weight_grange[0]").value(1))
                .andExpect(jsonPath("$.data.baby.weight_grange[1]").value(2))
                .andExpect(jsonPath("$.data.baby.comparison_label").value("TEST ONLY"))
                .andExpect(jsonPath("$.data.next_review_at").value(REVIEWED_AT.plusMonths(6).toInstant().toString()));
    }

    @Test
    void incompleteOptionalRangeDoesNotInventTheMissingValue() throws Exception {
        PregnancyWeekContentEntity content = reviewedFixture(20);
        content.setBabyLengthCmMin(BigDecimal.ONE);
        content.setComparisonLabel("TEST ONLY");
        contents.saveAndFlush(content);

        mockMvc.perform(get("/api/v1/pregnancy-content/weeks/20").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baby.length_cm_range").doesNotExist())
                .andExpect(jsonPath("$.data.baby.weight_g_range").doesNotExist())
                .andExpect(jsonPath("$.data.baby.comparison_label").value("TEST ONLY"));
    }

    @Test
    void dashboardUsesTheSameReviewedWeekWithoutChangingPregnancyCalculation() throws Exception {
        LocalDate dueDate = createPregnancy(20, 3);
        contents.saveAndFlush(reviewedFixture(20));
        JsonNode weekly = responseData(mockMvc.perform(get("/api/v1/pregnancy-content/weeks/20")
                        .with(userJwt())).andExpect(status().isOk()).andReturn());

        mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile_summary.role").value("MOM"))
                .andExpect(jsonPath("$.data.pregnancy_summary.gestational_week").value(20))
                .andExpect(jsonPath("$.data.pregnancy_summary.gestational_day").value(3))
                .andExpect(jsonPath("$.data.pregnancy_summary.trimester").value(2))
                .andExpect(jsonPath("$.data.pregnancy_summary.estimated_due_date").value(dueDate.toString()))
                .andExpect(jsonPath("$.data.pregnancy_summary.days_until_due").value(137))
                .andExpect(jsonPath("$.data.baby_summary.week").value(20))
                .andExpect(jsonPath("$.data.baby_summary.title").value(weekly.at("/title").stringValue()))
                .andExpect(jsonPath("$.data.baby_summary.summary").value(weekly.at("/summary").stringValue()))
                .andExpect(jsonPath("$.data.baby_summary.baby_development").value(weekly.at("/baby_development").stringValue()))
                .andExpect(jsonPath("$.data.baby_summary.disclaimer").value(weekly.at("/disclaimer").stringValue()))
                .andExpect(jsonPath("$.data.subscription.plan_id").value("FREE"));
    }

    @Test
    void dashboardReturnsNullForUnreviewedContent() throws Exception {
        createPregnancy(20, 0);
        contents.saveAndFlush(unreviewedFixture(20));

        mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pregnancy_summary.gestational_week").value(20))
                .andExpect(jsonPath("$.data.baby_summary").value(nullValue()));
    }

    @Test
    void dashboardReturnsNullForMissingContent() throws Exception {
        createPregnancy(20, 0);

        mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baby_summary").value(nullValue()));
    }

    @Test
    void dashboardDoesNotClampPregnancyBeyondWeek42() throws Exception {
        createPregnancy(43, 0);
        contents.saveAndFlush(reviewedFixture(42));

        mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pregnancy_summary.gestational_week").value(43))
                .andExpect(jsonPath("$.data.pregnancy_summary.days_until_due").value(-21))
                .andExpect(jsonPath("$.data.baby_summary").value(nullValue()));
    }

    @Test
    void dashboardReturns200WhenWeeklyQueryFails() throws Exception {
        createPregnancy(20, 0);
        contents.saveAndFlush(reviewedFixture(20));
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .startsWith("jdbc:h2:mem:pregnancy_weekly_contract");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE app.pregnancy_week_contents RENAME TO pregnancy_week_contents_unavailable");
        try {
            mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pregnancy_summary.gestational_week").value(20))
                    .andExpect(jsonPath("$.data.baby_summary").value(nullValue()))
                    .andExpect(jsonPath("$.data.subscription.plan_id").value("FREE"));
        } finally {
            jdbc.execute("ALTER TABLE app.pregnancy_week_contents_unavailable RENAME TO pregnancy_week_contents");
        }
    }

    private LocalDate createPregnancy(int week, int day) throws Exception {
        LocalDate dueDate = LocalDate.now(clock.withZone(PREGNANCY_ZONE)).plusDays(280L - week * 7L - day);
        mockMvc.perform(post("/api/v1/pregnancies").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimated_due_date\":\"%s\",\"calculation_source\":\"EDD\",\"timezone\":\"Asia/Ho_Chi_Minh\"}".formatted(dueDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.gestational_week").value(week))
                .andExpect(jsonPath("$.data.gestational_day").value(day));
        return dueDate;
    }

    private PregnancyWeekContentEntity reviewedFixture(int week) {
        PregnancyWeekContentEntity content = new PregnancyWeekContentEntity();
        content.setWeek(week);
        content.setTitle("Test weekly title " + week);
        content.setSummary("Test summary for week " + week);
        content.setBabyDevelopment("Test baby development");
        content.setMotherChanges("Test mother changes");
        content.setCareTips("Test care tips");
        content.setWarningSigns("Test warning signs");
        content.setSources("[{\"name\":\"Test fixture\",\"url\":\"https://example.invalid/weekly\"}]");
        content.setDisclaimer("Synthetic fixture used only in automated tests; not medical guidance.");
        content.setReviewStatus("REVIEWED");
        content.setReviewedBy("Integration fixture reviewer");
        content.setReviewedAt(REVIEWED_AT);
        content.setContentVersion(2);
        return content;
    }

    /** REVIEWED label kept, but one required value blanked (whitespace for NOT NULL text columns). */
    private PregnancyWeekContentEntity reviewedWithout(int week, String field) {
        PregnancyWeekContentEntity content = reviewedFixture(week);
        switch (field) {
            case "reviewed_by" -> content.setReviewedBy("  ");
            case "reviewed_at" -> content.setReviewedAt(null);
            case "summary" -> content.setSummary(" ");
            case "care_tips" -> content.setCareTips("");
            case "sources" -> content.setSources(" ");
            default -> throw new IllegalArgumentException(field);
        }
        return content;
    }

    private PregnancyWeekContentEntity unreviewedFixture(int week) {
        PregnancyWeekContentEntity content = reviewedFixture(week);
        content.setReviewStatus("UNREVIEWED");
        content.setReviewedBy(null);
        content.setReviewedAt(null);
        return content;
    }

    private RequestPostProcessor userJwt() {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }
}
