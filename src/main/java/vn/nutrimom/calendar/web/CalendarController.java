package vn.nutrimom.calendar.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.calendar.domain.CalendarSource;
import vn.nutrimom.calendar.domain.ReminderStatus;
import vn.nutrimom.calendar.dto.CalendarDtos.CalendarEventItem;
import vn.nutrimom.calendar.dto.CalendarDtos.CalendarMonthDay;
import vn.nutrimom.calendar.dto.CalendarDtos.CreateReminderRequest;
import vn.nutrimom.calendar.dto.CalendarDtos.ReminderResponse;
import vn.nutrimom.calendar.dto.CalendarDtos.UpdateReminderRequest;
import vn.nutrimom.calendar.dto.CalendarDtos.UpsertOccurrenceRequest;
import vn.nutrimom.calendar.service.CalendarQueryService;
import vn.nutrimom.calendar.service.CalendarReminderService;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;

/** Lịch của mẹ bầu và các nhắc nhở do chính họ tạo (spec mục 10). */
@Validated
@RestController
@RequestMapping("/api/v1/calendar")
@Tag(name = "Calendar", description = "Lịch tổng hợp và nhắc nhở của người dùng")
@SecurityRequirement(name = "bearerAuth")
public class CalendarController {

    private final CalendarQueryService calendar;
    private final CalendarReminderService reminders;

    public CalendarController(CalendarQueryService calendar, CalendarReminderService reminders) {
        this.calendar = calendar;
        this.reminders = reminders;
    }

    /**
     * @param types lọc theo nguồn; bỏ trống = cả ba. Giá trị lạ trả 400
     *              {@code INVALID_REQUEST_PARAMETER} theo đúng hành vi chung của binding enum.
     */
    @GetMapping("/events")
    @Operation(summary = "Danh sách mốc đã trộn từ hồ sơ y tế, buổi tư vấn đã xác nhận và nhắc nhở")
    public ApiResponse<List<CalendarEventItem>> events(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String timezone,
            @RequestParam(required = false) Set<CalendarSource> types) {
        return ApiResponses.success(calendar.events(jwt.getSubject(), from, to, timezone, types));
    }

    @GetMapping("/month")
    @Operation(summary = "Lưới tháng: chỉ các ngày có mốc, kèm số lượng và loại nguồn")
    public ApiResponse<List<CalendarMonthDay>> month(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Min(1970) @Max(2200) int year,
            @RequestParam int month,
            @RequestParam(required = false) String timezone) {
        return ApiResponses.success(calendar.month(jwt.getSubject(), year, month, timezone));
    }

    @GetMapping("/reminders")
    @Operation(summary = "Nhắc nhở của chính mình; mặc định từ hôm nay tới 90 ngày sau")
    public ApiResponse<List<ReminderResponse>> listReminders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ReminderStatus status,
            @RequestParam(required = false) String timezone) {
        return ApiResponses.success(reminders.list(jwt.getSubject(), from, to, status, timezone));
    }

    @PostMapping("/reminders")
    @Operation(summary = "Tạo một nhắc nhở tự do")
    public ResponseEntity<ApiResponse<ReminderResponse>> createReminder(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateReminderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(reminders.create(jwt.getSubject(), request)));
    }

    @GetMapping("/reminders/{id}")
    @Operation(summary = "Chi tiết một nhắc nhở; của người khác trả 404")
    public ApiResponse<ReminderResponse> getReminder(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ApiResponses.success(reminders.get(jwt.getSubject(), id));
    }

    @PatchMapping("/reminders/{id}")
    @Operation(summary = "Sửa nhắc nhở; cần version. Chuyển sang DONE thì trả kèm mốc gợi ý kế tiếp")
    public ApiResponse<ReminderResponse> updateReminder(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody UpdateReminderRequest request) {
        return ApiResponses.success(reminders.update(jwt.getSubject(), id, request));
    }

    @PutMapping("/reminders/{id}/occurrences")
    @Operation(summary = "Đánh dấu một lần lặp cụ thể là đã làm / bỏ qua; status null = bỏ đánh dấu")
    public ApiResponse<ReminderResponse> markOccurrence(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody UpsertOccurrenceRequest request) {
        return ApiResponses.success(reminders.upsertOccurrence(jwt.getSubject(), id, request));
    }

    @DeleteMapping("/reminders/{id}")
    @Operation(summary = "Xoá mềm một nhắc nhở")
    public ResponseEntity<Void> deleteReminder(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        reminders.delete(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }
}
