package vn.nutrimom.analytics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public final class TrafficDtos {

    private TrafficDtos() {}

    public record RecordVisitRequest(
            @Size(max = 50) String source,
            @Size(max = 50) String medium,
            @Size(max = 100) String campaign,
            @Size(max = 255) @JsonProperty("landing_path") String landingPath,
            @Size(max = 500) String referrer,
            @Size(max = 100) @JsonProperty("visitor_session_id") String visitorSessionId
    ) {}

    public record RecordVisitResponse(
            boolean recorded,
            String source
    ) {}

    public record SourceMetricDto(
            String source,
            String label,
            long visits,
            long signups,
            long purchases,
            long revenue,
            @JsonProperty("conversion_rate") double conversionRate,
            @JsonProperty("percentage_of_traffic") double percentageOfTraffic
    ) {}

    public record TrafficSummaryResponse(
            @JsonProperty("date_from") LocalDate dateFrom,
            @JsonProperty("date_to") LocalDate dateTo,
            @JsonProperty("total_visits") long totalVisits,
            @JsonProperty("total_signups") long totalSignups,
            @JsonProperty("total_purchases") long totalPurchases,
            @JsonProperty("total_revenue") long totalRevenue,
            @JsonProperty("overall_conversion_rate") double overallConversionRate,
            @JsonProperty("by_source") List<SourceMetricDto> bySource,
            @JsonProperty("ga4_property_id") String ga4PropertyId,
            @JsonProperty("ga4_console_url") String ga4ConsoleUrl
    ) {}
}
