package vn.nutrimom.consultation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.consultation.domain.AssignmentType;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationReviewEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.SlotStatus;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminConsultationIntegrationTest {
    private static final String ENDPOINT = "/api/v1/admin/consultation-requests";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired ExpertProfileRepository experts;
    @Autowired ConsultationRequestRepository requests;
    @Autowired ConsultationReviewRepository reviews;
    @Autowired AvailabilitySlotRepository slots;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @Test
    void endpointRequiresAdminRole() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ENDPOINT).with(userJwt())).andExpect(status().isForbidden());
        mockMvc.perform(get(ENDPOINT).with(expertJwt())).andExpect(status().isForbidden());
        mockMvc.perform(get(ENDPOINT).with(adminJwt())).andExpect(status().isOk());
    }

    @Test
    void returnsOnlyCompletedEvenWhenStatusQueryIsCrafted() throws Exception {
        String userId = createUser("Status User", "+84910000001", Set.of(UserRole.USER));
        String expertId = createExpert("Status Expert", "+84910000002");
        createRequest(userId, expertId, ConsultationStatus.COMPLETED, "completed");
        createRequest(userId, null, ConsultationStatus.PENDING_EXPERT, "pending expert");
        createRequest(userId, expertId, ConsultationStatus.PENDING_CONSULTATION,
                "pending consultation");
        createRequest(userId, expertId, ConsultationStatus.CANCELLED, "cancelled");

        assertOnlyCompleted(null);
        assertOnlyCompleted("PENDING_EXPERT");
        assertOnlyCompleted("PENDING_CONSULTATION");
        assertOnlyCompleted("CANCELLED");
    }

    @Test
    void searchesNamesCaseInsensitivelyAndPaginatesNewestFirst() throws Exception {
        String alice = createUser("Alice Mother", "+84910000003", Set.of(UserRole.USER));
        String bob = createUser("Bob Mother", "+84910000004", Set.of(UserRole.USER));
        String expertId = createExpert("Doctor Nguyen", "+84910000005");
        ConsultationRequestEntity older = createRequest(
                alice, expertId, ConsultationStatus.COMPLETED, "old private note");
        ConsultationRequestEntity newer = createRequest(
                bob, expertId, ConsultationStatus.COMPLETED, "new private note");
        setCreatedAt(older.getId(), "2026-01-01T00:00:00Z");
        setCreatedAt(newer.getId(), "2026-02-01T00:00:00Z");

        mockMvc.perform(get(ENDPOINT).param("q", "  aLiCe  ").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].user_display_name").value("Alice Mother"));
        mockMvc.perform(get(ENDPOINT).param("q", "dOcToR nGuYeN").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(2));
        mockMvc.perform(get(ENDPOINT).param("page", "1").param("pageSize", "1")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(1))
                .andExpect(jsonPath("$.data.total_items").value(2))
                .andExpect(jsonPath("$.data.total_pages").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(newer.getId()));
        mockMvc.perform(get(ENDPOINT).param("page", "2").param("pageSize", "1")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(older.getId()));

        assertInvalidPageSize("0");
        assertInvalidPageSize("101");
    }

    @Test
    void mapsReviewAndSlotWithoutExposingPrivateNote() throws Exception {
        String userId = createUser("Review User", "+84910000006", Set.of(UserRole.USER));
        String expertId = createExpert("Review Expert", "+84910000007");
        AvailabilitySlotEntity slot = createSlot(expertId);
        ConsultationRequestEntity reviewed = createRequest(
                userId, expertId, ConsultationStatus.COMPLETED, "secret consultation note");
        reviewed.setSlotId(slot.getId());
        requests.saveAndFlush(reviewed);
        createReview(reviewed.getId(), userId, expertId);
        ConsultationRequestEntity withoutReview = createRequest(
                userId, expertId, ConsultationStatus.COMPLETED, "another secret note");

        mockMvc.perform(get(ENDPOINT).param("q", "Review User").param("pageSize", "10")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[*].status", everyItem(org.hamcrest.Matchers.is("COMPLETED"))))
                .andExpect(jsonPath("$.data.items[*].note").doesNotExist())
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')].review.rating"
                        .formatted(reviewed.getId())).value(5))
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')].review.comment"
                        .formatted(reviewed.getId())).value("Very helpful"))
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')].slot.slot_date"
                        .formatted(reviewed.getId())).value("2026-01-15"))
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')].review"
                        .formatted(withoutReview.getId())).value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void openApiDocumentsCompletedOnlyContractAndOmitsStatusParameter() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['%s'].get.description".formatted(ENDPOINT),
                        org.hamcrest.Matchers.containsString("only consultations completed")))
                .andExpect(jsonPath("$.paths['%s'].get.parameters[*].name".formatted(ENDPOINT),
                        not(hasItem("status"))));
    }

    private void assertOnlyCompleted(String craftedStatus) throws Exception {
        var request = get(ENDPOINT).with(adminJwt());
        if (craftedStatus != null) {
            request.param("status", craftedStatus);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[*].status", contains("COMPLETED")));
    }

    private void assertInvalidPageSize(String value) throws Exception {
        mockMvc.perform(get(ENDPOINT).param("pageSize", value).with(adminJwt()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private String createUser(String displayName, String phone, Set<UserRole> roles) {
        UserEntity user = new UserEntity();
        user.setDisplayName(displayName);
        user.setPhone(phone);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(roles);
        return users.saveAndFlush(user).getId();
    }

    private String createExpert(String fullName, String phone) {
        String userId = createUser(fullName, phone, Set.of(UserRole.EXPERT));
        ExpertProfileEntity expert = new ExpertProfileEntity();
        expert.setUserId(userId);
        expert.setFullName(fullName);
        expert.setSpecialty(Specialty.HEALTH);
        expert.setYearsOfExperience(5);
        expert.setStatus(ExpertStatus.ACTIVE);
        experts.saveAndFlush(expert);
        return userId;
    }

    private ConsultationRequestEntity createRequest(
            String userId, String expertId, ConsultationStatus status, String note) {
        ConsultationRequestEntity request = new ConsultationRequestEntity();
        request.setUserId(userId);
        request.setExpertUserId(expertId);
        request.setSpecialty(Specialty.HEALTH);
        request.setAssignmentType(expertId == null ? AssignmentType.RANDOM : AssignmentType.DIRECT);
        request.setStatus(status);
        request.setNote(note);
        if (status == ConsultationStatus.COMPLETED) {
            request.setCompletedAt(OffsetDateTime.parse("2026-01-15T03:00:00Z"));
        }
        return requests.saveAndFlush(request);
    }

    private AvailabilitySlotEntity createSlot(String expertId) {
        AvailabilitySlotEntity slot = new AvailabilitySlotEntity();
        slot.setExpertUserId(expertId);
        slot.setSlotDate(LocalDate.of(2026, 1, 15));
        slot.setStartTime(LocalTime.of(10, 0));
        slot.setEndTime(LocalTime.of(10, 30));
        slot.setStatus(SlotStatus.BOOKED);
        return slots.saveAndFlush(slot);
    }

    private void createReview(String requestId, String userId, String expertId) {
        ConsultationReviewEntity review = new ConsultationReviewEntity();
        review.setRequestId(requestId);
        review.setUserId(userId);
        review.setExpertUserId(expertId);
        review.setRating((short) 5);
        review.setComment("Very helpful");
        reviews.saveAndFlush(review);
    }

    private void setCreatedAt(String requestId, String value) {
        OffsetDateTime timestamp = OffsetDateTime.parse(value);
        jdbc.update("update app.consultation_requests set created_at = ?, updated_at = ? where id = ?",
                timestamp, timestamp, requestId);
        entityManager.clear();
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

    private RequestPostProcessor expertJwt() {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString())
                        .claim("roles", List.of("EXPERT")))
                .authorities(new SimpleGrantedAuthority("ROLE_EXPERT"));
    }
}
