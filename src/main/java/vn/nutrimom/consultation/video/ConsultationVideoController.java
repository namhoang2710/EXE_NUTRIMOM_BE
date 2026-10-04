package vn.nutrimom.consultation.video;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.consultation.service.ConsultationRequestService;

@RestController
public class ConsultationVideoController {
    private final ConsultationVideoService video;
    private final ConsultationRequestService requests;
    public ConsultationVideoController(ConsultationVideoService video, ConsultationRequestService requests) {
        this.video = video; this.requests = requests;
    }
    @GetMapping("/api/v1/consultation-requests/{id}/video")
    public ResponseEntity<ApiResponse<VideoDtos.RoomInfo>> info(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponses.success(video.info(jwt.getSubject(), id)));
    }
    @PostMapping("/api/v1/consultation-requests/{id}/video/join")
    public ResponseEntity<ApiResponse<VideoDtos.JoinRoom>> join(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponses.success(video.join(jwt.getSubject(), id)));
    }
    @PreAuthorize("hasRole('EXPERT')")
    @PostMapping("/api/v1/consultation-requests/{id}/video/complete")
    public ApiResponse<?> complete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        video.info(jwt.getSubject(), id);
        return ApiResponses.success(requests.complete(jwt.getSubject(), id));
    }
    @PostMapping(value = "/api/v1/consultation-video/webhook", consumes = {"application/webhook+json", "application/json"})
    public ResponseEntity<Void> webhook(@RequestBody String rawBody,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        video.webhook(rawBody, authorization);
        return ResponseEntity.noContent().build();
    }
}
