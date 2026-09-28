package vn.nutrimom.consultation.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.consultation.domain.ExpertDayOffEntity;

public interface ExpertDayOffRepository extends JpaRepository<ExpertDayOffEntity, String> {

    boolean existsByExpertUserIdAndOffDate(String expertUserId, LocalDate offDate);

    List<ExpertDayOffEntity> findByExpertUserIdAndOffDateBetween(
            String expertUserId, LocalDate from, LocalDate to);

    void deleteByExpertUserIdAndOffDate(String expertUserId, LocalDate offDate);
}
