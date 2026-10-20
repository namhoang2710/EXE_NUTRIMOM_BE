package vn.nutrimom.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Period;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.payment.config.PayOSProperties;
import vn.nutrimom.payment.domain.*;
import vn.nutrimom.payment.repository.*;
import vn.nutrimom.payment.service.PaymentService;
import vn.nutrimom.common.exception.BusinessException;
import vn.payos.PayOS;
import vn.payos.model.webhooks.*;

/** Uses committed fixtures and independent transactions/threads to exercise real database locks. */
@SpringBootTest(properties={"payos.client-id=test","payos.api-key=test","payos.checksum-key=test"})
@ActiveProfiles("test")
class PaymentSafetyIntegrationTest {
    static final AtomicLong CODE=new AtomicLong(700000);
    @Autowired UserRepository users;
    @Autowired PaymentOrderRepository orders;
    @Autowired UserSubscriptionRepository subscriptions;
    @Autowired PaymentService service;
    @Autowired PayOSProperties properties;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean(answers=org.mockito.Answers.RETURNS_DEEP_STUBS) PayOS payos;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    TransactionTemplate tx;
    String user,other;
    @BeforeEach void setup() {
        tx=new TransactionTemplate(manager);properties.setDemoEnabled(true);
        user=account();other=account();
        when(payos.webhooks().verify(any(Webhook.class))).thenAnswer(call->((Webhook)call.getArgument(0)).getData());
    }
    @AfterEach void cleanup() {
        properties.setDemoEnabled(true);
        for(String id:List.of(user,other)) assertEquals(0,jdbc.queryForObject(
                "select count(*) from app.admin_notification_outbox where event_name='ADMIN_SUBSCRIPTION_CHANGED' and deep_link=?",
                Integer.class,"nutrimom://admin/users/"+id),"API purchase must not also emit a SQL-plan-change notification");
        tx.executeWithoutResult(s->{
            for(String id:List.of(user,other)) {
                jdbc.update("delete from app.admin_notification_outbox where source_id in (select id from app.payment_orders where user_id=?)", id);
                subscriptions.deleteAll(subscriptions.findAllByUserIdOrderByCreatedAtDesc(id));
                orders.deleteAll(orders.findAllByUserIdOrderByCreatedAtDesc(id));users.deleteById(id);
            }
        });
        // Commit-time V50 events from fixture teardown must not escape into the next test's dispatcher.
        for(String id:List.of(user,other)) jdbc.update("delete from app.admin_notification_outbox where deep_link=?","nutrimom://admin/users/"+id);
    }
    @Test void demoConfirmationRequiresOwnerDemoSourceEnabledServerAndPendingState() {
        long demo=order(PaymentSource.DEMO,PaymentOrderStatus.PENDING);
        assertThrows(BusinessException.class,()->service.confirmMockPayment(other,demo));
        properties.setDemoEnabled(false);
        assertThrows(BusinessException.class,()->service.confirmMockPayment(user,demo));
        properties.setDemoEnabled(true);
        assertThrows(BusinessException.class,()->service.confirmMockPayment(user,order(PaymentSource.REAL,PaymentOrderStatus.PENDING)));
        assertThrows(BusinessException.class,()->service.confirmMockPayment(user,order(PaymentSource.LEGACY_UNKNOWN,PaymentOrderStatus.PENDING)));
        assertThrows(BusinessException.class,()->service.confirmMockPayment(user,order(PaymentSource.DEMO,PaymentOrderStatus.CANCELLED)));
        assertThrows(BusinessException.class,()->service.confirmMockPayment(user,order(PaymentSource.DEMO,PaymentOrderStatus.EXPIRED)));
        assertEquals(0,tx.execute(s->subscriptions.findAllByUserIdOrderByCreatedAtDesc(user).size()).intValue());
    }
    @Test void duplicateConcurrentDemoConfirmationActivatesOnlyOnce() throws Exception {
        long code=order(PaymentSource.DEMO,PaymentOrderStatus.PENDING);
        concurrent(()->service.confirmMockPayment(user,code),()->service.confirmMockPayment(user,code),
                ()->service.confirmMockPayment(user,code));
        assertDays(30);
    }
    @Test void differentOrdersForSameUserExtendSeriallyInsteadOfCreatingDuplicateSubscriptions() throws Exception {
        long first=order(PaymentSource.DEMO,PaymentOrderStatus.PENDING),second=order(PaymentSource.DEMO,PaymentOrderStatus.PENDING);
        concurrent(()->service.confirmMockPayment(user,first),()->service.confirmMockPayment(user,second));
        assertDays(60);
    }
    @Test void verifiedConcurrentWebhookDuplicatesActivateOnlyOnce() throws Exception {
        long code=order(PaymentSource.REAL,PaymentOrderStatus.PENDING);var webhook=webhook(code,99000,"VND");
        concurrent(()->service.handlePayOSWebhook(webhook),()->service.handlePayOSWebhook(webhook),()->service.handlePayOSWebhook(webhook));
        assertDays(30);
        assertEquals(1, jdbc.queryForObject("select count(*) from app.admin_notification_outbox where source_id=(select id from app.payment_orders where order_code=?)", Integer.class, code));
        verify(payos.webhooks(),times(3)).verify(any(Webhook.class));
    }
    @Test void verifiedWebhookCannotActivateWrongAmountCurrencyOrDemoOrder() {
        long code=order(PaymentSource.REAL,PaymentOrderStatus.PENDING);
        assertThrows(BusinessException.class,()->service.handlePayOSWebhook(webhook(code,1,"VND")));
        assertThrows(BusinessException.class,()->service.handlePayOSWebhook(webhook(code,99000,"USD")));
        long demo=order(PaymentSource.DEMO,PaymentOrderStatus.PENDING);
        assertThrows(BusinessException.class,()->service.handlePayOSWebhook(webhook(demo,99000,"VND")));
        assertEquals(0,tx.execute(s->subscriptions.findAllByUserIdOrderByCreatedAtDesc(user).size()).intValue());
    }
    @Test void real399kPaymentGrantsTheCatalogTermAndRecordsRealPaidAmount() {
        long code=order(PaymentSource.REAL,PaymentOrderStatus.PENDING,PlanTier.PLAN_399K);
        service.handlePayOSWebhook(webhook(code,399000,"VND"));
        assertTerm(PlanTier.PLAN_399K.getTerm());
        tx.executeWithoutResult(s->{var paid=orders.findByOrderCode(code).orElseThrow();
            assertEquals(PaymentSource.REAL,paid.getSource());assertEquals(PaymentOrderStatus.PAID,paid.getStatus());
            assertEquals(399000,paid.getAmount());assertNotNull(paid.getPaidAt());
            assertEquals(PlanTier.PLAN_399K,subscriptions.findAllByUserIdOrderByCreatedAtDesc(user).get(0).getPlanTier());});
    }
    /**
     * Mua gói rẻ hơn khi đang giữ gói cao chỉ cộng thời hạn, KHÔNG hạ quyền lợi.
     *
     * <p>Nhánh gia hạn từng ghi đè tier vô điều kiện. Với hạn 30 ngày thiệt hại còn nhỏ; từ khi
     * PLAN_399K cấp 10 tháng thì một đơn 99k sẽ nuốt mất gần 10 tháng quyền lợi cao để đổi lấy
     * 30 ngày.</p>
     */
    @Test void buyingACheaperPlanWhileHoldingAHigherOneExtendsWithoutDowngrading() {
        properties.setDemoEnabled(true);
        service.confirmMockPayment(user,order(PaymentSource.DEMO,PaymentOrderStatus.PENDING,PlanTier.PLAN_399K));
        assertEquals(PlanTier.PLAN_399K,currentTier());

        service.confirmMockPayment(user,order(PaymentSource.DEMO,PaymentOrderStatus.PENDING,PlanTier.PLAN_99K));
        assertEquals(PlanTier.PLAN_399K,currentTier());
        assertTerm(PlanTier.PLAN_399K.getTerm(),PlanTier.PLAN_99K.getTerm());
    }
    /** Chiều ngược lại vẫn phải nâng được tier. */
    @Test void buyingAHigherPlanWhileHoldingALowerOneUpgradesTheTier() {
        properties.setDemoEnabled(true);
        service.confirmMockPayment(user,order(PaymentSource.DEMO,PaymentOrderStatus.PENDING,PlanTier.PLAN_99K));
        assertEquals(PlanTier.PLAN_99K,currentTier());

        service.confirmMockPayment(user,order(PaymentSource.DEMO,PaymentOrderStatus.PENDING,PlanTier.PLAN_399K));
        assertEquals(PlanTier.PLAN_399K,currentTier());
        assertTerm(PlanTier.PLAN_99K.getTerm(),PlanTier.PLAN_399K.getTerm());
    }
    @Test void publicWebhookCannotUseUnsignedPayloadWhenGatewayIsUnconfigured() {
        long code=order(PaymentSource.DEMO,PaymentOrderStatus.PENDING);
        String previous=properties.getClientId();
        try {
            properties.setClientId(null);
            assertThrows(BusinessException.class,()->service.handlePayOSWebhook(webhook(code,99000,"VND")));
            assertEquals(0,tx.execute(s->subscriptions.findAllByUserIdOrderByCreatedAtDesc(user).size()).intValue());
        } finally {properties.setClientId(previous);}
    }
    private String account() {
        return tx.execute(s->{var u=new UserEntity();u.setPhone("+8497"+CODE.incrementAndGet());u.setDisplayName("Payment Safety");
            u.setStatus(UserStatus.ACTIVE);u.setRoles(Set.of(UserRole.USER));return users.saveAndFlush(u).getId();});
    }
    private long order(PaymentSource source,PaymentOrderStatus status) {
        return order(source,status,PlanTier.PLAN_99K);
    }
    private long order(PaymentSource source,PaymentOrderStatus status,PlanTier plan) {
        long code=CODE.incrementAndGet();
        tx.executeWithoutResult(s->{var p=new PaymentOrderEntity(code,user,plan,plan.getPrice(),"safety test");
            p.setSource(source);p.setStatus(status);orders.saveAndFlush(p);});
        return code;
    }
    private Webhook webhook(long code,long amount,String currency) {
        var data=new WebhookData();data.setOrderCode(code);data.setAmount(amount);data.setCurrency(currency);data.setCode("00");
        var webhook=new Webhook();webhook.setCode("00");webhook.setSuccess(true);webhook.setData(data);webhook.setSignature("test-only");
        return webhook;
    }
    private void assertDays(long days) {
        tx.executeWithoutResult(s->{var rows=subscriptions.findAllByUserIdOrderByCreatedAtDesc(user);
            assertEquals(1,rows.size());assertEquals(days,Duration.between(rows.get(0).getStartDate(),rows.get(0).getEndDate()).toDays());});
    }
    /**
     * Kỳ vọng tính từ startDate đã lưu chứ không phải một hằng số ngày: PLAN_399K là 10 tháng
     * <em>lịch</em> nên số ngày thực tế là 303–305 tuỳ ngày chạy test. Nhận nhiều term để kiểm cả
     * các lần gia hạn cộng dồn.
     */
    private void assertTerm(Period... terms) {
        tx.executeWithoutResult(s->{var rows=subscriptions.findAllByUserIdOrderByCreatedAtDesc(user);
            assertEquals(1,rows.size());
            var expected=rows.get(0).getStartDate();
            for(Period t:terms) expected=expected.plus(t);
            assertEquals(expected,rows.get(0).getEndDate());});
    }
    private PlanTier currentTier() {
        return tx.execute(s->subscriptions.findAllByUserIdOrderByCreatedAtDesc(user).get(0).getPlanTier());
    }
    private void concurrent(Runnable... actions) throws Exception {
        var pool=Executors.newFixedThreadPool(actions.length);var ready=new CountDownLatch(actions.length);var go=new CountDownLatch(1);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(var action:actions) futures.add(pool.submit(()->{ready.countDown();try {go.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}action.run();}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            for(var f:futures)f.get(15,TimeUnit.SECONDS);
        } finally {go.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
    }
}
