package vn.nutrimom.admin.web;

import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.nutrimom.admin.service.AdminExpertPerformanceService;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;

@RestController
@RequestMapping("/api/v1/admin/experts/performance")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name="Admin expert performance",description="Aggregate expert KPI radar and overview, Vietnam calendar periods")
@SecurityRequirement(name="bearerAuth")
public class AdminExpertPerformanceController {
    private final AdminExpertPerformanceService service;
    private final vn.nutrimom.admin.service.ExpertScheduleKpiService schedules;
    public AdminExpertPerformanceController(AdminExpertPerformanceService service,vn.nutrimom.admin.service.ExpertScheduleKpiService schedules) {this.service=service;this.schedules=schedules;}
    @GetMapping
    @Operation(summary="Read expert KPI radar and overview (4 or 6 axes)",
            description="Defaults to month-to-date; paired dates, at most 366 days, no future dates. "
                    +"specialty: PSYCHOLOGY/OBSTETRICS/HEALTH; expertUserId: UUID. "
                    +"Use calculationVersion=3 for work-schedule attendance and server entry-action punctuality. V3 defaults to the full current calendar month (28/29/30/31 days), compares to the previous calendar month, and allows planned schedule through this month end. Versions 1/2 retain their historical definitions. "
                    +"Legacy attendance: completed or verified expert join / all due consultations. Punctuality: completed by scheduled end / all due consultations. Null percentages mean no denominator, not zero. "
                    +"includeExtendedMetrics=true adds TIMELY_ACCEPTANCE and RETURNING_CUSTOMERS; default false preserves the existing FE. "
                    +"Acceptance measures RANDOM queue delay in 08:00-20:00 Vietnam service hours, not automatic DIRECT booking. "
                    +"INACTIVE is not resignation; departures remain NOT_TRACKED.")
    public ApiResponse<?> performance(@RequestParam(required=false) LocalDate fromDate,
            @RequestParam(required=false) LocalDate toDate,@RequestParam(required=false) String specialty,
            @RequestParam(required=false) String expertUserId,
            @Parameter(description="Opt in to six axes; default four axes for existing clients")
            @RequestParam(defaultValue="false") boolean includeExtendedMetrics,
            @Parameter(description="3: work schedule + first server entry action; 2: verified provider arrival; 1: legacy")
            @RequestParam(defaultValue="1") int calculationVersion) {
        return calculationVersion==3?ApiResponses.success(schedules.snapshot(fromDate,toDate,specialty,expertUserId,includeExtendedMetrics))
                :ApiResponses.success(service.snapshot(fromDate,toDate,specialty,expertUserId,includeExtendedMetrics,calculationVersion));
    }
}
