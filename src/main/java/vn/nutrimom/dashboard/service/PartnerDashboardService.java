package vn.nutrimom.dashboard.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.calendar.service.SharedCalendarService;
import vn.nutrimom.config.CalendarProperties;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.dashboard.dto.FamilyTaskResponse;
import vn.nutrimom.dashboard.dto.PartnerDashboardResponse;
import vn.nutrimom.dashboard.dto.PartnerPregnancyOverviewResponse;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.repository.FamilyTaskRepository;
import vn.nutrimom.family.service.FamilySharingResolver;
import vn.nutrimom.family.service.FamilySharingResolver.SharedContext;
import vn.nutrimom.notification.service.ActivityFeedService;
import vn.nutrimom.pregnancy.domain.PregnancyEntity;
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

@Service
public class PartnerDashboardService {
    private final UserRepository users;
    private final FamilySharingResolver sharing;
    private final SharedCalendarService sharedCalendar;
    private final CalendarProperties calendarProperties;
    private final FamilyTaskRepository tasks;
    private final PregnancyRepository pregnancies;
    private final ActivityFeedService activityFeed;

    /** Số dòng activity hiển thị trên dashboard partner; đủ cho một block ngắn, không phải feed đầy đủ. */
    private static final int ACTIVITY_FEED_LIMIT = 20;

    public PartnerDashboardService(UserRepository users,
                                   FamilySharingResolver sharing,
                                   SharedCalendarService sharedCalendar,
                                   CalendarProperties calendarProperties,
                                   FamilyTaskRepository tasks,
                                   PregnancyRepository pregnancies,
                                   ActivityFeedService activityFeed) {
        this.users = users;
        this.sharing = sharing;
        this.sharedCalendar = sharedCalendar;
        this.calendarProperties = calendarProperties;
        this.tasks = tasks;
        this.pregnancies = pregnancies;
        this.activityFeed = activityFeed;
    }

    @Transactional(readOnly = true)
    public PartnerDashboardResponse getDashboard(String userId) {
        users.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "The authenticated account is unavailable."));
        SharedContext context = sharing.requireMembership(userId);
        FamilyMemberEntity member = context.member();
        PregnancyEntity pregnancy = pregnancies.findById(context.group().getPregnancyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Shared pregnancy was not found."));

        return new PartnerDashboardResponse(
                member.getMembershipRole().name(),
                member.getScopes().contains(FamilyScope.PREGNANCY_SUMMARY)
                        ? pregnancyOverview(pregnancy) : null,
                member.getScopes().contains(FamilyScope.FAMILY_TASKS)
                        ? assignedTasks(member) : null,
                member.getScopes().contains(FamilyScope.SHARED_CALENDAR)
                        ? sharedCalendar.upcomingFor(context, userId,
                                calendarProperties.getDashboardReminderLimit()) : null,
                // ALERTS vẫn là chỗ trống có chủ đích: repo chưa có nguồn "cảnh báo" nào, và nguồn
                // gần nhất — bảng notifications của mẹ — mang cả tiêu đề buổi tư vấn lẫn hồ sơ y tế
                // trong title/body mà không hề được lọc theo scope. Nối vào đây là rò dữ liệu y tế
                // qua đường khác, nên để rỗng cho tới khi có nguồn đúng nghĩa.
                member.getScopes().contains(FamilyScope.ALERTS)
                        ? List.of() : null,
                member.getScopes().contains(FamilyScope.ACTIVITY_FEED)
                        ? activityFeed.forPregnancy(pregnancy.getId(), ACTIVITY_FEED_LIMIT) : null);
    }

    private PartnerPregnancyOverviewResponse pregnancyOverview(PregnancyEntity pregnancy) {
        LocalDate today = LocalDate.now(zone(pregnancy.getTimezone()));
        long gestationalDays = gestationalDays(pregnancy, today);
        long week = gestationalDays / 7;
        int day = (int) (gestationalDays % 7);
        int trimester = week <= 13 ? 1 : week <= 27 ? 2 : 3;
        return new PartnerPregnancyOverviewResponse(
                pregnancy.getId(), pregnancy.getStatus().name(), week, day, trimester,
                pregnancy.getEstimatedDueDate(),
                ChronoUnit.DAYS.between(today, pregnancy.getEstimatedDueDate()),
                pregnancy.getCareFacilityName());
    }

    private long gestationalDays(PregnancyEntity pregnancy, LocalDate today) {
        long days;
        if (pregnancy.getCalculationSource() == PregnancyCalculationSource.MANUAL
                && pregnancy.getGestationalAgeAnchorDays() != null
                && pregnancy.getGestationalAgeAnchorDate() != null) {
            days = pregnancy.getGestationalAgeAnchorDays()
                    + ChronoUnit.DAYS.between(pregnancy.getGestationalAgeAnchorDate(), today);
        } else if (pregnancy.getLastMenstrualPeriod() != null) {
            days = ChronoUnit.DAYS.between(pregnancy.getLastMenstrualPeriod(), today);
        } else if (pregnancy.getEstimatedDueDate() != null) {
            days = 280L - ChronoUnit.DAYS.between(today, pregnancy.getEstimatedDueDate());
        } else {
            days = 0;
        }
        return Math.max(0, days);
    }

    private ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneOffset.UTC;
        try { return ZoneId.of(timezone); }
        catch (DateTimeException ex) { return ZoneOffset.UTC; }
    }

    private List<FamilyTaskResponse> assignedTasks(FamilyMemberEntity member) {
        return tasks.findByFamilyGroupIdAndAssigneeIdAndDeletedAtIsNullOrderByDueAtAsc(
                        member.getFamilyGroupId(), member.getId())
                .stream()
                .map(task -> new FamilyTaskResponse(
                        task.getId(), task.getTitle(), task.getDescription(),
                        task.getPriority().name(), task.getDueAt(), task.getStatus().name(),
                        task.getVersion()))
                .toList();
    }

}
