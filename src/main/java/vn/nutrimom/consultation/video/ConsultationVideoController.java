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
    private final vn.nutrimom.consultation.service.ExpertCheckinService checkins;
    public ConsultationVideoController(ConsultationVideoService video, ConsultationRequestService requests,
            vn.nutrimom.consultation.service.ExpertCheckinService checkins) {
        this.video = video; this.requests = requests; this.checkins=checkins;
    }
    @GetMapping("/api/v1/consultation-requests/{id}/video")
    public ResponseEntity<ApiResponse<VideoDtos.RoomInfo>> info(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponses.success(video.info(jwt.getSubject(), id)));
    }
    @PostMapping("/api/v1/consultation-requests/{id}/video/join")
    public ResponseEntity<ApiResponse<VideoDtos.JoinRoom>> join(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        // Separate transaction: a provider outage must not erase the expert's entry action.
        checkins.checkInIfExpert(jwt.getSubject(),id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponses.success(video.join(jwt.getSubject(), id)));
    }
    @PreAuthorize("hasRole('EXPERT')")
    @PostMapping("/api/v1/consultation-requests/{id}/video/check-in")
    @io.swagger.v3.oas.annotations.Operation(summary="Record the expert's first entry action using server time",
            description="No request body or client timestamp. Opens 5 minutes early; on time through start +3 minutes inclusive. Idempotent, independent of LiveKit.")
    public ResponseEntity<ApiResponse<vn.nutrimom.consultation.service.ExpertCheckinService.Checkin>> checkIn(
            @AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponses.success(checkins.checkIn(jwt.getSubject(),id)));
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
