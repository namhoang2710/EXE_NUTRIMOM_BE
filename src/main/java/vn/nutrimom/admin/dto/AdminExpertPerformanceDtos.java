package vn.nutrimom.admin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import vn.nutrimom.admin.dto.AdminOperationsDtos.Period;

/** Aggregate-only data: no patient identifiers, comments, clinical notes or room credentials. */
public final class AdminExpertPerformanceDtos {
    private AdminExpertPerformanceDtos() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Value(long numerator,long denominator,BigDecimal percent,String status,long missingEvidence) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Axis(String key,String label,Value current,Value previous,BigDecimal changePercentagePoints) {}
    public record SelectedExpert(String userId,String fullName,String specialty,String expertStatus,
                                 String accountStatus,OffsetDateTime profileCreatedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(long totalExperts,long activeExperts,long eligibleConsultations,long completedConsultations,
                          long observedConsultations,long ratingCount,BigDecimal averageRating,
                          Long departuresCount,String departuresStatus,
                          Long absentConsultations,Long lateConsultations,Long unknownAttendanceConsultations) {
        public Summary(long totalExperts,long activeExperts,long eligibleConsultations,long completedConsultations,
                long observedConsultations,long ratingCount,BigDecimal averageRating,Long departuresCount,String departuresStatus) {
            this(totalExperts,activeExperts,eligibleConsultations,completedConsultations,observedConsultations,ratingCount,
                averageRating,departuresCount,departuresStatus,null,null,null);
        }
    }
    public record Rules(int onTimeGraceMinutes,int satisfiedMinimumStars,String cohortBasis,
                        String attendanceEvidence,String comparisonBasis,int acceptanceSlaMinutes,
                        String acceptanceCohortBasis,String acceptanceScope,String returningCustomerBasis,
                        String acceptanceOpeningTime,String acceptanceClosingTime,String acceptanceTimezone) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Performance(OffsetDateTime snapshotAt,String timezone,Period period,String specialty,
                              SelectedExpert expert,Rules rules,List<Axis> axes,Summary summary,String realtimeRevision,
                              int calculationVersion,DataCoverage dataCoverage) {
        public Performance(OffsetDateTime snapshotAt,String timezone,Period period,String specialty,SelectedExpert expert,
                Rules rules,List<Axis> axes,Summary summary,String realtimeRevision) {
            this(snapshotAt,timezone,period,specialty,expert,rules,axes,summary,realtimeRevision,1,null);
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DataCoverage(java.time.LocalDate earliestScheduledDate,OffsetDateTime trackingStartedAt,
                               boolean previousPeriodComplete,String comparisonStatus) { }
}
