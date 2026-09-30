package vn.nutrimom.consultation.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import vn.nutrimom.consultation.domain.ConsultationReviewEntity;

public interface ConsultationReviewRepository
        extends JpaRepository<ConsultationReviewEntity, String>,
        JpaSpecificationExecutor<ConsultationReviewEntity> {

    boolean existsByRequestId(String requestId);

    Optional<ConsultationReviewEntity> findByRequestId(String requestId);

}
