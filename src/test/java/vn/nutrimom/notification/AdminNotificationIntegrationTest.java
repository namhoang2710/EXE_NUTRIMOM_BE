package vn.nutrimom.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.dto.ActivateAccountRequest;
import vn.nutrimom.auth.dto.LoginRequest;
import vn.nutrimom.auth.dto.RegisterRequest;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.auth.service.AuthService;
import vn.nutrimom.auth.service.TokenService;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.contact.domain.ContactTopic;
import vn.nutrimom.contact.dto.ContactDtos.CreateContactRequest;
import vn.nutrimom.contact.service.AdminContactService;
import vn.nutrimom.contact.service.ContactRequestService;
import vn.nutrimom.notification.domain.AdminNotificationEvent;
import vn.nutrimom.notification.domain.NotificationAudience;
import vn.nutrimom.notification.domain.NotificationCategory;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.repository.NotificationRepository;
import vn.nutrimom.notification.service.AdminNotificationDispatcher;
import vn.nutrimom.notification.service.AdminNotificationEvents;
import vn.nutrimom.notification.service.NotificationService;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PaymentSource;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.payment.repository.PaymentOrderRepository;
import vn.nutrimom.payment.service.PaymentService;
import vn.nutrimom.user.dto.DeleteAccountRequest;
import vn.nutrimom.user.service.UserAccountService;

