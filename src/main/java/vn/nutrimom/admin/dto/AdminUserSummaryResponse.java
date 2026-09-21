package vn.nutrimom.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record AdminUserSummaryResponse(
        long totalUsers,
        long activeUsers,
        long newUsersThisMonth,
        CurrentPeriod currentPeriod,
        List<MonthlyProgress> monthlyProgress) {

    public record CurrentPeriod(int month, int year, String label, String timezone) {
    }

    public record MonthlyProgress(
            int month,
            String monthLabel,
            long newUsers,
            long cumulativeUsers,
            @JsonInclude(JsonInclude.Include.ALWAYS) Double percentage,
            boolean future) {
    }
}
