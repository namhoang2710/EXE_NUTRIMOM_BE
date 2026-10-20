package vn.nutrimom.community.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.community.domain.CommunityReport;
import vn.nutrimom.community.domain.ReportStatus;

public interface CommunityReportRepository extends JpaRepository<CommunityReport, String> {

    /** 1 = báo cáo mới, 0 = người này đã báo cáo bài này (unique post_id + reporter_id). */
    @Modifying
    @Query(value = """
            insert into app.community_reports (id, post_id, reporter_id, reason, note, status, created_at, updated_at)
            values (:id, :postId, :reporterId, :reason, :note, 'OPEN', :now, :now) on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("postId") String postId,
            @Param("reporterId") String reporterId, @Param("reason") String reason, @Param("note") String note,
            @Param("now") OffsetDateTime now);

    @Query("""
            select r from CommunityReport r
            where (:status is null or r.status = :status)
              and (r.createdAt < :at or (r.createdAt = :at and r.id < :id))
            order by r.createdAt desc, r.id desc
            """)
    List<CommunityReport> findAdminPage(@Param("status") ReportStatus status, @Param("at") OffsetDateTime at,
            @Param("id") String id, Pageable window);

    /** [post_id, số báo cáo OPEN] cho một trang bài — một câu GROUP BY thay vì COUNT từng bài. */
    @Query("""
            select r.postId, count(r) from CommunityReport r
            where r.postId in :postIds and r.status = vn.nutrimom.community.domain.ReportStatus.OPEN
            group by r.postId
            """)
    List<Object[]> countOpenByPost(@Param("postIds") Collection<String> postIds);

    /** post_id → số báo cáo OPEN; bài không có báo cáo mở thì không có trong map. */
    default Map<String, Long> openCountsByPost(Collection<String> postIds) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : countOpenByPost(postIds)) counts.put((String) row[0], ((Number) row[1]).longValue());
        return counts;
    }

    /** Đóng mọi báo cáo còn mở của bài khi admin gỡ bài. */
    @Modifying
    @Query("""
            update CommunityReport r
            set r.status = vn.nutrimom.community.domain.ReportStatus.ACTION_TAKEN, r.handledBy = :adminId,
                r.handledAt = :now, r.resolutionNote = :note, r.updatedAt = :now
            where r.postId = :postId and r.status = vn.nutrimom.community.domain.ReportStatus.OPEN
            """)
    int closeOpenForPost(@Param("postId") String postId, @Param("adminId") String adminId,
            @Param("note") String note, @Param("now") OffsetDateTime now);
}
