package vn.nutrimom.analytics.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.analytics.domain.TrafficLogEntity;
import vn.nutrimom.analytics.domain.TrafficSource;
import vn.nutrimom.analytics.dto.TrafficDtos.*;
import vn.nutrimom.analytics.repository.TrafficLogRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class TrafficAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(TrafficAnalyticsService.class);

    private final TrafficLogRepository trafficLogRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.analytics.ga4-property-id:G-NUTRIMOM2026}")
    private String ga4PropertyId;

    @Value("${app.analytics.ga4-console-url:https://analytics.google.com/}")
    private String ga4ConsoleUrl;

    public TrafficAnalyticsService(TrafficLogRepository trafficLogRepository, JdbcTemplate jdbcTemplate) {
        this.trafficLogRepository = trafficLogRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public RecordVisitResponse recordVisit(RecordVisitRequest request, String clientIp, String userAgent, String currentUserId) {
        TrafficSource source = TrafficSource.fromString(request.source());

        String session = request.visitorSessionId() != null && !request.visitorSessionId().isBlank()
                ? request.visitorSessionId() : UUID.randomUUID().toString();
        String path = request.landingPath() != null && !request.landingPath().isBlank()
                ? request.landingPath() : "/";

        // De-duplicate same session hitting the same landing page in the last 15 minutes
        OffsetDateTime recentCutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(15);
        boolean existsRecent = trafficLogRepository.existsByVisitorSessionIdAndLandingPathAndCreatedAtAfter(
                session, path, recentCutoff);

        if (!existsRecent) {
            String ipHash = hashIp(clientIp);
            TrafficLogEntity entry = new TrafficLogEntity(
                    source,
                    request.medium(),
                    request.campaign(),
                    path,
                    request.referrer(),
                    session,
                    currentUserId,
                    ipHash,
                    userAgent
            );
            trafficLogRepository.save(entry);
            log.debug("Recorded traffic visit from source: {} for session: {}", source, session);
            return new RecordVisitResponse(true, source.name());
        }

        return new RecordVisitResponse(false, source.name());
    }

    @Transactional(readOnly = true)
    public TrafficSummaryResponse getTrafficSummary(LocalDate fromDate, LocalDate toDate) {
        LocalDate from = fromDate != null ? fromDate : LocalDate.now(ZoneOffset.UTC).minusDays(30);
        LocalDate to = toDate != null ? toDate : LocalDate.now(ZoneOffset.UTC);

        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        // 1. Total & grouped visits from traffic_logs
        Map<String, Long> visitsBySource = new LinkedHashMap<>();
        for (TrafficSource s : TrafficSource.values()) {
            visitsBySource.put(s.name(), 0L);
        }

        String visitsSql = """
            SELECT source, COUNT(*) as cnt
            FROM app.traffic_logs
            WHERE created_at >= ? AND created_at < ?
            GROUP BY source
        """;
        jdbcTemplate.query(visitsSql, (rs) -> {
            String src = rs.getString("source");
            long cnt = rs.getLong("cnt");
            visitsBySource.put(src, cnt);
        }, start, end);

        long totalVisits = visitsBySource.values().stream().mapToLong(Long::longValue).sum();

        // 2. Signups by acquisition_source in app.users
        Map<String, Long> signupsBySource = new LinkedHashMap<>();
        for (TrafficSource s : TrafficSource.values()) {
            signupsBySource.put(s.name(), 0L);
        }

        String signupsSql = """
            SELECT COALESCE(acquisition_source, 'DIRECT') as src, COUNT(*) as cnt
            FROM app.users
            WHERE created_at >= ? AND created_at < ?
            GROUP BY COALESCE(acquisition_source, 'DIRECT')
        """;
        jdbcTemplate.query(signupsSql, (rs) -> {
            String rawSrc = rs.getString("src");
            long cnt = rs.getLong("cnt");
            TrafficSource parsed = TrafficSource.fromString(rawSrc);
            signupsBySource.compute(parsed.name(), (k, v) -> (v == null ? 0L : v) + cnt);
        }, start, end);

        long totalSignups = signupsBySource.values().stream().mapToLong(Long::longValue).sum();

        // 3. Paid orders & revenue by acquisition_source (joined with users)
        Map<String, Long> purchasesBySource = new LinkedHashMap<>();
        Map<String, Long> revenueBySource = new LinkedHashMap<>();
        for (TrafficSource s : TrafficSource.values()) {
            purchasesBySource.put(s.name(), 0L);
            revenueBySource.put(s.name(), 0L);
        }

        String revenueSql = """
            SELECT COALESCE(u.acquisition_source, 'DIRECT') as src, COUNT(p.id) as purchases, COALESCE(SUM(p.amount), 0) as rev
            FROM app.payment_orders p
            JOIN app.users u ON p.user_id = u.id
            WHERE p.status = 'PAID' AND p.paid_at >= ? AND p.paid_at < ?
            GROUP BY COALESCE(u.acquisition_source, 'DIRECT')
        """;
        jdbcTemplate.query(revenueSql, (rs) -> {
            String rawSrc = rs.getString("src");
            long purchases = rs.getLong("purchases");
            long rev = rs.getLong("rev");
            TrafficSource parsed = TrafficSource.fromString(rawSrc);
            purchasesBySource.compute(parsed.name(), (k, v) -> (v == null ? 0L : v) + purchases);
            revenueBySource.compute(parsed.name(), (k, v) -> (v == null ? 0L : v) + rev);
        }, start, end);

        long totalPurchases = purchasesBySource.values().stream().mapToLong(Long::longValue).sum();
        long totalRevenue = revenueBySource.values().stream().mapToLong(Long::longValue).sum();
        double overallCr = totalVisits > 0 ? Math.round(((double) totalSignups / totalVisits * 100.0) * 100.0) / 100.0 : 0.0;

        // Build list ordered by key channels: TIKTOK, FACEBOOK, INSTAGRAM, GOOGLE, DIRECT, OTHER
        List<SourceMetricDto> metrics = new ArrayList<>();
        List<TrafficSource> displayOrder = List.of(
                TrafficSource.TIKTOK,
                TrafficSource.FACEBOOK,
                TrafficSource.INSTAGRAM,
                TrafficSource.GOOGLE,
                TrafficSource.DIRECT,
                TrafficSource.OTHER
        );

        for (TrafficSource s : displayOrder) {
            long visits = visitsBySource.getOrDefault(s.name(), 0L);
            long signups = signupsBySource.getOrDefault(s.name(), 0L);
            long purchases = purchasesBySource.getOrDefault(s.name(), 0L);
            long rev = revenueBySource.getOrDefault(s.name(), 0L);
            double cr = visits > 0 ? Math.round(((double) signups / visits * 100.0) * 100.0) / 100.0 : (signups > 0 ? 100.0 : 0.0);
            double trafficShare = totalVisits > 0 ? Math.round(((double) visits / totalVisits * 100.0) * 10.0) / 10.0 : 0.0;

            metrics.add(new SourceMetricDto(
                    s.name(),
                    s.getDisplayName(),
                    visits,
                    signups,
                    purchases,
                    rev,
                    cr,
                    trafficShare
            ));
        }

        return new TrafficSummaryResponse(
                from,
                to,
                totalVisits,
                totalSignups,
                totalPurchases,
                totalRevenue,
                overallCr,
                metrics,
                ga4PropertyId,
                ga4ConsoleUrl
        );
    }

    private String hashIp(String ip) {
        if (ip == null || ip.isBlank()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }
}
