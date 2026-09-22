package vn.nutrimom.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.Gender;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;

@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminUserIntegrationTest.TimeTestConfig.class)
@Transactional
class AdminUserIntegrationTest {
    private static final Instant SEPTEMBER_NOW = Instant.parse("2026-09-30T16:59:59.999999Z");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(SEPTEMBER_NOW);
    }

    @Test
    void endpointsRequireAdminRole() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/users").with(userJwt()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/users").with(adminJwt()))
                .andExpect(status().isOk());
    }

    @Test
    void listUsesOneBasedDatabasePaginationStableSortingAndRoleArrays() throws Exception {
        createUser("Alice", "+84900001001", "alice@example.com", UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER, UserRole.EXPERT),
                at("2026-01-01T00:00:00Z"));
        createUser("Bob", "+84900001002", null, UserStatus.LOCKED,
                OnboardingStatus.PROFILE_REQUIRED, Set.of(UserRole.USER),
                at("2026-02-01T00:00:00Z"));
        createUser("Carol", "+84900001003", "carol@example.com", UserStatus.DISABLED,
                OnboardingStatus.CONTEXT_REQUIRED, Set.of(UserRole.USER),
                at("2026-03-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/admin/users").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(20))
                .andExpect(jsonPath("$.data.total_items").value(3))
                .andExpect(jsonPath("$.data.total_pages").value(1))
                .andExpect(jsonPath("$.data.items[*].display_name", contains("Carol", "Bob", "Alice")))
                .andExpect(jsonPath("$.data.items[2].roles", containsInAnyOrder("USER", "EXPERT")))
                .andExpect(jsonPath("$.data.items[1].email", nullValue()))
                .andExpect(jsonPath("$.data.items[1].avatar_key", nullValue()))
                .andExpect(jsonPath("$.data.items[0].password_hash").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].last_active_at").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("page", "2")
                        .param("pageSize", "2")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].display_name").value("Alice"));
    }

    @Test
    void listSearchesCaseInsensitivelyTrimsAndCombinesFilters() throws Exception {
        createUser("Nguyen Alice", "+84911112222", "Alice@Example.COM", UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER, UserRole.EXPERT),
                at("2026-03-01T00:00:00Z"));
        createUser("Bob", "+84933334444", "bob@example.com", UserStatus.LOCKED,
                OnboardingStatus.PROFILE_REQUIRED, Set.of(UserRole.USER),
                at("2026-04-01T00:00:00Z"));

        assertSingleSearchResult("  nGuYeN aLiCe  ", "Nguyen Alice");
        assertSingleSearchResult("911112222", "Nguyen Alice");
        assertSingleSearchResult("ALICE@EXAMPLE.COM", "Nguyen Alice");

        assertSingleFilterResult("status", "LOCKED", "Bob");
        assertSingleFilterResult("role", "EXPERT", "Nguyen Alice");
        assertSingleFilterResult("onboardingStatus", "PROFILE_REQUIRED", "Bob");

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("status", "ACTIVE")
                        .param("role", "EXPERT")
                        .param("onboardingStatus", "COMPLETED")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].display_name").value("Nguyen Alice"));
    }

    @Test
    void listValidatesPaginationEnumsAndSortWhitelist() throws Exception {
        assertValidationError("page", "0");
        assertValidationError("pageSize", "0");
        assertValidationError("pageSize", "101");
        assertValidationError("sortBy", "passwordHash");
        assertValidationError("sortDirection", "sideways");

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("status", "PENDING")
                        .with(adminJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void listSupportsAscendingSortWithoutDuplicateRowsAndWithoutNPlusOne() throws Exception {
        createUser("Zulu", "+84900002001", null, UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER),
                at("2026-01-01T00:00:00Z"));
        createUser("Alpha", "+84900002002", null, UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER, UserRole.ADMIN),
                at("2026-01-02T00:00:00Z"));
        createUser("Mike", "+84900002003", null, UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER),
                at("2026-01-03T00:00:00Z"));

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().clear();

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("pageSize", "2")
                        .param("sortBy", "displayName")
                        .param("sortDirection", "asc")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(3))
                .andExpect(jsonPath("$.data.total_pages").value(2))
                .andExpect(jsonPath("$.data.items[*].display_name", contains("Alpha", "Mike")));

        assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    @Test
    void detailReturnsOnlyAccountFieldsAndUsesStandardErrors() throws Exception {
        UserEntity user = createUser("Detail User", "+84900003001", "detail@example.com",
                UserStatus.ACTIVE, OnboardingStatus.COMPLETED,
                Set.of(UserRole.USER, UserRole.EXPERT), at("2026-05-20T00:00:00Z"));
        user = users.findById(user.getId()).orElseThrow();
        user.setGender(Gender.FEMALE);
        user.setDateOfBirth(LocalDate.of(1998, 5, 20));
        user.setTermsAcceptedAt(at("2026-01-01T00:00:00Z"));
        user.setPrivacyAcceptedAt(at("2026-01-01T00:00:00Z"));
        users.saveAndFlush(user);
        entityManager.clear();

        mockMvc.perform(get("/api/v1/admin/users/{id}", user.getId()).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId()))
                .andExpect(jsonPath("$.data.gender").value("FEMALE"))
                .andExpect(jsonPath("$.data.date_of_birth").value("1998-05-20"))
                .andExpect(jsonPath("$.data.roles", containsInAnyOrder("USER", "EXPERT")))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.onboarding_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.created_at").exists())
                .andExpect(jsonPath("$.data.updated_at").exists())
                .andExpect(jsonPath("$.data.password_hash").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.pregnancy").doesNotExist())
                .andExpect(jsonPath("$.data.medical_records").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/users/{id}", UUID.randomUUID()).with(adminJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/users/not-a-uuid").with(adminJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_PARAMETER"));
        mockMvc.perform(get("/api/v1/admin/users/{id}", user.getId()).with(userJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/users/{id}", user.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void summaryUsesVietnamBoundariesAndBuildsTwelveMonthProgress() throws Exception {
        createUser("Before year", "+84900004001", null, UserStatus.DISABLED,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER),
                at("2025-12-31T16:59:59.999999Z"));
        createUser("January", "+84900004002", null, UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER),
                at("2025-12-31T17:00:00Z"));
        createUser("Before September", "+84900004003", null, UserStatus.LOCKED,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER),
                at("2026-08-31T16:59:59.999999Z"));
        createUser("September start", "+84900004004", null, UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER),
                at("2026-08-31T17:00:00Z"));
        createUser("September end", "+84900004005", null, UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER),
                at("2026-09-30T16:59:59.999999Z"));
        createUser("October start", "+84900004006", null, UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER),
                at("2026-09-30T17:00:00Z"));

        mockMvc.perform(get("/api/v1/admin/users/summary").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_users").value(6))
                .andExpect(jsonPath("$.data.active_users").value(4))
                .andExpect(jsonPath("$.data.new_users_this_month").value(2))
                .andExpect(jsonPath("$.data.current_period.month").value(9))
                .andExpect(jsonPath("$.data.current_period.year").value(2026))
                .andExpect(jsonPath("$.data.current_period.label").value("9/2026"))
                .andExpect(jsonPath("$.data.current_period.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.data.monthly_progress.length()").value(12))
                .andExpect(jsonPath("$.data.monthly_progress[*].month",
                        contains(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
                .andExpect(jsonPath("$.data.monthly_progress[0].month").value(1))
                .andExpect(jsonPath("$.data.monthly_progress[0].new_users").value(1))
                .andExpect(jsonPath("$.data.monthly_progress[0].cumulative_users").value(1))
                .andExpect(jsonPath("$.data.monthly_progress[0].percentage").value(25.0))
                .andExpect(jsonPath("$.data.monthly_progress[8].month").value(9))
                .andExpect(jsonPath("$.data.monthly_progress[8].new_users").value(2))
                .andExpect(jsonPath("$.data.monthly_progress[8].cumulative_users").value(4))
                .andExpect(jsonPath("$.data.monthly_progress[8].percentage").value(100.0))
                .andExpect(jsonPath("$.data.monthly_progress[8].future").value(false))
                .andExpect(jsonPath("$.data.monthly_progress[9].future").value(true))
                .andExpect(jsonPath("$.data.monthly_progress[9].percentage", nullValue()));
    }

    @Test
    void summaryPeriodChangesAcrossMonthAndYearAndHandlesEmptyYtd() throws Exception {
        createUser("Old user", "+84900005001", null, UserStatus.ACTIVE,
                OnboardingStatus.COMPLETED, Set.of(UserRole.USER),
                at("2025-06-01T00:00:00Z"));

        clock.set(Instant.parse("2026-10-01T00:00:00Z"));
        mockMvc.perform(get("/api/v1/admin/users/summary").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_period.month").value(10))
                .andExpect(jsonPath("$.data.current_period.label").value("10/2026"))
                .andExpect(jsonPath("$.data.monthly_progress[9].percentage").value(0.0))
                .andExpect(jsonPath("$.data.monthly_progress[10].percentage", nullValue()));

        clock.set(Instant.parse("2026-12-31T17:00:00Z"));
        mockMvc.perform(get("/api/v1/admin/users/summary").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_period.month").value(1))
                .andExpect(jsonPath("$.data.current_period.year").value(2027))
                .andExpect(jsonPath("$.data.current_period.label").value("1/2027"))
                .andExpect(jsonPath("$.data.new_users_this_month").value(0))
                .andExpect(jsonPath("$.data.monthly_progress[0].percentage").value(0.0))
                .andExpect(jsonPath("$.data.monthly_progress[1].percentage", nullValue()));
    }

    @Test
    void openApiPublishesAllAdminUserOperationsUnderTheirOwnTag() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].get.tags[0]")
                        .value("Admin Users"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/users/{userId}'].get.tags[0]")
                        .value("Admin Users"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/users/summary'].get.tags[0]")
                        .value("Admin Users"));
    }

    private void assertSingleSearchResult(String query, String displayName) throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").param("q", query).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].display_name").value(displayName));
    }

    private void assertSingleFilterResult(String parameter, String value, String displayName)
            throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .param(parameter, value)
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].display_name").value(displayName));
    }

    private void assertValidationError(String parameter, String value) throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .param(parameter, value)
                        .with(adminJwt()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private UserEntity createUser(
            String displayName,
            String phone,
            String email,
            UserStatus status,
            OnboardingStatus onboardingStatus,
            Set<UserRole> roles,
            OffsetDateTime createdAt) {
        UserEntity user = new UserEntity();
        user.setDisplayName(displayName);
        user.setPhone(phone);
        user.setEmail(email);
        user.setStatus(status);
        user.setOnboardingStatus(onboardingStatus);
        user.setRoles(roles);
        user = users.saveAndFlush(user);
        jdbc.update("update app.users set created_at = ?, updated_at = ? where id = ?",
                createdAt, createdAt, user.getId());
        entityManager.clear();
        return user;
    }

    private OffsetDateTime at(String value) {
        return OffsetDateTime.parse(value);
    }

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString())
                        .claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor userJwt() {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString())
                        .claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeTestConfig {
        @Bean
        @Primary
        MutableClock adminTestClock() {
            return new MutableClock(SEPTEMBER_NOW);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initialInstant) {
            this.instant = new AtomicReference<>(initialInstant);
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
