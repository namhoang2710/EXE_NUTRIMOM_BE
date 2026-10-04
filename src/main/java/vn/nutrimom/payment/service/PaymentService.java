package vn.nutrimom.payment.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.payment.config.PayOSProperties;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
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

    private final PayOS payOS;
    private final PayOSProperties properties;
    private final PaymentOrderRepository orderRepository;
    private final UserSubscriptionRepository subscriptionRepository;

    public PaymentService(PayOS payOS,
                          PayOSProperties properties,
                          PaymentOrderRepository orderRepository,
                          UserSubscriptionRepository subscriptionRepository) {
        this.payOS = payOS;
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional
    public PaymentResponse createSubscriptionPayment(String userId, CreatePaymentRequest request) {
        if (request.planTier() == null || request.planTier() == PlanTier.FREE) {
            throw new BusinessException(ErrorCode.INVALID_PLAN_TIER, "Gói Miễn phí không yêu cầu thanh toán.");
        }

        PlanTier plan = request.planTier();
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
                log.info("PayOS payment link created for orderCode: {}, url: {}", orderCode, checkoutUrl);
            } catch (Exception ex) {
                log.error("Failed to create PayOS payment link for orderCode: {}", orderCode, ex);
                throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Không thể khởi tạo cổng thanh toán PayOS: " + ex.getMessage());
            }
        } else {
            // Khi chưa cấu hình key trên .env, tự động tạo URL chuyển hướng test để luồng demo không bị gián đoạn
            checkoutUrl = properties.getReturnUrl() + "?orderCode=" + orderCode + "&mock=true";
            paymentLinkId = "MOCK-" + orderCode;
            log.info("PayOS not fully configured. Using mock checkout URL: {}", checkoutUrl);
        }

        PaymentOrderEntity order = new PaymentOrderEntity(orderCode, userId, plan, amount, description);
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
                log.error("PayOS webhook verification failed", e);
                throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Lỗi xác thực webhook: " + e.getMessage());
            }
        } else {
            data = webhook.getData();
        }

        if (data == null || data.getOrderCode() == null) {
            log.warn("PayOS webhook received with missing order data");
            return;
        }

        Long orderCode = data.getOrderCode();
        Optional<PaymentOrderEntity> orderOpt = orderRepository.findByOrderCode(orderCode);
        if (orderOpt.isEmpty()) {
            log.warn("Payment order not found for orderCode: {}", orderCode);
            return;
        }

        PaymentOrderEntity order = orderOpt.get();
        if (order.getStatus() == PaymentOrderStatus.PAID) {
            log.info("Payment order {} already marked as PAID. Skipping duplicate webhook.", orderCode);
            return;
        }

        if ("00".equals(webhook.getCode())) {
            activateOrderAndSubscription(order);
        } else {
            log.warn("Payment order {} received non-success code: {}", orderCode, webhook.getCode());
            order.setStatus(PaymentOrderStatus.CANCELLED);
            orderRepository.save(order);
        }
    }

    @Transactional
    public void confirmMockPayment(Long orderCode) {
        PaymentOrderEntity order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        if (order.getStatus() == PaymentOrderStatus.PAID) {
            return;
        }

        activateOrderAndSubscription(order);
    }

    private void activateOrderAndSubscription(PaymentOrderEntity order) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        order.setStatus(PaymentOrderStatus.PAID);
        order.setPaidAt(now);
        orderRepository.save(order);

        // Kích hoạt hoặc gia hạn Subscription thêm 30 ngày cho User
        Optional<UserSubscriptionEntity> activeSubOpt = subscriptionRepository
                .findFirstByUserIdAndStatusOrderByEndDateDesc(order.getUserId(), SubscriptionStatus.ACTIVE);

        if (activeSubOpt.isPresent() && activeSubOpt.get().isActive()) {
            UserSubscriptionEntity existing = activeSubOpt.get();
            existing.setEndDate(existing.getEndDate().plusDays(30));
            existing.setPlanTier(order.getPlanTier());
            subscriptionRepository.save(existing);
            log.info("Extended subscription for user: {} with plan: {} until {}",
                    order.getUserId(), order.getPlanTier(), existing.getEndDate());
        } else {
            UserSubscriptionEntity newSub = new UserSubscriptionEntity(
                    order.getUserId(),
                    order.getPlanTier(),
                    now,
                    now.plusDays(30)
            );
            subscriptionRepository.save(newSub);
            log.info("Created new subscription for user: {} with plan: {} until {}",
                    order.getUserId(), order.getPlanTier(), newSub.getEndDate());
        }
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
        PaymentOrderEntity order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Bạn không có quyền xem đơn hàng này.");
        }

        // Nếu đơn hàng đang PENDING và PayOS đã cấu hình, đồng bộ trạng thái mới nhất từ PayOS
        if (order.getStatus() == PaymentOrderStatus.PENDING && properties.isConfigured()) {
            try {
                PaymentLink linkInfo = payOS.paymentRequests().get(orderCode);
                if (linkInfo != null && "PAID".equalsIgnoreCase(String.valueOf(linkInfo.getStatus()))) {
                    activateOrderAndSubscription(order);
                }
            } catch (Exception ex) {
                log.warn("Could not sync PayOS status for orderCode: {}", orderCode, ex);
            }
        }

        return new PaymentOrderResponse(
                order.getId(),
                order.getOrderCode(),
                order.getPlanTier(),
                order.getPlanTier().getDisplayName(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getCheckoutUrl(),
                order.getDescription(),
                order.getPaidAt(),
                order.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<PaymentOrderResponse> getUserOrders(String userId) {
        return orderRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(order -> new PaymentOrderResponse(
                        order.getId(),
                        order.getOrderCode(),
                        order.getPlanTier(),
                        order.getPlanTier().getDisplayName(),
                        order.getAmount(),
                        order.getCurrency(),
                        order.getStatus(),
                        order.getCheckoutUrl(),
                        order.getDescription(),
                        order.getPaidAt(),
                        order.getCreatedAt()
                ))
                .toList();
    }
}
