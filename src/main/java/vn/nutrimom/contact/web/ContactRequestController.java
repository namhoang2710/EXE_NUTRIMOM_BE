package vn.nutrimom.contact.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.contact.dto.ContactDtos.ContactPage;
import vn.nutrimom.contact.dto.ContactDtos.ContactRequestResponse;
import vn.nutrimom.contact.dto.ContactDtos.CreateContactRequest;
import vn.nutrimom.contact.service.ContactRequestService;

@Validated
@RestController
@RequestMapping("/api/v1/contact-requests")
@Tag(name = "Contact requests", description = "User gửi thắc mắc tới hộp thư hỗ trợ, theo dõi và huỷ")
@SecurityRequirement(name = "bearerAuth")
public class ContactRequestController {
    private final ContactRequestService service;

    public ContactRequestController(ContactRequestService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Gửi thắc mắc (chủ đề + nội dung); tối đa 3 yêu cầu đang chờ mỗi user")
    public ResponseEntity<ApiResponse<ContactRequestResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(service.create(jwt.getSubject(), request)));
    }

    @GetMapping
    @Operation(summary = "Lịch sử yêu cầu hỗ trợ của chính mình, mới nhất trước (phân trang)")
    public ApiResponse<ContactPage<ContactRequestResponse>> listOwn(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ApiResponses.success(service.listOwn(jwt.getSubject(), page, pageSize));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết một yêu cầu hỗ trợ của mình")
    public ApiResponse<ContactRequestResponse> detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ApiResponses.success(service.detail(jwt.getSubject(), id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Huỷ yêu cầu (chỉ khi đang chờ xử lý)")
    public ApiResponse<ContactRequestResponse> cancel(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ApiResponses.success(service.cancel(jwt.getSubject(), id));
    }
}
