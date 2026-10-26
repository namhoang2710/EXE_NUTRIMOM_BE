package vn.nutrimom.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.repository.PaymentOrderRepository;
import vn.nutrimom.payment.service.PaymentReconciliationJob;
import vn.payos.PayOS;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;
import vn.payos.service.blocking.v2.paymentRequests.PaymentRequestsService;

/**
 * Vòng đời đơn thanh toán với một PayOS giả, chạy ở chế độ REAL.
 *
 * <p>Cố ý <strong>không</strong> {@code @Transactional}: dispatcher ghi trong transaction
 * {@code REQUIRES_NEW}, nên dưới một transaction test chưa commit nó sẽ không nhìn thấy dữ liệu của
 * chính bài kiểm. Mỗi bài dùng một người dùng riêng thay cho việc dọn bảng.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payos.client-id=test-client",
        "payos.api-key=test-api-key",
        "payos.checksum-key=test-checksum-key"
})
class PaymentOrderLifecycleTest {

    private static final AtomicLong PHONE = new AtomicLong(912000000L);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired PaymentOrderRepository orders;
    @Autowired Clock clock;
    // Job bean bị tắt ở profile test; dựng tay để gọi thẳng một lượt quét, không qua scheduler.
    @Autowired vn.nutrimom.payment.service.PaymentReconciliationDispatcher dispatcher;
    @Autowired vn.nutrimom.config.PaymentProperties paymentProperties;

    @MockitoBean PayOS payOS;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;

    PaymentRequestsService paymentRequests;

    vn.payos.service.blocking.webhooks.WebhooksService webhooks;

    @BeforeEach
    void stubGateway() {
        paymentRequests = org.mockito.Mockito.mock(PaymentRequestsService.class);
        when(payOS.paymentRequests()).thenReturn(paymentRequests);
        webhooks = org.mockito.Mockito.mock(vn.payos.service.blocking.webhooks.WebhooksService.class);
        when(payOS.webhooks()).thenReturn(webhooks);
        when(webhooks.verify(any(vn.payos.model.webhooks.Webhook.class)))
                .thenAnswer(call -> ((vn.payos.model.webhooks.Webhook) call.getArgument(0)).getData());
        when(paymentRequests.create(any(CreatePaymentLinkRequest.class)))
                .thenAnswer(invocation -> {
                    CreatePaymentLinkRequest request = invocation.getArgument(0);
                    return CreatePaymentLinkResponse.builder()
                            .bin("970422")
                            .accountNumber("0123456789")
                            .accountName("NUTRIMOM")
                            .amount(request.getAmount())
                            .description(request.getDescription())
                            .orderCode(request.getOrderCode())
                            .currency("VND")
                            .paymentLinkId("0123456789abcdef0123456789abcdef")
                            .status(PaymentLinkStatus.PENDING)
                            .expiredAt(request.getExpiredAt())
                            .checkoutUrl("https://pay.payos.vn/web/" + request.getOrderCode())
                            .qrCode("00020101021238")
                            .build();
                });
    }

    /**
     * Đơn vị của {@code expiredAt} là chỗ sai thì hỏng toàn bộ checkout mà không ai thấy ngay: gửi
     * mili khi cổng chờ giây thì giá trị vượt Int32 và link coi như vô hạn.
     */
    @Test
    void checkoutSendsTheExpiryAsEpochSecondsAndStoresIt() throws Exception {
        String userId = newUser();
        OffsetDateTime before = OffsetDateTime.now(clock);

        long orderCode = checkout(userId, "PLAN_99K");

        ArgumentCaptor<CreatePaymentLinkRequest> sent = ArgumentCaptor.forClass(CreatePaymentLinkRequest.class);
        verify(paymentRequests).create(sent.capture());
        Long expiredAt = sent.getValue().getExpiredAt();
        assertNotNull(expiredAt, "checkout phải gửi expiredAt, nếu không link sống mãi");
        assertTrue(expiredAt <= Integer.MAX_VALUE, "expiredAt phải là epoch GIÂY, vừa trong Int32");
        long expectedSeconds = before.plus(paymentProperties.getCheckoutTtl()).toEpochSecond();
        assertTrue(Math.abs(expiredAt - expectedSeconds) <= 5,
                "expiredAt lệch quá xa mốc now + checkout-ttl: " + expiredAt);

        PaymentOrderEntity stored = orders.findByOrderCode(orderCode).orElseThrow();
        assertNotNull(stored.getExpiresAt());
        assertEquals(expiredAt.longValue(), stored.getExpiresAt().toEpochSecond(),
                "hạn lưu ở DB và hạn gửi sang cổng phải là cùng một mốc");
    }

    /** Click đúp không được sinh ra hai link sống cho cùng một ý định mua. */
    @Test
    void secondCheckoutForTheSamePlanReusesTheLiveLink() throws Exception {
        String userId = newUser();

        long first = checkout(userId, "PLAN_99K");
        long second = checkout(userId, "PLAN_99K");

        assertEquals(first, second);
        verify(paymentRequests, times(1)).create(any(CreatePaymentLinkRequest.class));
        assertEquals(1, orders.findPendingByUser(userId, vn.nutrimom.payment.domain.PaymentSource.REAL).size());
    }

    /** Đổi sang gói khác thì link cũ phải chết, không thì quét nhầm mã là trả tiền cho gói đã bỏ. */
    @Test
    void checkoutForAnotherPlanClosesThePreviousLink() throws Exception {
        String userId = newUser();

        long cheap = checkout(userId, "PLAN_99K");
        long expensive = checkout(userId, "PLAN_399K");

        verify(paymentRequests).cancel(eq(cheap), anyString());
        assertEquals(PaymentOrderStatus.CANCELLED, orders.findByOrderCode(cheap).orElseThrow().getStatus());
        assertEquals(PaymentOrderStatus.PENDING, orders.findByOrderCode(expensive).orElseThrow().getStatus());
    }

    @Test
    void cancellingIsOwnerScopedAndIdempotent() throws Exception {
        String owner = newUser();
        String stranger = newUser();
        long orderCode = checkout(owner, "PLAN_99K");

        mockMvc.perform(post("/api/v1/payments/orders/{orderCode}/cancel", orderCode).with(userJwt(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ORDER_NOT_FOUND"));
        verify(paymentRequests, never()).cancel(eq(orderCode), anyString());

        mockMvc.perform(post("/api/v1/payments/orders/{orderCode}/cancel", orderCode).with(userJwt(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // Lần hai không gọi lại cổng và vẫn trả 200: FE đua với job, kết quả họ muốn đã có sẵn.
        mockMvc.perform(post("/api/v1/payments/orders/{orderCode}/cancel", orderCode).with(userJwt(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        verify(paymentRequests, times(1)).cancel(eq(orderCode), anyString());
    }

    @Test
    void cancellingAPaidOrderConflicts() throws Exception {
        String userId = newUser();
        long orderCode = checkout(userId, "PLAN_99K");
        markPaid(orderCode);

        mockMvc.perform(post("/api/v1/payments/orders/{orderCode}/cancel", orderCode).with(userJwt(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ORDER_NOT_CANCELLABLE"));
    }

    /** Cổng từ chối thì không được ghi gì: một đơn CANCELLED với link còn trả được là ca tệ nhất. */
    @Test
    void cancellingLeavesTheOrderPendingWhenTheGatewayRefuses() throws Exception {
        String userId = newUser();
        long orderCode = checkout(userId, "PLAN_99K");
        when(paymentRequests.cancel(eq(orderCode), anyString()))
                .thenThrow(new IllegalStateException("gateway down"));

        mockMvc.perform(post("/api/v1/payments/orders/{orderCode}/cancel", orderCode).with(userJwt(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_FAILED"));

        assertEquals(PaymentOrderStatus.PENDING, orders.findByOrderCode(orderCode).orElseThrow().getStatus());
    }

    /**
     * Hạn của ta là hạn có hiệu lực. Đánh EXPIRED suông mà không huỷ ở cổng sẽ để lại một link còn
     * trả được trong khi phía ta đã coi đơn là chết.
     */
    @Test
    void overdueOrdersAreClosedAtTheGatewayBeforeBeingExpired() throws Exception {
        String userId = newUser();
        long orderCode = checkout(userId, "PLAN_99K");
        makeOverdue(orderCode);
        when(paymentRequests.get(orderCode)).thenReturn(gatewayLink(orderCode, PaymentLinkStatus.PENDING, 99000L, 99000L));

        runJobOnce();

        verify(paymentRequests).cancel(eq(orderCode), anyString());
        assertEquals(PaymentOrderStatus.EXPIRED, orders.findByOrderCode(orderCode).orElseThrow().getStatus());
    }

    /** Quá hạn nhưng cổng báo đã trả thì tiền là thật: kích hoạt, không hết hạn. */
    @Test
    void overdueOrderAlreadyPaidAtTheGatewayIsActivatedOnce() throws Exception {
        String userId = newUser();
        long orderCode = checkout(userId, "PLAN_99K");
        makeOverdue(orderCode);
        when(paymentRequests.get(orderCode)).thenReturn(gatewayLink(orderCode, PaymentLinkStatus.PAID, 99000L, 0L));

        runJobOnce();
        runJobOnce();

        verify(paymentRequests, never()).cancel(eq(orderCode), anyString());
        PaymentOrderEntity settled = orders.findByOrderCode(orderCode).orElseThrow();
        assertEquals(PaymentOrderStatus.PAID, settled.getStatus());
        assertNotNull(settled.getPaidAt());
        mockMvc.perform(get("/api/v1/payments/my-subscription").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_tier").value("PLAN_99K"))
                // Đúng một kỳ hạn: lượt quét thứ hai không được cộng thêm 30 ngày nữa.
                .andExpect(jsonPath("$.data.days_remaining").value(30));
    }

    /** UNDERPAID đã có tiền trong tài khoản; huỷ link là cắt mất đường đối chiếu duy nhất. */
    @Test
    void underpaidOverdueOrdersAreLeftAlone() throws Exception {
        String userId = newUser();
        long orderCode = checkout(userId, "PLAN_99K");
        makeOverdue(orderCode);
        when(paymentRequests.get(orderCode)).thenReturn(gatewayLink(orderCode, PaymentLinkStatus.UNDERPAID, 99000L, 9000L));

        runJobOnce();

        verify(paymentRequests, never()).cancel(eq(orderCode), anyString());
        assertEquals(PaymentOrderStatus.PENDING, orders.findByOrderCode(orderCode).orElseThrow().getStatus());
    }

    /** Link DEMO là MOCK-… nên gọi cổng sẽ 404; LEGACY_UNKNOWN có thể thuộc writer khác. */
    @Test
    void demoAndLegacyOrdersNeverReachTheGateway() throws Exception {
        String userId = newUser();
        long demo = insertOverdueOrder(userId, vn.nutrimom.payment.domain.PaymentSource.DEMO);
        long legacy = insertOverdueOrder(userId, vn.nutrimom.payment.domain.PaymentSource.LEGACY_UNKNOWN);

        runJobOnce();

        verify(paymentRequests, never()).get(demo);
        verify(paymentRequests, never()).get(legacy);
        assertEquals(PaymentOrderStatus.PENDING, orders.findByOrderCode(demo).orElseThrow().getStatus());
        assertEquals(PaymentOrderStatus.PENDING, orders.findByOrderCode(legacy).orElseThrow().getStatus());
    }

    /**
     * Mã lỗi của một lần chuyển khoản hỏng không được giết link. Bản cũ biến mọi mã khác "00" thành
     * CANCELLED, nên một lần quét trượt là mất luôn đơn đang lành.
     */
    @Test
    void aFailedTransferWebhookLeavesTheOrderPending() throws Exception {
        String userId = newUser();
        long orderCode = checkout(userId, "PLAN_99K");

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(webhookBody(orderCode, 99000L, "99"))))
                .andExpect(status().isOk());

        assertEquals(PaymentOrderStatus.PENDING, orders.findByOrderCode(orderCode).orElseThrow().getStatus());
    }

    /**
     * Tiền về sau khi đơn đã hết hạn: cổng xác nhận đã trả thì phải giao hàng. Với hạn 5 phút đây
     * là chuyện xảy ra đều đặn chứ không phải ngoại lệ hiếm.
     */
    @Test
    void aLatePaymentOnAnExpiredOrderIsHonouredWhenTheGatewayConfirmsIt() throws Exception {
        String userId = newUser();
        long orderCode = checkout(userId, "PLAN_99K");
        markStatus(orderCode, PaymentOrderStatus.EXPIRED);
        when(paymentRequests.get(orderCode)).thenReturn(gatewayLink(orderCode, PaymentLinkStatus.PAID, 99000L, 0L));

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(webhookBody(orderCode, 99000L, "00"))))
                .andExpect(status().isOk());

        assertEquals(PaymentOrderStatus.PAID, orders.findByOrderCode(orderCode).orElseThrow().getStatus());
    }

    /** Bản thân webhook không hồi sinh được đơn nào: nguồn sự thật vẫn là câu trả lời của cổng. */
    @Test
    void aWebhookAloneCannotReviveAnExpiredOrder() throws Exception {
        String userId = newUser();
        long orderCode = checkout(userId, "PLAN_99K");
        markStatus(orderCode, PaymentOrderStatus.EXPIRED);
        when(paymentRequests.get(orderCode)).thenReturn(gatewayLink(orderCode, PaymentLinkStatus.EXPIRED, 99000L, 99000L));

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(webhookBody(orderCode, 99000L, "00"))))
                .andExpect(status().isOk());

        assertEquals(PaymentOrderStatus.EXPIRED, orders.findByOrderCode(orderCode).orElseThrow().getStatus());
    }

    // --- helpers ---

    /**
     * Payload webhook đi qua đúng đường HTTP của production.
     *
     * <p>Mọi trường {@code @NonNull} của SDK đều phải có mặt: thiếu một trường thì Jackson không
     * dựng nổi {@code WebhookData} và request dừng ở MALFORMED_JSON trước khi chạm tới service, tức
     * là bài kiểm sẽ xanh/đỏ vì lý do chẳng liên quan gì tới vòng đời đơn.</p>
     */
    private vn.payos.model.webhooks.Webhook webhookBody(long orderCode, long amount, String code) {
        var data = new vn.payos.model.webhooks.WebhookData();
        data.setOrderCode(orderCode);
        data.setAmount(amount);
        data.setCurrency("VND");
        data.setCode(code);
        data.setDesc(code);
        data.setDescription("NutriMom PLAN_99K");
        data.setAccountNumber("0123456789");
        data.setReference("FT" + orderCode);
        data.setTransactionDateTime("2026-10-22 08:00:00");
        data.setPaymentLinkId("0123456789abcdef0123456789abcdef");
        var webhook = new vn.payos.model.webhooks.Webhook();
        webhook.setCode(code);
        webhook.setDesc(code);
        webhook.setSuccess("00".equals(code));
        webhook.setData(data);
        webhook.setSignature("test-only");
        return webhook;
    }

    private void markStatus(long orderCode, PaymentOrderStatus status) {
        PaymentOrderEntity order = orders.findByOrderCode(orderCode).orElseThrow();
        order.setStatus(status);
        orders.save(order);
    }

    private void runJobOnce() {
        new PaymentReconciliationJob(orders, dispatcher, paymentProperties, clock).runOnce();
    }

    private PaymentLink gatewayLink(long orderCode, PaymentLinkStatus status, long amount, long remaining) {
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

    /** Đẩy hạn lùi quá cả thời gian ân hạn để lượt quét kế tiếp nhìn thấy đơn. */
    private void makeOverdue(long orderCode) {
        PaymentOrderEntity order = orders.findByOrderCode(orderCode).orElseThrow();
        order.setExpiresAt(OffsetDateTime.now(clock)
                .minus(paymentProperties.getReconcileGrace())
                .minusMinutes(1));
        orders.save(order);
    }

    private long insertOverdueOrder(String userId, vn.nutrimom.payment.domain.PaymentSource source) {
        long orderCode = PHONE.incrementAndGet();
        PaymentOrderEntity order = new PaymentOrderEntity(
                orderCode, userId, vn.nutrimom.payment.domain.PlanTier.PLAN_99K, 99000, "NutriMom PLAN_99K");
        order.setSource(source);
        order.setPayosPaymentLinkId(source == vn.nutrimom.payment.domain.PaymentSource.DEMO
                ? "MOCK-" + orderCode : null);
        order.setExpiresAt(OffsetDateTime.now(clock)
                .minus(paymentProperties.getReconcileGrace())
                .minusMinutes(1));
        orders.save(order);
        return orderCode;
    }

    private void markPaid(long orderCode) {
        PaymentOrderEntity order = orders.findByOrderCode(orderCode).orElseThrow();
        order.setStatus(PaymentOrderStatus.PAID);
        order.setPaidAt(OffsetDateTime.now(ZoneOffset.UTC));
        orders.save(order);
    }

    private long checkout(String userId, String planTier) throws Exception {
        var result = mockMvc.perform(post("/api/v1/payments/subscription-checkout")
                        .with(userJwt(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_tier\": \"" + planTier + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("order_code").asLong();
    }

    private String newUser() {
        UserEntity user = new UserEntity();
        user.setPhone("0" + PHONE.incrementAndGet());
        user.setDisplayName("Mẹ Bầu Vòng Đời");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        return users.saveAndFlush(user).getId();
    }

    private RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", "ROLE_USER"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
