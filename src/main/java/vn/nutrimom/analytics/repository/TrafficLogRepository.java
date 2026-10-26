package vn.nutrimom.analytics.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.nutrimom.analytics.domain.TrafficLogEntity;
import vn.nutrimom.analytics.domain.TrafficSource;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface TrafficLogRepository extends JpaRepository<TrafficLogEntity, String> {

    boolean existsByVisitorSessionIdAndLandingPathAndCreatedAtAfter(
            String visitorSessionId, String landingPath, OffsetDateTime cutoff);

    @Query("SELECT t.source, COUNT(t) FROM TrafficLogEntity t WHERE t.createdAt >= :startDate AND t.createdAt <= :endDate GROUP BY t.source")
    List<Object[]> countVisitsBySourceBetween(
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate);

    @Query("SELECT COUNT(t) FROM TrafficLogEntity t WHERE t.createdAt >= :startDate AND t.createdAt <= :endDate")
    long countTotalVisitsBetween(
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate);
}
