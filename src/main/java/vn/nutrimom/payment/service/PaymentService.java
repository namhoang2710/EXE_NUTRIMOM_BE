package vn.nutrimom.payment.service;

import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_PAYMENT_PAID;
import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_PAYMENT_RENEWED;

import jakarta.persistence.criteria.Predicate;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
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
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.notification.service.AdminNotificationEvents;
import vn.nutrimom.common.api.PageResponse;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.payment.config.PayOSProperties;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PaymentSource;
import vn.nutrimom.auth.repository.UserRepository;
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
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkItem;
import vn.payos.model.webhooks.Webhook;
import vn.payos.model.webhooks.WebhookData;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

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
    private final PaymentOrderRepository orderRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final UserRepository users;

    private final AdminNotificationEvents adminEvents;

    public PaymentService(PayOS payOS,
                          PayOSProperties properties,
                          PaymentOrderRepository orderRepository,
                          UserSubscriptionRepository subscriptionRepository, UserRepository users, AdminNotificationEvents adminEvents) {
        this.adminEvents = adminEvents;
        this.payOS = payOS;
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.users = users;
    }

    @Transactional
    public PaymentResponse createSubscriptionPayment(String userId, CreatePaymentRequest request) {
        if (request.planTier() == null || request.planTier() == PlanTier.FREE) {
            throw new BusinessException(ErrorCode.INVALID_PLAN_TIER, "Gói Miễn phí không yêu cầu thanh toán.");
        }

        PlanTier plan = request.planTier();
        if (!properties.isConfigured() && !properties.isDemoEnabled()) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Cổng thanh toán chưa sẵn sàng.");
        }
        int amount = plan.getPrice();

        // Sinh mã đơn hàng duy nhất dạng số nguyên dương (PayOS yêu cầu orderCode kiểu số)
        long timestampSec = System.currentTimeMillis() / 1000L;
        long randomSuffix = ThreadLocalRandom.current().nextLong(100L, 999L);
        long orderCode = (timestampSec % 1_000_000L) * 1000L + randomSuffix;

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
        order.setSource(properties.isConfigured() ? PaymentSource.REAL : PaymentSource.DEMO);
        order.setCheckoutUrl(checkoutUrl);
        order.setQrCode(qrCode);
        order.setPayosPaymentLinkId(paymentLinkId);
        orderRepository.save(order);

        return new PaymentResponse(
                orderCode,
                plan,
                plan.getDisplayName(),
                amount,
                order.getCurrency(),
                order.getStatus(),
                checkoutUrl,
                qrCode,
                description
        );
    }

    @Transactional
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
        Optional<PaymentOrderEntity> orderOpt = orderRepository.findByOrderCodeForUpdate(orderCode);
        if (orderOpt.isEmpty()) {
            log.warn("Payment order not found for orderCode: {}", orderCode);
            return;
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
            return;
        }
        if (order.getStatus() != PaymentOrderStatus.PENDING) return;

        if ("00".equals(webhook.getCode()) && "00".equals(data.getCode())) {
            activateOrderAndSubscription(order);
        } else {
            log.warn("Payment order {} received non-success code: {}", orderCode, webhook.getCode());
            order.setStatus(PaymentOrderStatus.CANCELLED);
            orderRepository.save(order);
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

        activateOrderAndSubscription(order);
    }

    private void activateOrderAndSubscription(PaymentOrderEntity order) {
        // Serialize distinct orders for the same user as well as duplicate deliveries of one order.
        users.findByIdForUpdate(order.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        order.setStatus(PaymentOrderStatus.PAID);
        order.setPaidAt(now);
        // The DB upgrade trigger must see this ledger entry before entitlement changes.
        orderRepository.saveAndFlush(order);

        // Kích hoạt hoặc gia hạn Subscription theo đúng thời hạn của gói đã mua
        Period term = order.getPlanTier().getTerm();
        Optional<UserSubscriptionEntity> activeSubOpt = subscriptionRepository
                .findFirstByUserIdAndStatusOrderByEndDateDesc(order.getUserId(), SubscriptionStatus.ACTIVE);

        boolean renewal = activeSubOpt.isPresent() && activeSubOpt.get().isActive();
        if (renewal) {
            UserSubscriptionEntity existing = activeSubOpt.get();
            existing.setEndDate(existing.getEndDate().plus(term));
            // Chỉ nâng tier, không bao giờ hạ. Ghi đè vô điều kiện sẽ khiến người đang giữ PLAN_399K
            // mà mua thêm một gói rẻ hơn mất trọn phần thời hạn còn lại ở quyền lợi cao. Thứ tự khai
            // báo PlanTier là thứ hạng gói nên compareTo nói đúng cao thấp.
            if (order.getPlanTier().compareTo(existing.getPlanTier()) > 0) {
                existing.setPlanTier(order.getPlanTier());
            }
            subscriptionRepository.save(existing);
            log.info("Extended subscription for user: {} with plan: {} until {}",
                    order.getUserId(), existing.getPlanTier(), existing.getEndDate());
        } else {
            UserSubscriptionEntity newSub = new UserSubscriptionEntity(
                    order.getUserId(),
                    order.getPlanTier(),
                    now,
                    now.plus(term)
            );
            subscriptionRepository.save(newSub);
            log.info("Created new subscription for user: {} with plan: {} until {}",
                    order.getUserId(), order.getPlanTier(), newSub.getEndDate());
        }
        adminEvents.record(renewal ? ADMIN_PAYMENT_RENEWED : ADMIN_PAYMENT_PAID,
                order.getId(), Long.toString(order.getOrderCode()),
                (renewal ? "Gia hạn " : "Mua ") + order.getPlanTier().getDisplayName()
                        + " thành công. Giá trị: " + order.getAmount() + " VND.");
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

    @Transactional
    public PaymentOrderResponse getOrderDetails(String userId, Long orderCode) {
        // Lọc chủ sở hữu ngay trong truy vấn: đơn của người khác và đơn không tồn tại cho ra cùng
        // một 404, không lộ việc đơn có tồn tại (docs/security.md mục 2). Giữ PAYMENT_ORDER_NOT_FOUND
        // thay vì AccessGuard vì helper đó ném cứng RESOURCE_NOT_FOUND, sẽ đổi luôn mã lỗi của ca
        // "đơn không tồn tại" vốn chẳng liên quan gì tới IDOR.
        PaymentOrderEntity order = orderRepository.findByOrderCodeAndUserIdForUpdate(orderCode, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        // Nếu đơn hàng đang PENDING và PayOS đã cấu hình, đồng bộ trạng thái mới nhất từ PayOS
        if (order.getStatus() == PaymentOrderStatus.PENDING && order.getSource() == PaymentSource.REAL && properties.isConfigured()) {
            try {
                PaymentLink linkInfo = payOS.paymentRequests().get(orderCode);
                if (linkInfo != null && "PAID".equalsIgnoreCase(String.valueOf(linkInfo.getStatus()))
                        && linkInfo.getAmount() != null && linkInfo.getAmount().longValue() == order.getAmount()) {
                    activateOrderAndSubscription(order);
                }
            } catch (Exception ex) {
                log.warn("Could not sync PayOS status orderCode={} failureType={}",
                        orderCode, ex.getClass().getSimpleName());
            }
        }

        return toResponse(order);
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
     * <p>H2 ở profile test không ép suy luận kiểu nên nó <em>không</em> bắt được lỗi này — đừng coi
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
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
