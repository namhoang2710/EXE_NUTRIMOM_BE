package vn.nutrimom.dashboard.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.dashboard.dto.FamilyTaskResponse;
import vn.nutrimom.dashboard.dto.PartnerDashboardResponse;
import vn.nutrimom.dashboard.dto.PartnerPregnancyOverviewResponse;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyGroupStatus;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.repository.FamilyGroupRepository;
import vn.nutrimom.family.repository.FamilyMemberRepository;
import vn.nutrimom.family.repository.FamilyTaskRepository;
import vn.nutrimom.pregnancy.domain.PregnancyEntity;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

@Service
public class PartnerDashboardService {
    private final UserRepository users;
    private final FamilyMemberRepository members;
    private final FamilyGroupRepository groups;
    private final FamilyTaskRepository tasks;
    private final PregnancyRepository pregnancies;

    public PartnerDashboardService(UserRepository users,
                                   FamilyMemberRepository members,
                                   FamilyGroupRepository groups,
                                   FamilyTaskRepository tasks,
                                   PregnancyRepository pregnancies) {
        this.users = users;
        this.members = members;
        this.groups = groups;
        this.tasks = tasks;
        this.pregnancies = pregnancies;
    }

    @Transactional(readOnly = true)
    public PartnerDashboardResponse getDashboard(String userId) {
        users.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "The authenticated account is unavailable."));
        MembershipContext context = resolveMembership(userId);
        FamilyMemberEntity member = context.member();
        PregnancyEntity pregnancy = pregnancies.findById(context.group().getPregnancyId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                        "Shared pregnancy was not found."));

        return new PartnerDashboardResponse(
                member.getMembershipRole().name(),
                member.getScopes().contains(FamilyScope.PREGNANCY_SUMMARY)
                        ? pregnancyOverview(pregnancy) : null,
                member.getScopes().contains(FamilyScope.FAMILY_TASKS)
                        ? assignedTasks(member) : null,
                member.getScopes().contains(FamilyScope.SHARED_CALENDAR)
                        ? List.of() : null,
                member.getScopes().contains(FamilyScope.ALERTS)
                        ? List.of() : null,
                member.getScopes().contains(FamilyScope.ACTIVITY_FEED)
                        ? List.of() : null);
    }

    private MembershipContext resolveMembership(String userId) {
        return members.findByUserIdAndStatusOrderByCreatedAtDesc(
                        userId, FamilyMemberStatus.ACTIVE)
                .stream()
                .map(member -> groups.findByIdAndStatus(
                                member.getFamilyGroupId(), FamilyGroupStatus.ACTIVE)
                        .map(group -> new MembershipContext(member, group))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN, "SHARING_SCOPE_REQUIRED",
                        "An active family membership is required for the partner dashboard."));
    }

    private PartnerPregnancyOverviewResponse pregnancyOverview(PregnancyEntity pregnancy) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long gestationalDays = Math.max(0,
                ChronoUnit.DAYS.between(pregnancy.getLastMenstrualPeriod(), today));
        long week = gestationalDays / 7;
        int day = (int) (gestationalDays % 7);
        int trimester = week <= 13 ? 1 : week <= 27 ? 2 : 3;
        return new PartnerPregnancyOverviewResponse(
                pregnancy.getId(), pregnancy.getStatus().name(), week, day, trimester,
                pregnancy.getEstimatedDueDate(),
                ChronoUnit.DAYS.between(today, pregnancy.getEstimatedDueDate()),
                pregnancy.getCareFacilityName());
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

    private record MembershipContext(
            FamilyMemberEntity member, FamilyGroupEntity group) { }
}
