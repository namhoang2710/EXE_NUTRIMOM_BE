package vn.nutrimom.pregnancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.pregnancy.domain.PregnancyWeekContentEntity;
import vn.nutrimom.pregnancy.repository.PregnancyWeekContentRepository;

/**
 * Proves the dashboard survives a real PostgreSQL error in the weekly-content read.
 *
 * <p>H2 cannot show this: on PostgreSQL a failed statement aborts the whole transaction, so the
 * weekly read must run in its own transaction. Runs only when {@code NUTRIMOM_PG_TEST_URL} points
 * at a disposable local database; Flyway migrates it from scratch. Never point it at a shared DB.
 */
@EnabledIfEnvironmentVariable(named = "NUTRIMOM_PG_TEST_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1)[:/].*")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PregnancyIntegrationTest.FixedTimeConfig.class)
class PregnancyWeeklyContentPostgresFallbackTest {
    private static final ZoneId PREGNANCY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("NUTRIMOM_PG_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("NUTRIMOM_PG_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("NUTRIMOM_PG_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.user", () -> System.getenv("NUTRIMOM_PG_TEST_USER"));
        registry.add("spring.flyway.password", () -> System.getenv("NUTRIMOM_PG_TEST_PASSWORD"));
    }

    @Autowired MockMvc mockMvc;
    @Autowired PregnancyWeekContentRepository contents;
    @Autowired UserRepository users;
    @Autowired Clock clock;
    @Autowired DataSource dataSource;
    private String userId;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getURL()).doesNotContain("supabase");
        }
        contents.deleteAll();
        UserEntity user = new UserEntity();
        user.setPhone("09" + String.format("%08d", Math.floorMod(UUID.randomUUID().hashCode(), 100_000_000)));
        user.setDisplayName("Weekly content PostgreSQL fixture");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        userId = users.saveAndFlush(user).getId();
    }

    @Test
    void dashboardReturns200WhenWeeklyQueryFailsOnPostgres() throws Exception {
        createPregnancy(20);
        contents.saveAndFlush(reviewedFixture(20));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("ALTER TABLE app.pregnancy_week_contents RENAME TO pregnancy_week_contents_unavailable");
        try {
            // Control: the weekly endpoint itself now hits a real PostgreSQL error.
            mockMvc.perform(get("/api/v1/pregnancy-content/weeks/20").with(userJwt()))
                    .andExpect(status().is5xxServerError());

            mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pregnancy_summary.gestational_week").value(20))
                    .andExpect(jsonPath("$.data.baby_summary").value(nullValue()))
                    .andExpect(jsonPath("$.data.subscription.plan_id").value("FREE"));
        } finally {
            jdbc.execute("ALTER TABLE app.pregnancy_week_contents_unavailable RENAME TO pregnancy_week_contents");
        }

        mockMvc.perform(get("/api/v1/dashboard/mom").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baby_summary.week").value(20));
    }

    private void createPregnancy(int week) throws Exception {
        LocalDate dueDate = LocalDate.now(clock.withZone(PREGNANCY_ZONE)).plusDays(280L - week * 7L);
        mockMvc.perform(post("/api/v1/pregnancies").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimated_due_date\":\"%s\",\"calculation_source\":\"EDD\",\"timezone\":\"Asia/Ho_Chi_Minh\"}".formatted(dueDate)))
                .andExpect(status().isCreated());
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
        content.setReviewedAt(OffsetDateTime.parse("2026-09-20T08:00:00Z"));
        content.setContentVersion(2);
        return content;
    }

    private RequestPostProcessor userJwt() {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
