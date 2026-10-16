package vn.nutrimom.payment.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.payment.domain.PaymentOrderEntity;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrderEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentOrderEntity p where p.orderCode=:code")
    Optional<PaymentOrderEntity> findByOrderCodeForUpdate(@Param("code") Long code);

    Optional<PaymentOrderEntity> findByOrderCode(Long orderCode);

    List<PaymentOrderEntity> findAllByUserIdOrderByCreatedAtDesc(String userId);
    List<PaymentOrderEntity> findTop3ByUserIdOrderByCreatedAtDesc(String userId);

    Optional<PaymentOrderEntity> findByIdAndUserId(String id, String userId);
}
