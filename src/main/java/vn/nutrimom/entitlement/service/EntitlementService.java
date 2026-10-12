package vn.nutrimom.entitlement.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.entitlement.PlanCatalog;
import vn.nutrimom.entitlement.domain.PlanDefinition;
import vn.nutrimom.entitlement.domain.PlanEntitlements;
import vn.nutrimom.entitlement.domain.PlanFeature;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.payment.domain.SubscriptionStatus;
import vn.nutrimom.payment.domain.UserSubscriptionEntity;
import vn.nutrimom.payment.repository.UserSubscriptionRepository;

/**
 * Trả lời "user này đang ở gói nào và được dùng gì".
 *
 * <p>Dùng đúng {@code findFirstByUserIdAndStatusOrderByEndDateDesc} +
 * {@link UserSubscriptionEntity#isActive()} + mặc định {@link PlanTier#FREE} như
 * {@code PaymentService.getUserSubscription}, nên hai nơi giống nhau <em>do xây dựng</em> chứ không
 * phải do trùng hợp. {@code PaymentService} và {@code AssistantAccountContextService} cố ý chưa được
 * gom về đây: chúng thuộc module của người khác, dọn sau trong một nhánh riêng.</p>
 *
 * <p><strong>Không cache.</strong> Giống {@code FamilySharingResolver}: user vừa thanh toán xong
 * phải dùng được tính năng ngay ở request kế tiếp, không phải chờ TTL hết.</p>
 */
@Service
public class EntitlementService {

    private final UserSubscriptionRepository subscriptions;
    private final PlanCatalog catalog;

    public EntitlementService(UserSubscriptionRepository subscriptions, PlanCatalog catalog) {
        this.subscriptions = subscriptions;
        this.catalog = catalog;
    }

    /** Gói đang có hiệu lực; không có hoặc đã hết hạn đều rơi về {@link PlanTier#FREE}. */
    @Transactional(readOnly = true)
    public PlanTier currentPlan(String userId) {
        return activeSubscription(userId)
                .map(UserSubscriptionEntity::getPlanTier)
                .orElse(PlanTier.FREE);
    }

    @Transactional(readOnly = true)
    public PlanEntitlements entitlementsOf(String userId) {
        return catalog.entitlementsOf(currentPlan(userId));
    }

    @Transactional(readOnly = true)
    public boolean has(String userId, PlanFeature feature) {
        return entitlementsOf(userId).has(feature);
    }

    /** Ảnh chụp gói hiện tại cho {@code /subscriptions/me} và block subscription của dashboard. */
    @Transactional(readOnly = true)
    public PlanSnapshot snapshotOf(String userId) {
        UserSubscriptionEntity subscription = activeSubscription(userId).orElse(null);
        PlanTier tier = subscription == null ? PlanTier.FREE : subscription.getPlanTier();
        PlanDefinition plan = catalog.get(tier);
        if (subscription == null) {
            return new PlanSnapshot(plan, false, null, null, 0L);
        }
        long daysRemaining = Math.max(0, ChronoUnit.DAYS.between(
                OffsetDateTime.now(ZoneOffset.UTC).toLocalDate(),
                subscription.getEndDate().toLocalDate()));
        return new PlanSnapshot(plan, true,
                subscription.getStartDate(), subscription.getEndDate(), daysRemaining);
    }

    private Optional<UserSubscriptionEntity> activeSubscription(String userId) {
        return subscriptions
                .findFirstByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE)
                .filter(UserSubscriptionEntity::isActive);
    }

    /**
     * Gói hiện tại kèm hạn dùng.
     *
     * <p>{@code paid} phân biệt "đang trả phí" với "rơi về FREE": gói FREE không có dòng
     * subscription nào nên {@code startDate}/{@code endDate} đều null.</p>
     */
    public record PlanSnapshot(
            PlanDefinition plan,
            boolean paid,
            OffsetDateTime startDate,
            OffsetDateTime endDate,
            long daysRemaining) { }
}
