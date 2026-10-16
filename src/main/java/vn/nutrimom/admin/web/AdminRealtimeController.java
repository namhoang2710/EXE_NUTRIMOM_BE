package vn.nutrimom.admin.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.nutrimom.admin.realtime.AdminRealtimeHub;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

@RestController
@RequestMapping("/api/v1/admin/realtime")
@Tag(name="Admin Realtime",description="SSE protocol 1: ready, update, heartbeat, access_revoked")
@SecurityRequirement(name="bearerAuth")
public class AdminRealtimeController {
    private final ObjectProvider<AdminRealtimeHub> hubs;
    public AdminRealtimeController(ObjectProvider<AdminRealtimeHub> hubs) { this.hubs=hubs; }
    @GetMapping(value="/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary="Cập nhật Dashboard và số thông báo của chính Admin qua một kết nối SSE",
            description="Bearer header required. Latest revisions are decimal strings; no event replay. "
                +"Heartbeat every 15s; closes at token expiry or after 5 minutes. "
                +"Returns 404 when disabled, 429 with Retry-After at connection capacity.")
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request,HttpServletResponse response) {
        if(request.getQueryString()!=null) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Stream không nhận query parameter.");
        var hub=hubs.getIfAvailable();
        if(hub==null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        response.setHeader("Cache-Control","no-store, no-transform");
        response.setHeader("X-Accel-Buffering","no");
        response.setCharacterEncoding("UTF-8");
        try {
            var emitter=hub.open(jwt.getSubject(),jwt.getExpiresAt());
            request.setAttribute("nutrimom.admin-sse-authorized",true);
            return emitter;
        }
        catch(BusinessException failure) {
            if(ErrorCode.RATE_LIMITED.code().equals(failure.getCode())) response.setHeader("Retry-After","30");
            throw failure;
        }
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(BusinessException.class)
    public org.springframework.http.ResponseEntity<vn.nutrimom.common.api.ApiErrorResponse> failure(BusinessException error) {
        // An SSE-only Accept header must still receive the standard JSON handshake error envelope.
        var body=new vn.nutrimom.common.api.ApiError(error.getCode(),error.getMessage(),error.getFields(),
                error.isRetryable(),org.slf4j.MDC.get("requestId"));
        return org.springframework.http.ResponseEntity.status(error.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(new vn.nutrimom.common.api.ApiErrorResponse(body));
    }
}
