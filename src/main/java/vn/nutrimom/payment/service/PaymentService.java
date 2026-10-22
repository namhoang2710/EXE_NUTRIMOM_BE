package vn.nutrimom.payment.service;

import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.api.PageResponse;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.config.PaymentProperties;
import vn.nutrimom.payment.config.PayOSProperties;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PaymentSource;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.payment.domain.SubscriptionStatus;
import vn.nutrimom.payment.domain.UserSubscriptionEntity;
import vn.nutrimom.payment.dto.CreatePaymentRequest;
import vn.nutrimom.payment.dto.PaymentOrderResponse;
import vn.nutrimom.payment.dto.PaymentResponse;
import vn.nutrimom.payment.dto.SubscriptionResponse;
import vn.nutrimom.payment.repository.PaymentOrderRepository;
import vn.nutrimom.payment.repository.UserSubscriptionRepository;
import vn.payos.PayOS;
import vn.payos.exception.InvalidSignatureException;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLinkItem;
import vn.payos.model.webhooks.Webhook;
import vn.payos.model.webhooks.WebhookData;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String USER_CANCEL_REASON = "Người dùng huỷ đơn";
    private static final String SUPERSEDED_REASON = "Người dùng tạo đơn mới cho gói khác";
    private static final int ORDER_CODE_ATTEMPTS = 5;

    /**
     * Thứ tự này trước đây nằm trong {@code order by} của JPQL; bỏ {@code @Query} thì nó phải đi
     * theo {@code Pageable}, không thì trang mất thứ tự ổn định. Giữ {@code created_at DESC} vì đó
     * là thứ index {@code ix_payment_orders_user_created} (V31) phục vụ; {@code id DESC} chỉ để phá
     * hoà khi hai đơn trùng timestamp.
     */
    private static final Sort HISTORY_SORT = Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final PayOS payOS;
    private final PayOSProperties properties;
    private final PaymentProperties paymentProperties;
    private final PaymentOrderRepository orderRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final UserRepository users;
    private final SubscriptionActivationService activation;
    private final PaymentReconciliationDispatcher reconciliation;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PaymentService(PayOS payOS,
                          PayOSProperties properties,
                          PaymentProperties paymentProperties,
                          PaymentOrderRepository orderRepository,
                          UserSubscriptionRepository subscriptionRepository,
                          UserRepository users,
                          SubscriptionActivationService activation,
                          PaymentReconciliationDispatcher reconciliation,
                          PlatformTransactionManager transactionManager,
                          Clock clock) {
        this.payOS = payOS;
        this.properties = properties;
        this.paymentProperties = paymentProperties;
        this.orderRepository = orderRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.users = users;
        this.activation = activation;
        this.reconciliation = reconciliation;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Mở một lượt thanh toán cho gói hội viên.
     *
     * <p>Method này cố ý <strong>không</strong> {@code @Transactional}. Ba lời gọi sang PayOS có thể
     * xuất hiện ở đây (huỷ đơn cũ, tạo link mới, huỷ link thừa), mỗi lời gọi mặc định chờ tới 60
     * giây và SDK còn thử lại hai lần; giữ một transaction - tệ hơn nữa là một khoá dòng - suốt
     * quãng đó nghĩa là một sự cố bên cổng sẽ chặn luôn webhook của chính người dùng ấy, tức là
     * tiền đã về mà không ai ghi nhận được.</p>
     *
     * <p>Nguyên tử theo từng người dùng vẫn giữ được, chỉ là ở phạm vi hẹp hơn: lần tra cứu cuối
     * cùng và lệnh insert nằm chung trong một transaction ngắn dưới khoá dòng user. Cái giá là
     * trong lần đua hiếm hoi, kẻ thua đã lỡ tạo một link ở cổng; link đó được huỷ ngay sau đó, và
     * nếu lệnh huỷ cũng hỏng thì {@code expiredAt} đóng nó lại sau đúng một chu kỳ TTL.</p>
     */
    public PaymentResponse createSubscriptionPayment(String userId, CreatePaymentRequest request) {
        if (request.planTier() == null || request.planTier() == PlanTier.FREE) {
            throw new BusinessException(ErrorCode.INVALID_PLAN_TIER, "Gói Miễn phí không yêu cầu thanh toán.");
        }

        PlanTier plan = request.planTier();
        if (!properties.isConfigured() && !properties.isDemoEnabled()) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Cổng thanh toán chưa sẵn sàng.");
        }
        PaymentSource source = properties.isConfigured() ? PaymentSource.REAL : PaymentSource.DEMO;
        OffsetDateTime now = OffsetDateTime.now(clock);

        PaymentOrderEntity reusable = transactions.execute(status -> findReusable(userId, plan, source, now));
        if (reusable != null) {
            log.info("Reusing live checkout orderCode={}", reusable.getOrderCode());
            return toPaymentResponse(reusable);
        }

        supersedePendingOrders(userId, source, plan);

        int amount = plan.getPrice();
        OffsetDateTime expiresAt = now.plus(paymentProperties.getCheckoutTtl());

        long orderCode = nextOrderCode(now);

        String description = "NutriMom " + plan.name();
        if (description.length() > 25) {
            description = description.substring(0, 25);
        }

        String checkoutUrl;
        String qrCode = null;
        String paymentLinkId = null;

        if (properties.isConfigured()) {
            try {
                PaymentLinkItem item = PaymentLinkItem.builder()
                        .name(plan.getDisplayName())
                        .quantity(1)
                        .price((long) amount)
                        .build();

                String returnUrl = properties.getReturnUrl() + "?orderCode=" + orderCode;
                String cancelUrl = properties.getCancelUrl() + "?orderCode=" + orderCode;

                CreatePaymentLinkRequest payosRequest = CreatePaymentLinkRequest.builder()
                        .orderCode(orderCode)
                        .amount((long) amount)
                        .description(description)
                        .returnUrl(returnUrl)
                        .cancelUrl(cancelUrl)
                        .items(List.of(item))
                        // Tài liệu PayOS khai expiredAt là Unix Timestamp kiểu Int32, và ví dụ
                        // chính thức của SDK dùng System.currentTimeMillis()/1000 - tức là GIÂY.
                        // Gửi nhầm mili thì giá trị vượt Int32 và link coi như không có hạn.
                        .expiredAt(expiresAt.toEpochSecond())
                        .build();

                CreatePaymentLinkResponse response = payOS.paymentRequests().create(payosRequest);
                checkoutUrl = response.getCheckoutUrl();
                qrCode = response.getQrCode();
                paymentLinkId = response.getPaymentLinkId();
                log.info("PayOS payment link created for orderCode: {}", orderCode);
            } catch (Exception ex) {
                log.warn("PayOS checkout failed orderCode={} failureType={}", orderCode, ex.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Không thể khởi tạo thanh toán. Vui lòng thử lại.");
            }
        } else {
            // Demo checkout is allowed only by the explicit server-side flag checked above.
            checkoutUrl = properties.getReturnUrl() + "?orderCode=" + orderCode + "&mock=true";
            paymentLinkId = "MOCK-" + orderCode;
            log.info("Demo checkout created for orderCode: {}", orderCode);
        }

        PaymentOrderEntity order = new PaymentOrderEntity(orderCode, userId, plan, amount, description);
        order.setSource(source);
        order.setCheckoutUrl(checkoutUrl);
        order.setQrCode(qrCode);
        order.setPayosPaymentLinkId(paymentLinkId);
        // Lưu hạn cho cả DEMO: job vẫn bỏ qua DEMO, nhưng FE dùng chung một màn hình đếm ngược.
        order.setExpiresAt(expiresAt);

        PaymentOrderEntity winner = transactions.execute(status -> {
            users.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            PaymentOrderEntity competitor = findReusable(userId, plan, source, now);
            if (competitor != null) {
                return competitor;
            }
            orderRepository.save(order);
            return null;
        });

        if (winner != null) {
            // Một request song song đã kịp tạo đơn cho đúng gói này. Link vừa tạo thành thừa.
            log.info("Discarding a duplicate checkout link orderCode={}", orderCode);
            cancelAtGatewayQuietly(orderCode, source, SUPERSEDED_REASON);
            return toPaymentResponse(winner);
        }

        return toPaymentResponse(order);
    }

    /**
     * Đơn PENDING còn hạn của đúng người, đúng gói, đúng nguồn.
     *
     * <p>Tạo đơn mới khi đã có một đơn như vậy nghĩa là sinh thêm một {@code order_code} và một
     * link nữa trong khi link cũ vẫn trả được: hai link sống cho một ý định mua là đường ngắn nhất
     * tới cảnh tiền về mà không biết gắn vào đâu.</p>
     *
     * <p>Cố ý không dùng unique partial index trên {@code (user_id) where status='PENDING'}: writer
     * cũ trên DB dùng chung có thể đã vi phạm sẵn nên migration sẽ fail, và khi index đứng được thì
     * một cú click đúp biến thành 500 vi phạm ràng buộc thay vì tái sử dụng êm.</p>
     */
    /**
     * Mã đơn hàng: giây epoch <strong>đầy đủ</strong> nhân 1000 cộng một hậu tố ngẫu nhiên.
     *
     * <p>Bản cũ lấy {@code (epochSec % 1_000_000) * 1000 + random(100..999)}. Phần {@code % 1e6}
     * khiến phần giờ của mã quay vòng đúng 11,57 ngày, trong khi {@code order_code} có UNIQUE và
     * PayOS cũng đòi mã duy nhất vĩnh viễn: hai đơn cách nhau một chu kỳ, rơi trúng cùng giây và
     * cùng hậu tố là một lần vi phạm ràng buộc, tức 500 ngay lúc người dùng bấm thanh toán. Xác
     * suất ấy tăng theo bình phương lượng đơn mỗi ngày, nên nó là thứ chỉ nổ khi đã có người dùng
     * thật.</p>
     *
     * <p>Bỏ phép chia dư thì phần giây không bao giờ lặp lại nữa. Giá trị hiện ở mức 1,8e12, còn
     * rất xa trần 9007199254740991 của PayOS. Chỉ còn một cửa trùng là hai đơn trong cùng một giây
     * bốc trúng cùng hậu tố; vòng lặp dưới đây tra bảng trước khi gọi cổng nên cửa đó thu lại còn
     * đúng hai lệnh insert song song trong cùng mili giây, và UNIQUE vẫn là chốt chặn cuối.</p>
     */
    private long nextOrderCode(OffsetDateTime now) {
        long seconds = now.toEpochSecond();
        for (int attempt = 0; attempt < ORDER_CODE_ATTEMPTS; attempt++) {
            long candidate = seconds * 1000L + ThreadLocalRandom.current().nextLong(1000L);
            if (!orderRepository.existsByOrderCode(candidate)) {
                return candidate;
            }
        }
        log.warn("Could not allocate a free orderCode after {} attempts", ORDER_CODE_ATTEMPTS);
        throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Không tạo được mã đơn hàng. Vui lòng thử lại.");
    }

    private PaymentOrderEntity findReusable(String userId, PlanTier plan, PaymentSource source, OffsetDateTime now) {
        return orderRepository.findPendingByUser(userId, source).stream()
                .filter(order -> order.getPlanTier() == plan)
                .filter(order -> order.getCheckoutUrl() != null)
                .filter(order -> order.getExpiresAt() != null && order.getExpiresAt().isAfter(now))
                .findFirst()
                .orElse(null);
    }

    /**
     * Đóng các đơn chờ của gói khác trước khi mở đơn mới.
     *
     * <p>Đổi ý sang gói khác là chuyện thường; để cả hai link cùng sống thì người dùng quét nhầm mã
     * cũ là trả tiền cho gói họ vừa bỏ. Huỷ hỏng thì không ghi gì ở DB: đơn vẫn PENDING và job đối
     * soát sẽ lo, còn đánh dấu CANCELLED trong khi link bên cổng còn sống mới là thứ tạo ra khoản
     * tiền không gắn được vào đâu.</p>
     */
    private void supersedePendingOrders(String userId, PaymentSource source, PlanTier plan) {
        List<Long> stale = transactions.execute(status ->
                orderRepository.findPendingByUser(userId, source).stream()
                        .filter(order -> order.getPlanTier() != plan)
                        .map(PaymentOrderEntity::getOrderCode)
                        .toList());
        if (stale == null) {
            return;
        }
        for (Long orderCode : stale) {
            if (cancelAtGatewayQuietly(orderCode, source, SUPERSEDED_REASON)) {
                markCancelled(orderCode);
            }
        }
    }

    /** Huỷ link ở cổng mà không làm hỏng luồng đang chạy. Đơn DEMO không có link nào để huỷ. */
    private boolean cancelAtGatewayQuietly(Long orderCode, PaymentSource source, String reason) {
        if (source != PaymentSource.REAL) {
            return true;
        }
        try {
            payOS.paymentRequests().cancel(orderCode, reason);
            return true;
        } catch (RuntimeException failure) {
            log.warn("Could not cancel link at gateway orderCode={} failureType={}",
                    orderCode, failure.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Huỷ một đơn theo yêu cầu của chính chủ đơn.
     *
     * <p>Thứ tự bắt buộc: đọc và kiểm trạng thái, đóng transaction, gọi cổng, rồi mở transaction
     * ghi và kiểm lại. Gọi cổng khi đang giữ khoá dòng là cách biến một lần PayOS chậm thành một
     * hàng đợi khoá; ghi trước khi gọi cổng là cách tạo ra một đơn CANCELLED với link còn trả
     * được.</p>
     *
     * <p>Đơn đã EXPIRED hoặc CANCELLED trả 200 chứ không 409: FE đua với job đối soát, và người
     * dùng bấm huỷ một đơn vừa tự hết hạn thì kết quả họ muốn đã có sẵn. 409 dành riêng cho đơn đã
     * PAID, nơi xung đột là thật vì tiền đã vào.</p>
     */
    public PaymentOrderResponse cancelOrder(String userId, Long orderCode) {
        Boolean cancelAtGateway = transactions.execute(status -> {
            PaymentOrderEntity order = orderRepository.findByOrderCodeAndUserIdForUpdate(orderCode, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
            if (order.getStatus() == PaymentOrderStatus.PAID) {
                throw new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_CANCELLABLE);
            }
            if (order.getStatus() != PaymentOrderStatus.PENDING) {
                return false;
            }
            if (order.getSource() != PaymentSource.REAL) {
                order.setStatus(PaymentOrderStatus.CANCELLED);
                orderRepository.save(order);
                return false;
            }
            return true;
        });

        if (Boolean.TRUE.equals(cancelAtGateway)) {
            try {
                payOS.paymentRequests().cancel(orderCode, USER_CANCEL_REASON);
            } catch (RuntimeException failure) {
                // Hay gặp nhất là link vừa đổi trạng thái giữa hai lời gọi, kể cả vừa được trả.
                // Không ghi gì: đường đồng bộ sẽ đẩy đơn lên đúng trạng thái của cổng.
                log.warn("PayOS refused to cancel orderCode={} failureType={}",
                        orderCode, failure.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.PAYMENT_FAILED,
                        "Chưa huỷ được đơn thanh toán. Vui lòng thử lại.");
            }
            markCancelled(orderCode);
        }

        return transactions.execute(status -> orderRepository.findByOrderCodeAndUserId(orderCode, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND)));
    }

    /** Ghi CANCELLED dưới khoá dòng, chỉ khi đơn vẫn còn chờ. */
    private void markCancelled(Long orderCode) {
        transactions.executeWithoutResult(status -> orderRepository.findByOrderCodeForUpdate(orderCode)
                .filter(order -> order.getStatus() == PaymentOrderStatus.PENDING)
                .ifPresent(order -> {
                    order.setStatus(PaymentOrderStatus.CANCELLED);
                    orderRepository.save(order);
                }));
    }

    /**
     * Webhook là tín hiệu kích, không phải nguồn sự thật.
     *
     * <p>Mã thành công trên một đơn còn chờ thì kích hoạt ngay: payload đã qua kiểm chữ ký và đã
     * khớp số tiền, không có lý do bắt người dùng chờ thêm một vòng hỏi cổng. Mọi mã khác chỉ được
     * ghi log. PayOS gửi mã lỗi cho một lần chuyển khoản hỏng, còn link thì vẫn sống và vẫn trả
     * được, nên biến nó thành CANCELLED là tự tay giết một đơn đang lành.</p>
     *
     * <p>Đơn đã kết sổ mà vẫn nhận webhook thì giao cho dispatcher đọc lại cổng: với hạn checkout
     * 5 phút, tiền về sau khi đóng sổ là chuyện xảy ra đều đặn chứ không phải ngoại lệ hiếm. Bản
     * thân webhook vẫn không hồi sinh được đơn nào; thứ quyết định là câu trả lời của
     * {@code paymentRequests().get(orderCode)}.</p>
     */
    public void handlePayOSWebhook(Webhook webhook) {
        log.info("Received PayOS webhook with code: {}", webhook.getCode());

        WebhookData data;
        if (properties.isConfigured()) {
            try {
                data = payOS.webhooks().verify(webhook);
            } catch (InvalidSignatureException e) {
                log.error("PayOS webhook verification failed: invalid signature", e);
                throw new BusinessException(ErrorCode.PAYMENT_SIGNATURE_INVALID);
            } catch (Exception e) {
                log.warn("PayOS webhook verification failed failureType={}", e.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Không xác thực được kết quả thanh toán.");
            }
        } else {
            // Public unsigned webhooks must never activate even DEMO orders.
            throw new BusinessException(ErrorCode.PAYMENT_SIGNATURE_INVALID);
        }

        if (data == null || data.getOrderCode() == null) {
            log.warn("PayOS webhook received with missing order data");
            return;
        }

        Long orderCode = data.getOrderCode();
        boolean success = "00".equals(webhook.getCode()) && "00".equals(data.getCode());

        Boolean settled = transactions.execute(status -> {
            Optional<PaymentOrderEntity> orderOpt = orderRepository.findByOrderCodeForUpdate(orderCode);
            if (orderOpt.isEmpty()) {
                log.warn("Payment order not found for orderCode: {}", orderCode);
                return false;
            }

            PaymentOrderEntity order = orderOpt.get();
            if (order.getSource() != PaymentSource.REAL
                    || data.getAmount() == null || data.getAmount().longValue() != order.getAmount()
                    || (data.getCurrency() != null && !order.getCurrency().equalsIgnoreCase(data.getCurrency()))
                    || (data.getPaymentLinkId() != null && order.getPayosPaymentLinkId() != null
                        && !order.getPayosPaymentLinkId().equals(data.getPaymentLinkId()))) {
                throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Giao dịch không khớp đơn thanh toán.");
            }
            if (order.getStatus() == PaymentOrderStatus.PAID) {
                log.info("Payment order {} already marked as PAID. Skipping duplicate webhook.", orderCode);
                return false;
            }
            if (order.getStatus() != PaymentOrderStatus.PENDING) {
                return success;
            }
            if (success) {
                activation.activate(order);
            } else {
                log.warn("Payment order {} received non-success code: {}", orderCode, webhook.getCode());
            }
            return false;
        });

        if (Boolean.TRUE.equals(settled)) {
            reconciliation.reconcileSettled(orderCode);
        }
    }

    @Transactional
    public void confirmMockPayment(String userId, Long orderCode) {
        if (!properties.isDemoEnabled()) throw new BusinessException(ErrorCode.FORBIDDEN, "Thanh toán demo đã bị tắt.");
        // Lọc chủ sở hữu trong truy vấn, cùng quy ước với getOrderDetails.
        PaymentOrderEntity order = orderRepository.findByOrderCodeAndUserIdForUpdate(orderCode, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        if (order.getSource() != PaymentSource.DEMO) throw new BusinessException(ErrorCode.FORBIDDEN);

        if (order.getStatus() == PaymentOrderStatus.PAID) {
            return;
        }
        if (order.getStatus() != PaymentOrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Trạng thái đơn không cho phép xác nhận.");
        }

        activation.activate(order);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getUserSubscription(String userId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<UserSubscriptionEntity> subOpt = subscriptionRepository
                .findFirstByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE);

        if (subOpt.isPresent() && subOpt.get().isActive()) {
            UserSubscriptionEntity sub = subOpt.get();
            long daysRemaining = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), sub.getEndDate().toLocalDate()));
            return new SubscriptionResponse(
                    sub.getId(),
                    sub.getPlanTier(),
                    sub.getPlanTier().getDisplayName(),
                    sub.getStatus(),
                    true,
                    sub.getStartDate(),
                    sub.getEndDate(),
                    daysRemaining
            );
        }

        return new SubscriptionResponse(
                null,
                PlanTier.FREE,
                PlanTier.FREE.getDisplayName(),
                SubscriptionStatus.ACTIVE,
                true,
                null,
                null,
                0L
        );
    }

    /**
     * Chi tiết một đơn, kèm một lượt đồng bộ với PayOS khi đơn còn chờ.
     *
     * <p>Không còn {@code @Transactional} và không còn khoá dòng. Bản cũ giữ cả hai suốt lời gọi
     * HTTP sang cổng, nên mỗi lần FE poll là một dòng bị khoá cho tới khi PayOS trả lời, và chỉ cần
     * đoán đúng {@code orderCode} là giữ được khoá đó.</p>
     *
     * <p>Cooldown nằm trong chính dispatcher ({@code reconcile_attempt_at}), nên FE poll hai giây
     * một lần cũng chỉ sinh ra một lời gọi cổng mỗi chu kỳ. Sau giai đoạn này đường đồng bộ nhận đủ
     * cả PAID, CANCELLED lẫn EXPIRED chứ không riêng PAID như trước.</p>
     */
    public PaymentOrderResponse getOrderDetails(String userId, Long orderCode) {
        // Lọc chủ sở hữu ngay trong truy vấn: đơn của người khác và đơn không tồn tại cho ra cùng
        // một 404, không lộ việc đơn có tồn tại (docs/security.md mục 2). Giữ PAYMENT_ORDER_NOT_FOUND
        // thay vì AccessGuard vì helper đó ném cứng RESOURCE_NOT_FOUND, sẽ đổi luôn mã lỗi của ca
        // "đơn không tồn tại" vốn chẳng liên quan gì tới IDOR.
        PaymentOrderEntity order = readOrder(userId, orderCode);

        if (order.getStatus() == PaymentOrderStatus.PENDING
                && order.getSource() == PaymentSource.REAL
                && properties.isConfigured()) {
            try {
                reconciliation.reconcilePending(orderCode);
            } catch (RuntimeException failure) {
                // Cổng không trả lời thì vẫn trả trạng thái đang lưu, không dựng 500 cho một màn
                // hình chỉ đang hiển thị tiến độ.
                log.warn("Could not sync PayOS status orderCode={} failureType={}",
                        orderCode, failure.getClass().getSimpleName());
            }
            order = readOrder(userId, orderCode);
        }

        return toResponse(order);
    }

    private PaymentOrderEntity readOrder(String userId, Long orderCode) {
        return transactions.execute(status -> orderRepository.findByOrderCodeAndUserId(orderCode, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND)));
    }

    /**
     * Lịch sử giao dịch của chính người gọi, phân trang và lọc ở tầng truy vấn.
     *
     * <p>Cố ý <strong>không</strong> đồng bộ PayOS cho các đơn PENDING như endpoint chi tiết làm:
     * một trang toàn đơn dở dang sẽ thành ngần ấy lời gọi mạng trong một request. Muốn trạng thái
     * mới nhất của một đơn thì gọi {@code GET /payments/orders/{orderCode}}.</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<PaymentOrderResponse> getUserOrders(String userId,
                                                            int page,
                                                            int pageSize,
                                                            PaymentOrderStatus status,
                                                            PlanTier planTier,
                                                            LocalDate fromDate,
                                                            LocalDate toDate) {
        // Khoảng ngày nhận theo ngày lịch UTC và bao trọn cả ngày toDate (nửa mở: < toDate + 1 ngày).
        OffsetDateTime from = fromDate == null ? null : fromDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = toDate == null ? null : toDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Page<PaymentOrderEntity> found = orderRepository.findAll(
                historyFilter(userId, status, planTier, from, to),
                PageRequest.of(page - 1, pageSize, HISTORY_SORT));

        return PageResponse.of(found.getContent().stream().map(this::toResponse).toList(),
                page, pageSize, found.getTotalElements());
    }

    /**
     * Bộ lọc lịch sử: chỉ thêm điều kiện cho bộ lọc thực sự có giá trị.
     *
     * <p>Mẫu {@code (:param is null or cot = :param)} <strong>không dùng được</strong> với tham số
     * thời gian trên PostgreSQL. Null {@code OffsetDateTime} đi xuống driver thành
     * {@code setNull(idx, Types.TIMESTAMP[_WITH_TIMEZONE])}, và pgjdbc ánh xạ cả hai mã đó sang oid
     * {@code UNSPECIFIED}; trong vế {@code $1 is null} thì Postgres không còn chỗ nào để suy kiểu và
     * trả {@code 42P18 could not determine data type of parameter}, tức là HTTP 500 cho mọi request
     * không gửi đủ cả hai ngày. Tham số enum/varchar không dính vì chúng có oid thật.</p>
     *
     * <p>H2 ở profile test không ép suy luận kiểu nên nó <em>không</em> bắt được lỗi này. Đừng coi
     * test xanh là bằng chứng cho đường đọc lịch sử.</p>
     */
    private static Specification<PaymentOrderEntity> historyFilter(String userId,
                                                                   PaymentOrderStatus status,
                                                                   PlanTier planTier,
                                                                   OffsetDateTime from,
                                                                   OffsetDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (planTier != null) {
                predicates.add(cb.equal(root.get("planTier"), planTier));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private PaymentResponse toPaymentResponse(PaymentOrderEntity order) {
        return new PaymentResponse(
                order.getOrderCode(),
                order.getPlanTier(),
                order.getPlanTier().getDisplayName(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getCheckoutUrl(),
                order.getQrCode(),
                order.getDescription(),
                order.getExpiresAt()
        );
    }

    private PaymentOrderResponse toResponse(PaymentOrderEntity order) {
        return new PaymentOrderResponse(
                order.getId(),
                order.getOrderCode(),
                order.getPlanTier(),
                order.getPlanTier().getDisplayName(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getSource(),
                order.getCheckoutUrl(),
                order.getQrCode(),
                order.getDescription(),
                order.getPaidAt(),
                order.getExpiresAt(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
