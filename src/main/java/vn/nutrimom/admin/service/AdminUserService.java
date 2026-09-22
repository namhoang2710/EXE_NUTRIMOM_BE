package vn.nutrimom.admin.service;

import java.time.Clock;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.admin.dto.AdminUserDetailResponse;
import vn.nutrimom.admin.dto.AdminUserListItemResponse;
import vn.nutrimom.admin.dto.AdminUserPageResponse;
import vn.nutrimom.admin.dto.AdminUserSummaryResponse;
import vn.nutrimom.admin.dto.AdminUserSummaryResponse.CurrentPeriod;
import vn.nutrimom.admin.dto.AdminUserSummaryResponse.MonthlyProgress;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

@Service
public class AdminUserService {
    static final ZoneId VIETNAM_TIMEZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("createdAt", "updatedAt", "displayName", "status");

    private final UserRepository users;
    private final Clock clock;

    public AdminUserService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse list(
            int page,
            int pageSize,
            String query,
            UserStatus status,
            UserRole role,
            OnboardingStatus onboardingStatus,
            String sortBy,
            String sortDirection) {
        validatePage(page, pageSize);
        Sort ordering = ordering(sortBy, sortDirection);
        String search = normalizeSearch(query);
        Page<String> idPage = users.findAdminUserIds(
                search, status, role, onboardingStatus,
                PageRequest.of(page - 1, pageSize, ordering));

        List<AdminUserListItemResponse> items = List.of();
        if (!idPage.isEmpty()) {
            Map<String, UserEntity> usersById = users.findAllWithRolesByIdIn(idPage.getContent())
                    .stream()
                    .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
            items = idPage.getContent().stream()
                    .map(usersById::get)
                    .map(this::toListItem)
                    .toList();
        }
        return new AdminUserPageResponse(
                items, page, pageSize, idPage.getTotalElements(), idPage.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse detail(UUID userId) {
        UserEntity user = users.findByIdWithRoles(userId.toString())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND, "User not found."));
        return new AdminUserDetailResponse(
                user.getId(), user.getDisplayName(), user.getPhone(), user.getEmail(),
                user.getGender(), user.getDateOfBirth(), user.getAvatarKey(), roles(user),
                user.getStatus(), user.getOnboardingStatus(), user.getTermsAcceptedAt(),
                user.getPrivacyAcceptedAt(), user.getCreatedAt(), user.getUpdatedAt(),
                user.getVersion());
    }

    @Transactional(readOnly = true)
    public AdminUserSummaryResponse summary() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(VIETNAM_TIMEZONE);
        YearMonth currentPeriod = YearMonth.from(now);
        long totalUsers = users.count();
        long activeUsers = users.countByStatus(UserStatus.ACTIVE);

        OffsetDateTime currentMonthStart = startOf(currentPeriod);
        OffsetDateTime nextMonthStart = startOf(currentPeriod.plusMonths(1));
        long newUsersThisMonth = users.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                currentMonthStart, nextMonthStart);

        List<Long> monthlyCounts = new ArrayList<>(currentPeriod.getMonthValue());
        for (int month = 1; month <= currentPeriod.getMonthValue(); month++) {
            YearMonth period = YearMonth.of(currentPeriod.getYear(), month);
            OffsetDateTime endExclusive = month == currentPeriod.getMonthValue()
                    ? now.plusNanos(1).toOffsetDateTime()
                    : startOf(period.plusMonths(1));
            monthlyCounts.add(users.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    startOf(period), endExclusive));
        }

        long yearToDateUsers = monthlyCounts.stream().mapToLong(Long::longValue).sum();
        long cumulativeUsers = 0;
        List<MonthlyProgress> monthlyProgress = new ArrayList<>(12);
        for (int month = 1; month <= 12; month++) {
            boolean future = month > currentPeriod.getMonthValue();
            long newUsers = future ? 0 : monthlyCounts.get(month - 1);
            if (!future) cumulativeUsers += newUsers;
            monthlyProgress.add(new MonthlyProgress(
                    month,
                    Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    newUsers,
                    future ? 0 : cumulativeUsers,
                    future ? null : percentage(cumulativeUsers, yearToDateUsers),
                    future));
        }

        CurrentPeriod period = new CurrentPeriod(
                currentPeriod.getMonthValue(), currentPeriod.getYear(),
                currentPeriod.getMonthValue() + "/" + currentPeriod.getYear(),
                VIETNAM_TIMEZONE.getId());
        return new AdminUserSummaryResponse(
                totalUsers, activeUsers, newUsersThisMonth, period, List.copyOf(monthlyProgress));
    }

    private AdminUserListItemResponse toListItem(UserEntity user) {
        if (user == null) {
            throw new IllegalStateException("Paged user could not be loaded");
        }
        return new AdminUserListItemResponse(
                user.getId(), user.getDisplayName(), user.getPhone(), user.getEmail(),
                user.getAvatarKey(), roles(user), user.getStatus(), user.getOnboardingStatus(),
                user.getCreatedAt(), user.getUpdatedAt());
    }

    private List<UserRole> roles(UserEntity user) {
        return user.getRoles().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .toList();
    }

    private OffsetDateTime startOf(YearMonth period) {
        return period.atDay(1).atStartOfDay(VIETNAM_TIMEZONE).toOffsetDateTime();
    }

    private Double percentage(long cumulativeUsers, long yearToDateUsers) {
        if (yearToDateUsers == 0) return 0.0;
        return Math.round(cumulativeUsers * 1000.0 / yearToDateUsers) / 10.0;
    }

    private Sort ordering(String sortBy, String sortDirection) {
        if (!ALLOWED_SORT_FIELDS.contains(sortBy)) {
            throw invalid("sortBy must be one of: createdAt, updatedAt, displayName, status");
        }
        if (!Set.of("asc", "desc").contains(sortDirection)) {
            throw invalid("sortDirection must be asc or desc");
        }
        Sort.Direction direction = Sort.Direction.fromString(sortDirection);
        return Sort.by(direction, sortBy).and(Sort.by(Sort.Direction.ASC, "id"));
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1) throw invalid("page must be greater than or equal to 1");
        if (pageSize < 1 || pageSize > 100) {
            throw invalid("pageSize must be between 1 and 100");
        }
    }

    private String normalizeSearch(String query) {
        if (query == null || query.isBlank()) return null;
        return query.trim();
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
