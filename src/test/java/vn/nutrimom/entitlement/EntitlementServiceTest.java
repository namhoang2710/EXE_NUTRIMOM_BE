package vn.nutrimom.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.entitlement.domain.PlanFeature;
import vn.nutrimom.entitlement.service.EntitlementService;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.payment.domain.SubscriptionStatus;
import vn.nutrimom.payment.domain.UserSubscriptionEntity;
import vn.nutrimom.payment.repository.UserSubscriptionRepository;

/**
 * Phân giải gói — phần dễ sai nhất của task 17.
 *
 * <p>Mọi ngả "không còn hiệu lực" đều phải rơi về FREE, vì không có job nào chuyển dòng hết hạn sang
 * {@code EXPIRED}: hết hạn chỉ được tính lúc đọc.</p>
 */
@org.springframework.test.context.bean.override.mockito.MockitoBean(types = {
        vn.nutrimom.assistant.service.GroqAssistantClient.class,
        vn.nutrimom.consultation.video.LiveKitClient.class})
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EntitlementServiceTest {

    @Autowired EntitlementService entitlements;
    @Autowired UserRepository users;
    @Autowired UserSubscriptionRepository subscriptions;

    @Test
    void userWithoutAnySubscriptionIsFree() {
        String userId = createUser("0931000001");

        assertThat(entitlements.currentPlan(userId)).isEqualTo(PlanTier.FREE);
        assertThat(entitlements.has(userId, PlanFeature.FAMILY_SHARING)).isFalse();
        assertThat(entitlements.has(userId, PlanFeature.CALENDAR_REMINDERS)).isFalse();
        assertThat(entitlements.snapshotOf(userId).paid()).isFalse();
    }

    @Test
    void activeSubscriptionGrantsItsPlan() {
        String userId = createUser("0931000002");
        PlanFixtures.grant(subscriptions, userId, PlanTier.PLAN_99K);

        assertThat(entitlements.currentPlan(userId)).isEqualTo(PlanTier.PLAN_99K);
        assertThat(entitlements.has(userId, PlanFeature.CALENDAR_REMINDERS)).isTrue();
        // 99K không mua được liên kết gia đình — đúng như bảng giá.
        assertThat(entitlements.has(userId, PlanFeature.FAMILY_SHARING)).isFalse();
        assertThat(entitlements.snapshotOf(userId).paid()).isTrue();
    }

    /** Dòng vẫn mang status ACTIVE nhưng đã ngoài cửa sổ ngày — vẫn phải mất quyền. */
    @Test
    void expiredSubscriptionFallsBackToFreeEvenThoughItsRowStillSaysActive() {
        String userId = createUser("0931000003");
        PlanFixtures.grantExpired(subscriptions, userId, PlanTier.PLAN_399K);

        assertThat(subscriptions.findFirstByUserIdAndStatusOrderByEndDateDesc(
                userId, SubscriptionStatus.ACTIVE)).isPresent();
        assertThat(entitlements.currentPlan(userId)).isEqualTo(PlanTier.FREE);
        assertThat(entitlements.has(userId, PlanFeature.FAMILY_SHARING)).isFalse();
    }

    @Test
    void cancelledSubscriptionFallsBackToFree() {
        String userId = createUser("0931000004");
        UserSubscriptionEntity subscription = subscriptions.saveAndFlush(
                new UserSubscriptionEntity(userId, PlanTier.PLAN_399K,
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(1),
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(30)));
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscriptions.saveAndFlush(subscription);

        assertThat(entitlements.currentPlan(userId)).isEqualTo(PlanTier.FREE);
    }

    private String createUser(String phone) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName("Entitlement " + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        return users.saveAndFlush(user).getId();
    }
}
