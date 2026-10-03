package vn.nutrimom.payment.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.nutrimom.payment.domain.SubscriptionStatus;
import vn.nutrimom.payment.domain.UserSubscriptionEntity;

@Repository
public interface UserSubscriptionRepository extends JpaRepository<UserSubscriptionEntity, String> {

    @Query("SELECT s FROM UserSubscriptionEntity s WHERE s.userId = :userId AND s.status = :status ORDER BY s.endDate DESC")
    List<UserSubscriptionEntity> findActiveSubscriptions(@Param("userId") String userId, @Param("status") SubscriptionStatus status);

    Optional<UserSubscriptionEntity> findFirstByUserIdAndStatusOrderByEndDateDesc(String userId, SubscriptionStatus status);
    @Query("select s from UserSubscriptionEntity s where s.userId = :userId and s.status = :status and s.startDate <= :now and s.endDate >= :now order by s.endDate desc")
    List<UserSubscriptionEntity> findCurrentForAssistant(@Param("userId") String userId, @Param("status") SubscriptionStatus status,
            @Param("now") java.time.OffsetDateTime now, org.springframework.data.domain.Pageable pageable);

    List<UserSubscriptionEntity> findAllByUserIdOrderByCreatedAtDesc(String userId);
}
