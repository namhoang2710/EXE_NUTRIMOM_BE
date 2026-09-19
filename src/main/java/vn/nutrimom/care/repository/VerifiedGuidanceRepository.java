package vn.nutrimom.care.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.care.domain.VerifiedGuidanceEntity;

public interface VerifiedGuidanceRepository extends JpaRepository<VerifiedGuidanceEntity, String> {
    List<VerifiedGuidanceEntity> findAllByOrderByWeekAscCreatedAtAscIdAsc();
}
