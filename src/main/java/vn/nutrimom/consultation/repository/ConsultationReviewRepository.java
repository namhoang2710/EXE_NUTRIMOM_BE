package vn.nutrimom.consultation.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.consultation.domain.ConsultationReviewEntity;

public interface ConsultationReviewRepository
        extends JpaRepository<ConsultationReviewEntity, String> {

    boolean existsByRequestId(String requestId);

    Optional<ConsultationReviewEntity> findByRequestId(String requestId);

    List<ConsultationReviewEntity> findByExpertUserIdOrderByCreatedAtDesc(String expertUserId);
}
