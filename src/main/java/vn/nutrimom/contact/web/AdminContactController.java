package vn.nutrimom.contact.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.contact.domain.ContactRequestStatus;
import vn.nutrimom.contact.domain.ContactTopic;
import vn.nutrimom.contact.dto.ContactDtos.AdminContactDetail;
import vn.nutrimom.contact.dto.ContactDtos.AdminContactSummary;
import vn.nutrimom.contact.dto.ContactDtos.ContactPage;
import vn.nutrimom.contact.service.AdminContactService;

@Validated
@RestController
@RequestMapping("/api/v1/admin/contact-requests")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin contact requests", description = "Admin xem hộp thư hỗ trợ, gọi điện giải đáp và đánh dấu hoàn tất")
@SecurityRequirement(name = "bearerAuth")
public class AdminContactController {
    private final AdminContactService service;

    public AdminContactController(AdminContactService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Hộp thư hỗ trợ, mới nhất trước; lọc trạng thái/chủ đề, tìm theo tên hoặc SĐT user; phân trang")
    public ApiResponse<ContactPage<AdminContactSummary>> list(
            @Parameter(description = "PENDING / COMPLETED / CANCELLED; bỏ trống = tất cả")
            @RequestParam(required = false) ContactRequestStatus status,
            @Parameter(description = "POLICY / APP_USAGE / ACCOUNT / OTHER; bỏ trống = tất cả")
            @RequestParam(required = false) ContactTopic topic,
            @Parameter(description = "Từ khoá theo tên hiển thị hoặc số điện thoại của user")
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ApiResponses.success(service.list(status, topic, q, page, pageSize));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết yêu cầu kèm thông tin liên hệ của user (tên, SĐT, email...)")
    public ApiResponse<AdminContactDetail> detail(@PathVariable String id) {
        return ApiResponses.success(service.detail(id));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Đánh dấu đã hoàn tất sau khi gọi điện giải đáp (chỉ khi đang chờ)")
    public ApiResponse<AdminContactDetail> complete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ApiResponses.success(service.complete(jwt.getSubject(), id));
    }
}
