package vn.nutrimom.calendar.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.calendar.dto.CalendarDtos.CreateReminderFromRecordRequest;
import vn.nutrimom.calendar.dto.CalendarDtos.ReminderResponse;
import vn.nutrimom.calendar.service.CalendarReminderService;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;

/**
 * Nút "thêm vào lịch nhắc nhở" nằm trên màn hình một hồ sơ y tế.
 *
 * <p>Route đặt dưới {@code /medical-records} vì đó là ngữ cảnh người dùng đang đứng, và vì quyền
 * truy cập được kiểm bằng chính query owner-scoped của hồ sơ — không mở thêm một bề mặt IDOR mới
 * dưới {@code /calendar}. Controller lại nằm trong module calendar để module hồ sơ y tế không phải
 * phụ thuộc ngược vào lịch.</p>
 *
 * <p>Dùng chung prefix với {@code MedicalRecordController} là hợp lệ: không cặp (method, pattern)
 * nào trùng nhau. Mọi mapping thêm sau này phải nằm trong {@code /{id}/reminders}.</p>
 */
@RestController
@RequestMapping("/api/v1/medical-records")
@Tag(name = "Calendar", description = "Lịch tổng hợp và nhắc nhở của người dùng")
@SecurityRequirement(name = "bearerAuth")
public class MedicalRecordReminderController {

    private final CalendarReminderService reminders;

    public MedicalRecordReminderController(CalendarReminderService reminders) {
        this.reminders = reminders;
    }

    @PostMapping("/{id}/reminders")
    @Operation(summary = "Tạo nhắc nhở (tái khám, khám định kỳ) từ một hồ sơ y tế")
    public ResponseEntity<ApiResponse<ReminderResponse>> create(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody CreateReminderFromRecordRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.success(
                reminders.createFromMedicalRecord(jwt.getSubject(), id, request)));
    }
}