/** Committed fixtures exercise the real dispatcher transactions, API authorization and producers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@MockitoBean(types = {vn.nutrimom.assistant.service.GroqAssistantClient.class,
        vn.nutrimom.consultation.video.LiveKitClient.class, vn.nutrimom.common.email.EmailService.class})
class AdminNotificationIntegrationTest {
    private static final AtomicLong SEQUENCE = new AtomicLong(3100000);
    private static final String PASSWORD = "StrongTest!123";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationService inbox;
    @Autowired AdminNotificationEvents events;
    @Autowired AdminNotificationDispatcher dispatcher;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired TokenService tokens;
    @Autowired PasswordEncoder encoder;
    @Autowired UserAccountService accounts;
    @Autowired ContactRequestService contacts;
    @Autowired AdminContactService adminContacts;
    @Autowired PaymentOrderRepository orders;
    @Autowired PaymentService payments;
    @Autowired Clock clock;
    @Autowired vn.nutrimom.auth.service.OtpService otp;
    @Autowired vn.nutrimom.auth.service.MagicLinkService magic;
    @Autowired vn.nutrimom.auth.repository.MagicLoginTokenRepository magicTokens;
    @Autowired vn.nutrimom.consultation.service.ConsultationRequestService consultations;
    @Autowired vn.nutrimom.consultation.repository.ExpertProfileRepository experts;
    private final List<String> owners = new ArrayList<>();
    private final List<String> sources = new ArrayList<>();
    private TransactionTemplate tx;
    private String admin;
    private String otherAdmin;
    private String customer;
    private String expert;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        admin = account(UserRole.ADMIN, UserStatus.ACTIVE);
        otherAdmin = account(UserRole.ADMIN, UserStatus.ACTIVE);
        customer = account(UserRole.USER, UserStatus.ACTIVE);
        expert = account(UserRole.EXPERT, UserStatus.ACTIVE);
    }

    @AfterEach void cleanup() {
        tx.executeWithoutResult(status -> {
            for (String source : sources) jdbc.update("delete from app.admin_notification_outbox where source_id=?", source);
            for (String owner : owners) {
                jdbc.update("delete from app.admin_notification_outbox where source_id=?", owner);
                jdbc.update("delete from app.notifications where user_id=?", owner);
                jdbc.update("delete from app.activity_events where subject_user_id=? or actor_user_id=?", owner, owner);
                jdbc.update("delete from app.contact_requests where user_id=?", owner);
                jdbc.update("delete from app.consultation_requests where user_id=? or expert_user_id=?", owner, owner);
                jdbc.update("delete from app.consultation_slots where expert_user_id=?", owner);
                jdbc.update("delete from app.expert_profiles where user_id=?", owner);
                jdbc.update("delete from app.user_subscriptions where user_id=?", owner);
                jdbc.update("delete from app.payment_orders where user_id=?", owner);
                jdbc.update("delete from app.refresh_tokens where user_id=?", owner);
                users.findById(owner).map(UserEntity::getEmail).ifPresent(email ->
                        jdbc.update("delete from app.magic_login_tokens where email=?", email));
                jdbc.update("delete from app.otp_challenges where phone=(select phone from app.users where id=?)", owner);
            }
            for (String owner : owners) users.deleteById(owner);
        });
    }

    @Test void durableFanOutDeduplicatesAndKeepsEachAdminsReadStateIndependent() throws Exception {
        record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, customer);
        record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, customer);
        assertThat(count(admin)).isZero();
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(dispatcher.dispatchBatch()).isZero();
        assertThat(count(admin)).isEqualTo(1);
        assertThat(count(otherAdmin)).isEqualTo(1);
        assertThat(count(customer)).isZero();
        assertThat(count(expert)).isZero();
        String id = inbox.list(admin, null, 20, false).items().get(0).id();
        mvc.perform(get("/api/v1/notifications").with(adminJwt(admin)).param("audience", "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].audience").value("ADMIN"))
                .andExpect(jsonPath("$.data.items[0].category").value("ACCOUNT"))
                .andExpect(jsonPath("$.data.items[0].type").value("SYSTEM"));
        mvc.perform(post("/api/v1/notifications/{id}/read", id).with(adminJwt(otherAdmin)))
                .andExpect(status().isNotFound());
        inbox.markRead(admin, id);
        var firstRead = inbox.markRead(admin, id).readAt();
        assertThat(inbox.markRead(admin, id).readAt()).isEqualTo(firstRead);
        assertThat(count(otherAdmin)).isEqualTo(1);
    }

    @Test void rollbackDoesNotPublishAndWorkerRetriesDoNotResetReadState() {
        tx.executeWithoutResult(status -> {
            events.record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, customer);
            status.setRollbackOnly();
        });
        assertThat(dispatcher.dispatchBatch()).isZero();
        record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, customer);
        dispatcher.dispatchBatch();
        var row = inbox.list(admin, null, 20, false).items().get(0);
        var read = inbox.markRead(admin, row.id()).readAt();
        jdbc.update("update app.admin_notification_outbox set processed_at=null where source_id=?", customer);
        dispatcher.dispatchBatch();
        assertThat(inbox.list(admin, null, 20, false).items()).hasSize(1);
        assertThat(inbox.list(admin, null, 20, false).items().get(0).readAt()).isEqualTo(read);
    }

    @Test void revokedAdminCannotReadCountOrMarkOldAdminRowsEvenWithStaleAdminJwt() throws Exception {
        record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, customer);
        dispatcher.dispatchBatch();
        String id = inbox.list(admin, null, 20, false).items().get(0).id();
        inbox.publish(admin, NotificationType.SYSTEM, "Personal", "Safe", null, null, null);
        tx.executeWithoutResult(status -> {
            var revoked = users.findById(admin).orElseThrow();
            revoked.setRoles(Set.of(UserRole.USER)); users.saveAndFlush(revoked);
        });
        mvc.perform(get("/api/v1/notifications").with(adminJwt(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Personal"));
        mvc.perform(get("/api/v1/notifications/unread-count").with(adminJwt(admin)))
                .andExpect(jsonPath("$.data.count").value(1));
        mvc.perform(post("/api/v1/notifications/{id}/read", id).with(adminJwt(admin)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notifications/read-all").with(adminJwt(admin)))
                .andExpect(jsonPath("$.data.updated").value(1));
        assertThat(notifications.findById(id).orElseThrow().getReadAt()).isNull();
        for (String owner : List.of(admin, customer, expert)) {
            mvc.perform(get("/api/v1/notifications").with(adminJwt(owner)).param("audience", "ADMIN"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/notifications/unread-count").with(adminJwt(owner)).param("audience", "ADMIN"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/notifications/read-all").with(adminJwt(owner)).param("audience", "ADMIN"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test void categoryFiltersValidateInputsAndReadAllOnlyTouchesMatchingCategory() throws Exception {
        record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, customer);
        record(AdminNotificationEvent.ADMIN_APPOINTMENT_CREATED, customer);
        dispatcher.dispatchBatch();
        mvc.perform(get("/api/v1/notifications").with(adminJwt(admin)).param("category", "ACCOUNT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(post("/api/v1/notifications/read-all").with(adminJwt(admin))
                        .param("audience", "ADMIN").param("category", "ACCOUNT"))
                .andExpect(jsonPath("$.data.updated").value(1));
        assertThat(inbox.unreadCount(admin, NotificationAudience.ADMIN, NotificationCategory.APPOINTMENT)).isEqualTo(1);
        for (String parameter : List.of("audience", "category", "cursor")) {
            mvc.perform(get("/api/v1/notifications").with(adminJwt(admin)).param(parameter, "invalid"))
                    .andExpect(status().is(422));
        }
        mvc.perform(get("/api/v1/notifications").with(adminJwt(admin)).param("limit", "51"))
                .andExpect(status().is(422));
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }

    @Test void inactiveAdminsAreSkippedAndDatabaseEditsDoNotCreateEvents() {
        String inactive = account(UserRole.ADMIN, UserStatus.DISABLED);
        tx.executeWithoutResult(status -> {
            var u = users.findById(customer).orElseThrow();
            u.setStatus(UserStatus.DISABLED); users.saveAndFlush(u);
        });
        assertThat(dispatcher.dispatchBatch()).isZero();
        record(AdminNotificationEvent.ADMIN_ACCOUNT_DISABLED, customer);
        dispatcher.dispatchBatch();
        assertThat(count(inactive)).isZero();
        assertThat(count(admin)).isEqualTo(1);
    }

    @Test void adminCanFilterPersonalNotificationsWithoutReadingAdminEvents() {
        record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, customer); dispatcher.dispatchBatch();
        inbox.publish(admin, NotificationType.SYSTEM, "Personal", "Safe", null, null, null);
        assertThat(inbox.list(admin, null, 20, false, NotificationAudience.USER, null).items()).hasSize(1)
                .allSatisfy(row -> assertThat(row.title()).isEqualTo("Personal"));
        assertThat(inbox.markAllRead(admin, NotificationAudience.USER, null)).isEqualTo(1);
        assertThat(inbox.unreadCount(admin, NotificationAudience.ADMIN, null)).isEqualTo(1);
    }

    @Test void registrationActivationAndSelfDisableGenerateEventsButLoginAndBadCredentialsDoNot() {
        String phone = phone();
        var response = auth.register(new RegisterRequest(phone, null, PASSWORD, "QA Customer", true, null));
        String id = response.user().id(); owners.add(id);
        auth.login(new LoginRequest(phone, PASSWORD, null));
        assertThatThrownBy(() -> accounts.deleteAccount(id, new DeleteAccountRequest("QA", "wrong", null, null)))
                .isInstanceOf(BusinessException.class);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        accounts.deleteAccount(id, new DeleteAccountRequest("QA", PASSWORD, null, null));
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        String pending = account(UserRole.USER, UserStatus.PENDING_ACTIVATION);
        tx.executeWithoutResult(status -> {
            var u = users.findById(pending).orElseThrow(); u.setEmailActivationTokenHash(tokens.hash("012345"));
            u.setEmailActivationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)); users.saveAndFlush(u);
        });
        auth.activateAccount(new ActivateAccountRequest("012345", null));
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count(admin)).isEqualTo(3);
    }

    @Test void supportCreateCompleteAndCancelGenerateAdminEventsWithoutLeakingMessageBody() {
        var topic = ContactTopic.values()[0];
        var first = contacts.create(customer, new CreateContactRequest(topic, "Private support content"));
        sources.add(first.id());
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count(admin)).isEqualTo(1); // The legacy immediate producer and outbox share the dedup key.
        adminContacts.complete(admin, first.id());
        assertThatThrownBy(() -> adminContacts.complete(admin, first.id())).isInstanceOf(BusinessException.class);
        var second = contacts.create(customer, new CreateContactRequest(topic, "Another private question"));
        sources.add(second.id()); contacts.cancel(customer, second.id());
        assertThat(dispatcher.dispatchBatch()).isEqualTo(3);
        assertThat(count(admin)).isEqualTo(4);
        assertThat(inbox.list(admin, null, 20, false).items()).allSatisfy(row ->
                assertThat(row.body()).doesNotContain("Private support content", "Another private question"));
    }

    @Test void paymentSuccessAndRenewalAreRecordedOncePerOrderDespiteReplayedConfirmation() {
        long first = paidOrder();
        payments.confirmMockPayment(customer, first);
        payments.confirmMockPayment(customer, first);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        long second = paidOrder(); payments.confirmMockPayment(customer, second);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        var items = inbox.list(admin, null, 20, false).items();
        assertThat(items).hasSize(2).allSatisfy(row -> {
            assertThat(row.category()).isEqualTo(NotificationCategory.PAYMENT);
            assertThat(row.body()).contains("399000 VND");
            assertThat(row.deepLink()).startsWith("nutrimom://admin/payment-orders/");
        });
        assertThat(items).anySatisfy(row -> assertThat(row.title()).contains("gia hạn"));
    }

    @Test void concurrentWorkersDeliverEachEventOnce() throws Exception {
        for (int i = 0; i < 12; i++) {
            String source = UUID.randomUUID().toString(); sources.add(source);
            record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, source);
        }
        var pool = Executors.newFixedThreadPool(3);
        var ready = new CountDownLatch(3);
        var go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) futures.add(pool.submit(() -> {
                ready.countDown(); go.await(); return dispatcher.dispatchBatch();
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            int total = 0;
            for (var future : futures) total += future.get(30, TimeUnit.SECONDS);
            assertThat(total).isEqualTo(12);
            assertThat(count(admin)).isEqualTo(12);
            assertThat(count(otherAdmin)).isEqualTo(12);
        } finally { go.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void oneBrokenEventIsDeferredWithoutBlockingLaterEvents() {
        record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, customer);
        jdbc.update("update app.admin_notification_outbox set event_name='INVALID' where source_id=?", customer);
        record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, expert);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count(admin)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select attempts from app.admin_notification_outbox where source_id=?",
                Integer.class, customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select processed_at is null from app.admin_notification_outbox where source_id=?",
                Boolean.class, customer)).isTrue();
        jdbc.update("update app.admin_notification_outbox set event_name='ADMIN_ACCOUNT_CREATED',next_attempt_at=created_at where source_id=?", customer);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count(admin)).isEqualTo(2);
    }

    @Test void otpRegistrationNotifiesButOtpLoginDoesNot() {
        String phone = phone();
        var challenge = otp.request(new vn.nutrimom.auth.dto.OtpChallengeRequest(phone,
                vn.nutrimom.auth.domain.OtpPurpose.REGISTER, true, null), null);
        var registered = otp.verify(new vn.nutrimom.auth.dto.OtpVerifyRequest(
                challenge.challengeId(), challenge.debugCode(), null, "OTP QA"));
        owners.add(registered.authentication().user().id());
        var login = otp.request(new vn.nutrimom.auth.dto.OtpChallengeRequest(phone,
                vn.nutrimom.auth.domain.OtpPurpose.LOGIN, null, null), null);
        otp.verify(new vn.nutrimom.auth.dto.OtpVerifyRequest(login.challengeId(), login.debugCode(), null, null));
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count(admin)).isEqualTo(1);
    }

    @Test void magicLinkCreatesOrActivatesCustomersWithoutNotifyingForExistingLogin() {
        String email = "notification-" + UUID.randomUUID() + "@example.test";
        String code = magicToken(email);
        var registered = magic.verifyMagicLink(new vn.nutrimom.auth.dto.MagicLinkVerifyRequest(null, code, email, null));
        String id = registered.user().id(); owners.add(id);
        magic.verifyMagicLink(new vn.nutrimom.auth.dto.MagicLinkVerifyRequest(null, magicToken(email), email, null));
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        tx.executeWithoutResult(status -> {
            var u = users.findById(id).orElseThrow(); u.setStatus(UserStatus.PENDING_ACTIVATION); users.saveAndFlush(u);
        });
        magic.verifyMagicLink(new vn.nutrimom.auth.dto.MagicLinkVerifyRequest(null, magicToken(email), email, null));
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count(admin)).isEqualTo(2);
    }

    @Test void pendingEmailRegistrationAndActivationAreDistinctEvents() {
        String email = "notification-" + UUID.randomUUID() + "@example.test";
        var registration = auth.register(new RegisterRequest(null, email, PASSWORD, "Email QA", true, null));
        String id = registration.user().id(); owners.add(id);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        tx.executeWithoutResult(status -> {
            var u = users.findById(id).orElseThrow(); u.setEmailActivationTokenHash(tokens.hash("654321")); users.saveAndFlush(u);
        });
        auth.activateAccount(new ActivateAccountRequest("654321", email, null));
        auth.activateAccount(new ActivateAccountRequest("654321", email, null));
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count(admin)).isEqualTo(2);
    }

    @Test void directAndRandomAppointmentLifecycleNotifiesAdminsWithoutDisclosingConsultationNotes() {
        var specialty = vn.nutrimom.consultation.domain.Specialty.values()[0];
        tx.executeWithoutResult(status -> {
            var profile = new vn.nutrimom.consultation.domain.ExpertProfileEntity();
            profile.setUserId(expert); profile.setFullName("Expert QA"); profile.setSpecialty(specialty);
            profile.setStatus(vn.nutrimom.consultation.domain.ExpertStatus.ACTIVE); experts.saveAndFlush(profile);
        });
        LocalDate tomorrow = LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"))).plusDays(1);
        var direct = consultations.create(customer, new vn.nutrimom.consultation.dto.RequestDtos.CreateConsultationRequest(
                vn.nutrimom.consultation.domain.AssignmentType.DIRECT, expert, tomorrow, LocalTime.of(9, 0), specialty, "Private medical note"));
        sources.add(direct.id()); consultations.complete(expert, direct.id());
        var random = consultations.create(customer, new vn.nutrimom.consultation.dto.RequestDtos.CreateConsultationRequest(
                vn.nutrimom.consultation.domain.AssignmentType.RANDOM, null, null, null, specialty, "Private medical note"));
        sources.add(random.id());
        consultations.accept(expert, random.id(), new vn.nutrimom.consultation.dto.RequestDtos.AcceptConsultationRequest(tomorrow, LocalTime.of(10, 0)));
        consultations.cancel(customer, random.id());
        assertThatThrownBy(() -> consultations.cancel(customer, random.id())).isInstanceOf(BusinessException.class);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(5);
        assertThat(count(admin)).isEqualTo(5);
        assertThat(inbox.list(admin, null, 20, false).items()).allSatisfy(row ->
                assertThat(row.body()).doesNotContain("Private medical note"));
    }

    @Test void cursorPaginationRemainsStableWhenANewEventIsInserted() {
        for (int i = 0; i < 4; i++) {
            String source = UUID.randomUUID().toString(); sources.add(source);
            record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, source);
        }
        dispatcher.dispatchBatch();
        var first = inbox.list(admin, null, 2, false, NotificationAudience.ADMIN, null);
        record(AdminNotificationEvent.ADMIN_ACCOUNT_CREATED, customer); dispatcher.dispatchBatch();
        var next = inbox.list(admin, first.nextCursor(), 2, false, NotificationAudience.ADMIN, null);
        assertThat(next.items()).hasSize(2);
        assertThat(next.items().stream().map(row -> row.id()).toList())
                .doesNotContainAnyElementsOf(first.items().stream().map(row -> row.id()).toList());
        assertThat(next.hasMore()).isFalse();
    }

    private String magicToken(String email) {
        String code = Long.toString(SEQUENCE.incrementAndGet());
        tx.executeWithoutResult(status -> {
            var token = new vn.nutrimom.auth.domain.MagicLoginTokenEntity(); token.setEmail(email);
            token.setTokenHash(tokens.hash(code)); token.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
            magicTokens.saveAndFlush(token);
        });
        return code;
    }

    private String account(UserRole role, UserStatus status) {
        String id = tx.execute(transaction -> {
            var user = new UserEntity(); user.setPhone(phone()); user.setDisplayName("Notification QA");
            user.setPasswordHash(encoder.encode(PASSWORD)); user.setStatus(status); user.setRoles(Set.of(role));
            return users.saveAndFlush(user).getId();
        });
        owners.add(id); return id;
    }

    private static String phone() { return "09" + String.format("%08d", SEQUENCE.incrementAndGet()); }
    private void record(AdminNotificationEvent event, String source) {
        tx.executeWithoutResult(status -> events.record(event, source));
    }
    private long count(String owner) { return inbox.unreadCount(owner); }
    private long paidOrder() {
        long code = SEQUENCE.incrementAndGet();
        String id = tx.execute(status -> {
            var order = new PaymentOrderEntity(code, customer, PlanTier.PLAN_399K, 399000, "QA");
            order.setSource(PaymentSource.DEMO); return orders.saveAndFlush(order).getId();
        });
        sources.add(id); return code;
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt(String id) {
        return jwt().jwt(token -> token.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
