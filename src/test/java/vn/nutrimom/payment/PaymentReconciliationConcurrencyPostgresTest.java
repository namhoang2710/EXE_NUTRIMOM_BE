package vn.nutrimom.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.config.PaymentProperties;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PaymentSource;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.payment.repository.PaymentOrderRepository;
import vn.nutrimom.payment.service.PaymentReconciliationDispatcher;
import vn.nutrimom.payment.service.PaymentService;
import vn.payos.PayOS;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;
import vn.payos.service.blocking.v2.paymentRequests.PaymentRequestsService;
import vn.payos.service.blocking.webhooks.WebhooksService;

/**
 * Hai cuộc đua mà H2 không chứng minh được.
 *
 * <p>Câu claim dựa vào việc PostgreSQL đánh giá lại mệnh đề WHERE trên phiên bản dòng mới sau khi
 * chờ khoá ({@code EvalPlanQual}) ở mức READ COMMITTED. Tính đúng-một-lần của kích hoạt thì dựa vào
 * {@code select ... for update} thật. H2 không mô phỏng cả hai.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payos.client-id=test-client",
        "payos.api-key=test-api-key",
        "payos.checksum-key=test-checksum-key"
})
@EnabledIfSystemProperty(named = "payment.compatibility.test", matches = "true")
class PaymentReconciliationConcurrencyPostgresTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PaymentOrderRepository orders;
    @Autowired PaymentService service;
    @Autowired PaymentReconciliationDispatcher dispatcher;
    @Autowired PaymentProperties properties;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;

    @MockitoBean PayOS payOS;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;

    PaymentRequestsService paymentRequests;
    WebhooksService webhooks;

    @BeforeEach
    void stubGateway() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_qa", env.getProperty("spring.datasource.url"));
        paymentRequests = mock(PaymentRequestsService.class);
        webhooks = mock(WebhooksService.class);
        when(payOS.paymentRequests()).thenReturn(paymentRequests);
        when(payOS.webhooks()).thenReturn(webhooks);
        when(webhooks.verify(any(vn.payos.model.webhooks.Webhook.class)))
                .thenAnswer(call -> ((vn.payos.model.webhooks.Webhook) call.getArgument(0)).getData());
    }

    @Test
    void exactlyOneWorkerClaimsAnOverdueOrder() throws Exception {
        long orderCode = overdueOrder(newUser());
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime cutoff = now.minus(properties.getReconcileCooldown());

        AtomicInteger winners = new AtomicInteger();
        race(8, index -> {
            Integer claimed = tx.execute(s -> orders.claimForReconciliation(orderCode, now, cutoff));
            if (claimed != null && claimed == 1) {
                winners.incrementAndGet();
            }
        });

        assertEquals(1, winners.get(), "chỉ một worker được giành đơn");
    }

    /**
     * Job đánh hết hạn đua với webhook báo đã trả. Kết quả phải là PAID với đúng một gói và đúng
     * một kỳ hạn: PAID được phép đè EXPIRED, chiều ngược lại thì không.
     */
    @Test
    void theJobAndTheWebhookTogetherActivateExactlyOnce() throws Exception {
        String userId = newUser();
        long orderCode = overdueOrder(userId);
        when(paymentRequests.get(orderCode))
                .thenReturn(link(orderCode, PaymentLinkStatus.PAID, 99000L, 0L));

        race(2, index -> {
            if (index == 0) {
                dispatcher.reconcilePending(orderCode);
            } else {
                service.handlePayOSWebhook(webhook(orderCode));
            }
        });

        PaymentOrderEntity settled = orders.findByOrderCode(orderCode).orElseThrow();
        assertEquals(PaymentOrderStatus.PAID, settled.getStatus());
        assertNotNull(settled.getPaidAt());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from app.user_subscriptions where user_id=?", Long.class, userId));
        assertEquals(30L, jdbc.queryForObject(
                "select extract(day from end_date-start_date)::bigint from app.user_subscriptions where user_id=?",
                Long.class, userId));
    }

    // --- helpers ---

    private void race(int threads, java.util.function.IntConsumer body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        body.accept(index);
                    } catch (Exception ignored) {
                        // Thua cuộc đua là kết quả hợp lệ; phần khẳng định nằm ở trạng thái cuối.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private PaymentLink link(long orderCode, PaymentLinkStatus status, long amount, long remaining) {
        return PaymentLink.builder()
                .id("0123456789abcdef0123456789abcdef")
                .orderCode(orderCode)
                .amount(amount)
                .amountPaid(amount - remaining)
                .amountRemaining(remaining)
                .status(status)
                .createdAt(OffsetDateTime.now(clock).toString())
                .transactions(List.of())
                .build();
    }

    private vn.payos.model.webhooks.Webhook webhook(long orderCode) {
        var data = new vn.payos.model.webhooks.WebhookData();
        data.setOrderCode(orderCode);
        data.setAmount(99000L);
        data.setCurrency("VND");
        data.setCode("00");
        data.setDesc("00");
        data.setDescription("NutriMom PLAN_99K");
        data.setAccountNumber("0123456789");
        data.setReference("FT" + orderCode);
        data.setTransactionDateTime("2026-10-22 08:00:00");
        data.setPaymentLinkId("0123456789abcdef0123456789abcdef");
        var webhook = new vn.payos.model.webhooks.Webhook();
        webhook.setCode("00");
        webhook.setDesc("00");
        webhook.setSuccess(true);
        webhook.setData(data);
        webhook.setSignature("test-only");
        return webhook;
    }

    private long overdueOrder(String userId) {
        long orderCode = 7_000_000_000L + SEQ.incrementAndGet();
        return tx.execute(s -> {
            PaymentOrderEntity order = new PaymentOrderEntity(
                    orderCode, userId, PlanTier.PLAN_99K, 99000, "NutriMom PLAN_99K");
            order.setSource(PaymentSource.REAL);
            order.setPayosPaymentLinkId("0123456789abcdef0123456789abcdef");
            order.setCheckoutUrl("https://pay.payos.vn/web/" + orderCode);
            order.setExpiresAt(OffsetDateTime.now(clock)
                    .minus(properties.getReconcileGrace())
                    .minusMinutes(1));
            orders.saveAndFlush(order);
            return orderCode;
        });
    }

    private String newUser() {
        return tx.execute(s -> {
            UserEntity user = new UserEntity();
            user.setPhone("+8498" + SEQ.incrementAndGet());
            user.setDisplayName("Reconcile Race");
            user.setStatus(UserStatus.ACTIVE);
            user.setRoles(Set.of(UserRole.USER));
            return users.saveAndFlush(user).getId();
        });
    }
}
