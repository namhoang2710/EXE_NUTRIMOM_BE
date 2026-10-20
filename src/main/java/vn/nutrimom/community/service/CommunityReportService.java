package vn.nutrimom.community.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.domain.CommunityPost;
import vn.nutrimom.community.domain.CommunityReport;
import vn.nutrimom.community.domain.ReportStatus;
import vn.nutrimom.community.dto.CommunityDtos.AdminReportItem;
import vn.nutrimom.community.dto.CommunityDtos.Author;
import vn.nutrimom.community.dto.CommunityDtos.ReportRequest;
import vn.nutrimom.community.dto.CommunityDtos.ReportResult;
import vn.nutrimom.community.dto.CommunityDtos.ResolveAction;
import vn.nutrimom.community.repository.CommunityPostRepository;
import vn.nutrimom.community.repository.CommunityReportRepository;

/**
 * Báo cáo bài viết. Người báo cáo chỉ xuất hiện trong API admin; chủ bài không được báo mỗi lần bị
 * báo cáo, chỉ được báo khi admin quyết định gỡ bài (thông báo không nêu ai báo cáo).
 */
@Service
public class CommunityReportService {
    private final CommunityReportRepository reports;
    private final CommunityPostRepository posts;
    private final CommunityPostService postService;
    private final CommunityModerationService moderation;
    private final CommunityMembers members;

    public CommunityReportService(CommunityReportRepository reports, CommunityPostRepository posts,
            CommunityPostService postService, CommunityModerationService moderation, CommunityMembers members) {
        this.reports = reports;
        this.posts = posts;
        this.postService = postService;
        this.moderation = moderation;
        this.members = members;
    }

    /** Báo cáo trùng không lỗi, không tạo dòng mới: {@code created=false}. */
    @Transactional
    public ReportResult report(String userId, String postId, ReportRequest request) {
        members.requireActive(userId);
        CommunityPost post = postService.requirePublic(postId);
        if (post.getAuthorId().equals(userId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Bạn không thể báo cáo bài viết của chính mình.");
        }
        String note = CommunityText.optional(request.note(), CommunityLimits.NOTE_MAX_CHARS, "note");
        int inserted = reports.insertIfAbsent(UUID.randomUUID().toString(), postId, userId, request.reason().name(),
                note, CommunityClock.now());
        return new ReportResult(inserted == 1);
    }

    @Transactional(readOnly = true)
    public CursorPage<AdminReportItem> list(ReportStatus status, String cursor, int limit) {
        CommunityLimits.requirePageSize(limit, 50);
        CommunityCursor from = CommunityCursor.descending(cursor);
        var page = CommunityCursor.window(reports.findAdminPage(status, from.at(), from.id(),
                PageRequest.of(0, limit + 1)), limit, CommunityReport::getCreatedAt, CommunityReport::getId);
        return new CursorPage<>(toItems(page.items()), page.nextCursor(), page.hasMore());
    }

    /** DISMISS chỉ đóng báo cáo này; REMOVE_POST gỡ bài và đóng mọi báo cáo còn mở của bài. */
    @Transactional
    public AdminReportItem resolve(String adminId, String reportId, ResolveAction action, String rawNote) {
        CommunityReport report = reports.findById(reportId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy báo cáo."));
        String note = CommunityText.optional(rawNote, CommunityLimits.NOTE_MAX_CHARS, "note");
        if (action == ResolveAction.REMOVE_POST) {
            // Đóng báo cáo này qua entity trước; remove() đóng các báo cáo OPEN còn lại bằng UPDATE hàng loạt.
            if (report.getStatus() == ReportStatus.OPEN) close(report, ReportStatus.ACTION_TAKEN, adminId, note);
            moderation.remove(adminId, report.getPostId(), note);
        } else if (report.getStatus() == ReportStatus.OPEN) {
            close(report, ReportStatus.DISMISSED, adminId, note);
        }
        return toItems(List.of(report)).get(0);
    }

    private void close(CommunityReport report, ReportStatus status, String adminId, String note) {
        report.setStatus(status);
        report.setHandledBy(adminId);
        report.setHandledAt(CommunityClock.now());
        report.setResolutionNote(note);
        reports.saveAndFlush(report);
    }

    private List<AdminReportItem> toItems(List<CommunityReport> page) {
        if (page.isEmpty()) return List.of();
        Set<String> postIds = page.stream().map(CommunityReport::getPostId).collect(Collectors.toSet());
        Map<String, CommunityPost> postsById = posts.findAllById(postIds).stream()
                .collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        Set<String> userIds = new HashSet<>();
        page.forEach(r -> userIds.add(r.getReporterId()));
        postsById.values().forEach(p -> userIds.add(p.getAuthorId()));
        Map<String, Author> users = members.authors(userIds);
        Map<String, Long> open = reports.openCountsByPost(postIds);
        return page.stream().map(r -> {
            CommunityPost post = postsById.get(r.getPostId());
            return new AdminReportItem(r.getId(), r.getPostId(),
                    post == null ? null : CommunityText.excerpt(post.getContent(), 200),
                    post == null ? null : post.getStatus(), post == null || post.isDeleted(),
                    post == null ? null : users.getOrDefault(post.getAuthorId(), CommunityMembers.fallback(post.getAuthorId())),
                    users.getOrDefault(r.getReporterId(), CommunityMembers.fallback(r.getReporterId())),
                    r.getReason(), r.getNote(), r.getStatus(), open.getOrDefault(r.getPostId(), 0L),
                    r.getHandledBy(), r.getHandledAt(), r.getResolutionNote(), r.getCreatedAt());
        }).toList();
    }
}
