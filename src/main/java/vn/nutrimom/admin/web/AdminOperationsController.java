package vn.nutrimom.admin.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vn.nutrimom.admin.dto.AdminOperationsDtos.*;
import vn.nutrimom.admin.service.AdminOperationsService;
import vn.nutrimom.admin.service.AdminReportService;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.consultation.dto.ExpertDtos.AdminExpertResponse;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name="Admin operations",description="Admin-only operational snapshots, appointments, collections and reports")
@SecurityRequirement(name="bearerAuth")
public class AdminOperationsController {
    private final AdminOperationsService service;
    private final AdminReportService reports;
    public AdminOperationsController(AdminOperationsService service,AdminReportService reports) {this.service=service;this.reports=reports;}
    @GetMapping("/dashboard")
    @Operation(summary="Get bounded operational dashboard snapshot")
    public ApiResponse<Dashboard> dashboard(@RequestParam(required=false) LocalDate fromDate,@RequestParam(required=false) LocalDate toDate) {
        return ApiResponses.success(service.dashboard(fromDate,toDate));
    }
    @GetMapping("/appointments")
    @Operation(summary="Read consultation requests and schedule, without clinical notes or room access")
    public ApiResponse<Page<Appointment>> appointments(@Parameter(schema=@Schema(minimum="1",maximum="1000000")) @RequestParam(defaultValue="1") int page,
            @Parameter(schema=@Schema(minimum="1",maximum="100")) @RequestParam(defaultValue="20") int pageSize,@Parameter(schema=@Schema(maxLength=100)) @RequestParam(required=false) String q,
            @RequestParam(required=false) String status,@Parameter(schema=@Schema(allowableValues={"PSYCHOLOGY","OBSTETRICS","HEALTH"})) @RequestParam(required=false) String specialty,
            @RequestParam(required=false) String expertUserId,@RequestParam(required=false) LocalDate fromDate,
            @RequestParam(required=false) LocalDate toDate,@RequestParam(required=false) String dateBasis,
            @RequestParam(defaultValue="false") boolean attentionOnly) {
        return ApiResponses.success(service.appointments(page,pageSize,q,status,specialty,expertUserId,fromDate,toDate,dateBasis,attentionOnly));
    }
    @GetMapping("/appointments/{requestId}")
    public ApiResponse<Appointment> appointment(@PathVariable String requestId) {return ApiResponses.success(service.appointment(requestId));}
    @GetMapping("/experts/page")
    public ApiResponse<Page<AdminExpertResponse>> experts(@Parameter(schema=@Schema(minimum="1",maximum="1000000")) @RequestParam(defaultValue="1") int page,
            @Parameter(schema=@Schema(minimum="1",maximum="100")) @RequestParam(defaultValue="20") int pageSize,@Parameter(schema=@Schema(maxLength=100)) @RequestParam(required=false) String q,
            @Parameter(schema=@Schema(allowableValues={"PSYCHOLOGY","OBSTETRICS","HEALTH"})) @RequestParam(required=false) String specialty,@RequestParam(required=false) String status) {
        return ApiResponses.success(service.experts(page,pageSize,q,specialty,status));
    }
    @GetMapping("/revenue/summary")
    public ApiResponse<Revenue> revenue(@RequestParam(required=false) LocalDate fromDate,
            @RequestParam(required=false) LocalDate toDate,@Parameter(schema=@Schema(allowableValues={"REAL","DEMO","LEGACY_UNKNOWN","ALL"},defaultValue="ALL")) @RequestParam(required=false) String source,
            @Parameter(schema=@Schema(allowableValues={"PERIOD","ALL_TIME"})) @RequestParam(required=false) String range) {
        return ApiResponses.success(service.revenue(fromDate,toDate,source,range));
    }
    @GetMapping("/payment-orders")
    public ApiResponse<Page<PaymentOrder>> payments(@Parameter(schema=@Schema(minimum="1",maximum="1000000")) @RequestParam(defaultValue="1") int page,
            @Parameter(schema=@Schema(minimum="1",maximum="100")) @RequestParam(defaultValue="20") int pageSize,@Parameter(schema=@Schema(maxLength=100)) @RequestParam(required=false) String q,
            @Parameter(schema=@Schema(allowableValues={"FREE","PLAN_99K","PLAN_399K"})) @RequestParam(required=false) String planTier,@RequestParam(required=false) String status,
            @Parameter(schema=@Schema(allowableValues={"REAL","DEMO","LEGACY_UNKNOWN","ALL"},defaultValue="ALL")) @RequestParam(required=false) String source,@RequestParam(required=false) LocalDate fromDate,
            @RequestParam(required=false) LocalDate toDate,@RequestParam(required=false) String dateBasis,
            @Parameter(schema=@Schema(allowableValues={"PERIOD","ALL_TIME"})) @RequestParam(required=false) String range) {
        return ApiResponses.success(service.payments(page,pageSize,q,planTier,status,source,fromDate,toDate,dateBasis,range));
    }
    @GetMapping("/payment-orders/{orderCode}")
    public ApiResponse<PaymentOrder> payment(@PathVariable String orderCode) {return ApiResponses.success(service.payment(orderCode));}
    @GetMapping("/subscriptions")
    public ApiResponse<Page<Subscription>> subscriptions(@Parameter(schema=@Schema(minimum="1",maximum="1000000")) @RequestParam(defaultValue="1") int page,
            @Parameter(schema=@Schema(minimum="1",maximum="100")) @RequestParam(defaultValue="20") int pageSize,@Parameter(schema=@Schema(maxLength=100)) @RequestParam(required=false) String q,
            @Parameter(schema=@Schema(allowableValues={"FREE","PLAN_99K","PLAN_399K"})) @RequestParam(required=false) String planTier,@Parameter(schema=@Schema(allowableValues={"ACTIVE","EXPIRED","CANCELLED","NOT_STARTED"})) @RequestParam(required=false) String effectiveStatus,
            @RequestParam(required=false) Integer expiringWithinDays) {
        return ApiResponses.success(service.subscriptions(page,pageSize,q,planTier,effectiveStatus,expiringWithinDays));
    }
    @GetMapping("/reports/{type}")
    public ApiResponse<Report> report(@Parameter(schema=@Schema(allowableValues={"OPERATIONS","USERS_SUBSCRIPTIONS","REVENUE","EXPERT_PERFORMANCE"})) @PathVariable String type,@RequestParam(required=false) LocalDate fromDate,
            @RequestParam(required=false) LocalDate toDate,@Parameter(schema=@Schema(allowableValues={"REAL","DEMO","LEGACY_UNKNOWN","ALL"},defaultValue="ALL")) @RequestParam(required=false) String source,
            @Parameter(schema=@Schema(minimum="1",maximum="1000000")) @RequestParam(defaultValue="1") int page,@Parameter(schema=@Schema(minimum="1",maximum="100")) @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) String specialty,@RequestParam(required=false) String expertUserId,
            @Parameter(description="3: schedule attendance and entry-action punctuality; 2: provider presence") @RequestParam(defaultValue="2") int calculationVersion) {
        return ApiResponses.success(reports.report(type,fromDate,toDate,source,page,pageSize,specialty,expertUserId,calculationVersion));
    }
    @GetMapping("/reports/{type}/export")
    public ResponseEntity<byte[]> export(@Parameter(schema=@Schema(allowableValues={"OPERATIONS","USERS_SUBSCRIPTIONS","REVENUE","EXPERT_PERFORMANCE"})) @PathVariable String type,@RequestParam(required=false) LocalDate fromDate,
            @RequestParam(required=false) LocalDate toDate,@Parameter(schema=@Schema(allowableValues={"REAL","DEMO","LEGACY_UNKNOWN","ALL"},defaultValue="ALL")) @RequestParam(required=false) String source,@RequestParam(required=false) String specialty,
            @RequestParam(required=false) String expertUserId,@RequestParam(defaultValue="2") int calculationVersion) {
        byte[] csv=reports.export(type,fromDate,toDate,source,specialty,expertUserId,calculationVersion);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("nutrimom-"+type.toLowerCase(java.util.Locale.ROOT)+".csv").build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(csv);
    }
}
