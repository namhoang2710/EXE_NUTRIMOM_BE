package vn.nutrimom.payment.service;

import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_PAYMENT_PAID;
import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_PAYMENT_RENEWED;

import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.notification.service.AdminNotificationEvents;
import vn.nutrimom.payment.domain.PaymentOrderEntity;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.SubscriptionStatus;
import vn.nutrimom.payment.domain.UserSubscriptionEntity;
import vn.nutrimom.payment.repository.PaymentOrderRepository;
import vn.nutrimom.payment.repository.UserSubscriptionRepository;

/**
 * Ghi nhận một đơn đã trả tiền và kích hoạt (hoặc gia hạn) gói hội viên tương ứng.
 *
 * <p>Tách khỏi {@code PaymentService} thành bean riêng vì cả webhook, endpoint chi tiết và job đối
 * soát đều phải đi qua đúng một đoạn mã này. Để nguyên trong {@code PaymentService} thì dispatcher
 * của job phải phụ thuộc ngược lại vào service, trong khi service đã phụ thuộc vào dispatcher — một
 * vòng bean mà Spring từ chối dựng.</p>
 *
 * <p>Method ở đây cố ý <strong>không</strong> {@code @Transactional}: nó luôn chạy ghép vào
 * transaction của bên gọi, nơi đơn hàng đã được khoá dòng và kiểm lại trạng thái. Mở transaction
 * riêng ở đây sẽ tách lần kiểm đó khỏi lần ghi, đúng khe hở mà exactly-once dựa vào.</p>
 */
@Service
public class SubscriptionActivationService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionActivationService.class);

    private final PaymentOrderRepository orderRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final UserRepository users;
    private final AdminNotificationEvents adminEvents;

    public SubscriptionActivationService(PaymentOrderRepository orderRepository,
                                         UserSubscriptionRepository subscriptionRepository,
                                         UserRepository users,
                                         AdminNotificationEvents adminEvents) {
        this.orderRepository = orderRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.users = users;
        this.adminEvents = adminEvents;
    }

    public void activate(PaymentOrderEntity order) {
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
}
