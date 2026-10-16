package vn.nutrimom.entitlement;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.payment.domain.UserSubscriptionEntity;
import vn.nutrimom.payment.repository.UserSubscriptionRepository;

/**
 * Cấp gói cho tài khoản trong test.
 *
 * <p>Từ khi task 17 bật cổng tính năng, "chưa thanh toán" nghĩa là FREE, mà FREE thì không có
 * {@code FAMILY_SHARING} lẫn {@code CALENDAR_REMINDERS}. Các bài test family/calendar đang kiểm
 * <em>hành vi của chính module đó</em> chứ không kiểm chuyện bán hàng, nên chúng cấp sẵn gói đầy đủ
 * để quay lại đúng tiền đề cũ. Phần chặn theo gói có bài riêng:
 * {@code EntitlementEnforcementIntegrationTest}.</p>
 */
public final class PlanFixtures {

    private PlanFixtures() {
    }

    /** Gói đầy đủ, còn hạn — tiền đề mặc định cho test của các module trả phí. */
    public static void grantFullPlan(UserSubscriptionRepository subscriptions, String userId) {
        grant(subscriptions, userId, PlanTier.PLAN_399K);
    }

    public static void grant(
            UserSubscriptionRepository subscriptions, String userId, PlanTier tier) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        subscriptions.saveAndFlush(
                new UserSubscriptionEntity(userId, tier, now.minusDays(1), now.plusDays(30)));
    }

    /** Gói đã hết hạn: dòng vẫn ACTIVE nhưng ngoài cửa sổ ngày, nên phải rơi về FREE. */
    public static void grantExpired(
            UserSubscriptionRepository subscriptions, String userId, PlanTier tier) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        subscriptions.saveAndFlush(
                new UserSubscriptionEntity(userId, tier, now.minusDays(60), now.minusDays(1)));
    }
}
