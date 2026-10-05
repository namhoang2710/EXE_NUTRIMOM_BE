package vn.nutrimom.calendar.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.calendar.domain.CalendarSource;
import vn.nutrimom.calendar.dto.SharedCalendarDtos.SharedCalendarEventsResponse;
import vn.nutrimom.calendar.dto.SharedCalendarDtos.SharedCalendarMonthResponse;
import vn.nutrimom.calendar.service.SharedCalendarService;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;

/**
 * Lịch của mẹ bầu cho thành viên gia đình xem.
 *
 * <p>Tách khỏi {@code CalendarController} có chủ đích: ở đó "id trong JWT" luôn là chủ sở hữu dữ
 * liệu, còn ở đây id trong JWT là người xem và dữ liệu thuộc về người khác. Nhét hai mô hình uỷ
 * quyền vào chung một handler là cách nhanh nhất để một hôm nào đó lọt mất một nhánh kiểm tra.</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/family/shared-calendar")
@Tag(name = "Family sharing", description = "Pregnancy family groups and scoped sharing")
@SecurityRequirement(name = "bearerAuth")
public class SharedCalendarController {

    private final SharedCalendarService sharedCalendar;

    public SharedCalendarController(SharedCalendarService sharedCalendar) {
        this.sharedCalendar = sharedCalendar;
    }

    /**
     * @param types lọc theo nguồn; bỏ trống = toàn bộ nguồn được phép. Giá trị nằm ngoài phần được
     *              phép (ví dụ {@code MEDICAL_RECORD}) chỉ làm kết quả rỗng, không phải 403 — trả
     *              403 ở đây sẽ biến endpoint thành nơi dò "mẹ có hồ sơ y tế hay không".
     */
    @GetMapping("/events")
    @Operation(summary = "Lịch của mẹ bầu cho thành viên có scope SHARED_CALENDAR")
    public ApiResponse<SharedCalendarEventsResponse> events(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String timezone,
            @RequestParam(required = false) Set<CalendarSource> types) {
        return ApiResponses.success(
                sharedCalendar.events(jwt.getSubject(), from, to, timezone, types));
    }

    @GetMapping("/month")
    @Operation(summary = "Lưới tháng của mẹ bầu cho thành viên có scope SHARED_CALENDAR")
    public ApiResponse<SharedCalendarMonthResponse> month(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Min(1970) @Max(2200) int year,
            @RequestParam int month,
            @RequestParam(required = false) String timezone) {
        return ApiResponses.success(
                sharedCalendar.month(jwt.getSubject(), year, month, timezone));
    }
}
