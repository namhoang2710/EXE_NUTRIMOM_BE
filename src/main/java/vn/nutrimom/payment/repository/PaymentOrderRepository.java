package vn.nutrimom.payment.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vn.nutrimom.payment.domain.PaymentOrderEntity;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrderEntity, String> {

    Optional<PaymentOrderEntity> findByOrderCode(Long orderCode);

    List<PaymentOrderEntity> findAllByUserIdOrderByCreatedAtDesc(String userId);
    List<PaymentOrderEntity> findTop3ByUserIdOrderByCreatedAtDesc(String userId);

    Optional<PaymentOrderEntity> findByIdAndUserId(String id, String userId);
}
