package vn.nutrimom.pregnancy.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.pregnancy.domain.PregnancyEntity;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;

public interface PregnancyRepository extends JpaRepository<PregnancyEntity, String> {
    Optional<PregnancyEntity> findByOwnerUserIdAndStatus(String ownerUserId, PregnancyStatus status);
    Optional<PregnancyEntity> findByIdAndOwnerUserId(String id, String ownerUserId);
    List<PregnancyEntity> findByOwnerUserIdOrderByCreatedAtDescIdDesc(String ownerUserId);
    List<PregnancyEntity> findByOwnerUserIdAndStatusOrderByCreatedAtDescIdDesc(String ownerUserId,
                                                                             PregnancyStatus status);
    boolean existsByOwnerUserIdAndStatus(String ownerUserId, PregnancyStatus status);
    boolean existsByOwnerUserIdAndStatusAndIdNot(String ownerUserId, PregnancyStatus status, String id);
}
