package vn.nutrimom.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;

/** Explicit, safe projections: no entity serialization, clinical notes or provider secrets. */
public final class AdminOperationsDtos {
    private AdminOperationsDtos() {}
    public record Period(LocalDate fromDate, LocalDate toDate,
                         LocalDate previousFromDate, LocalDate previousToDate) {}
    public record Page<T>(List<T> items, int page, int pageSize, long totalItems, int totalPages) {}
    public record Slot(String id, LocalDate slotDate, LocalTime startTime, LocalTime endTime) {}
    public record Appointment(String id, String userId, String userDisplayName,
            String expertUserId, String expertName, String specialty, String assignmentType,
            String status, Slot slot, OffsetDateTime createdAt, OffsetDateTime completedAt,
            Double waitingHours, boolean needsAttention, String attentionReason,
            OffsetDateTime updatedAt, ReviewResponse review, Integer reviewRating,
            LocalDate preferredDate,OffsetDateTime acceptanceDeadlineAt,OffsetDateTime acceptedAt,
            Integer acceptancePolicyVersion,OffsetDateTime serverTime) {}
    public record PaymentOrder(String id, String orderCode, String userId, String userDisplayName,
            String planTier, String planName, long amount, String currency, String source,
            String status, OffsetDateTime createdAt, OffsetDateTime paidAt,
            String description, OffsetDateTime updatedAt) {}
    public record Subscription(String id, String userId, String userDisplayName, String planTier,
            String status, String effectiveStatus, OffsetDateTime startDate,
            OffsetDateTime endDate, long daysRemaining) {}
    public record DailyCollection(LocalDate date, long amount, long paidOrders) {}
    public record PlanCollection(String planTier, long amount, long paidOrders) {}
    public record RevenueTotals(long collectedAmount, long paidOrders, BigDecimal averagePaidAmount,
            long previousCollectedAmount, long legacyUnknownOrders) {}
    public record Revenue(OffsetDateTime snapshotAt, String timezone,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS) Period period,
            String source, RevenueTotals summary, List<DailyCollection> dailySeries,
            List<PlanCollection> byPlan, String range) {
        public Revenue(OffsetDateTime snapshotAt, String timezone, Period period, String source,
                RevenueTotals summary, List<DailyCollection> dailySeries, List<PlanCollection> byPlan) {
            this(snapshotAt,timezone,period,source,summary,dailySeries,byPlan,"PERIOD");
        }
    }
    public record Kpis(long supportPending, long supportOverdue, long expertPending, long expertOverdue,
            long todayTotal, long todayCompleted, long todayPending, long realCollectedAmount,
            long realPaidOrders, long previousRealCollectedAmount) {}
    public record AttentionItem(String id, String kind, String sourceId, String displayName,
            String title, OffsetDateTime occurredAt, double ageHours, boolean overdue) {}
    public record AttentionTotals(long pastEndUncompleted, long waitingExpert, long supportPending) {}
    public record Attention(AttentionTotals totals, List<AttentionItem> items) {}
    public record HourlyBookings(String hour, long count) {}
    public record TodaySchedule(long totalItems, List<Appointment> items, List<HourlyBookings> hourlyBookings) {}
    public record ComparedCollection(LocalDate date, long amount, long paidOrders,
            LocalDate previousDate, long previousAmount) {}
    public record Registration(LocalDate date, long count, LocalDate previousDate, long previousCount) {}
    public record Series(List<ComparedCollection> collections, List<Registration> registrations) {}
    public record SubscriptionSnapshot(OffsetDateTime asOf,
            @com.fasterxml.jackson.annotation.JsonProperty("plan_99k_users") long plan99kUsers,
            @com.fasterxml.jackson.annotation.JsonProperty("plan_399k_users") long plan399kUsers) {}
    public record Activity(String id, String kind, String sourceId, String title,
                           OffsetDateTime occurredAt, String orderCode) {}
    public record Overview(long lifetimeRealCollectedAmount, long todayBookedAppointments, long todayBookingUsers) {}
    public record CustomerSnapshot(OffsetDateTime asOf, long totalCustomers, long freeUsers,
            @com.fasterxml.jackson.annotation.JsonProperty("plan_99k_users") long plan99kUsers,
            @com.fasterxml.jackson.annotation.JsonProperty("plan_399k_users") long plan399kUsers) {}
    public record MonthlyCollection(String month, long amount, long paidOrders) {}
    public record MonthlyRevenue(int year, String source, List<MonthlyCollection> items) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record MetricComparison(BigDecimal currentValue, BigDecimal previousValue) {}
    public record MetricComparisons(String previousMonth, int bookingBaselineDays,
            MetricComparison revenue, MetricComparison customers,
            MetricComparison appointments,
            @com.fasterxml.jackson.annotation.JsonProperty("plan_399k") MetricComparison plan399k) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record AdoptionDay(LocalDate date, OffsetDateTime asOf, java.math.BigDecimal ratioPercent) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record PlanAdoption(java.math.BigDecimal currentRatioPercent, java.math.BigDecimal previousRatioPercent,
            java.math.BigDecimal changePercentagePoints, OffsetDateTime previousAsOf,
            OffsetDateTime historyStartedAt, String comparisonStatus, List<AdoptionDay> dailySeries) {}
    public record Dashboard(OffsetDateTime snapshotAt, String timezone, Period period, Kpis kpis,
            Attention attention, TodaySchedule todaySchedule, Series series,
            SubscriptionSnapshot subscriptionSnapshot, List<Activity> recentActivity,
            Overview overview, CustomerSnapshot customerSnapshot, MonthlyRevenue monthlyRevenue,
            @com.fasterxml.jackson.annotation.JsonProperty("plan_399k_adoption") PlanAdoption plan399kAdoption,
            MetricComparisons metricComparisons, String realtimeRevision) {}
    public record RegisteredUser(String userId, String displayName, OffsetDateTime createdAt,
            String accountStatus, String currentPlanTier, OffsetDateTime subscriptionEndDate) {}
    public record Report(String type, OffsetDateTime generatedAt, String timezone, Period period,
            Map<String, Object> summary, List<?> items, int page, int pageSize,
            long totalItems, int totalPages) {}
}
