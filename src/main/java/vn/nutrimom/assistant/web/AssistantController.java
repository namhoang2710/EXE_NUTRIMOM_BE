package vn.nutrimom.assistant.web;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.nutrimom.assistant.dto.AssistantDtos.*;
import vn.nutrimom.assistant.service.*;
import vn.nutrimom.common.api.*;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {
    private final AssistantService service;
    private final AssistantStore store;
    public AssistantController(AssistantService service, AssistantStore store) { this.service = service; this.store = store; }
    @GetMapping("/status")
    public ApiResponse<Status> status(@AuthenticationPrincipal Jwt jwt) { return ApiResponses.success(service.status(jwt.getSubject())); }
    @GetMapping("/context")
    public ApiResponse<UserOverview> context(@AuthenticationPrincipal Jwt jwt) { return ApiResponses.success(service.overview(jwt.getSubject())); }
    @GetMapping("/preferences")
    public ApiResponse<Preferences> preferences(@AuthenticationPrincipal Jwt jwt) { return ApiResponses.success(store.preferences(jwt.getSubject())); }
    @PatchMapping("/preferences")
    public ApiResponse<Preferences> preferences(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdatePreferences request) { return ApiResponses.success(store.updatePreferences(jwt.getSubject(), request)); }
    @GetMapping("/conversations")
    public ApiResponse<List<Conversation>> list(@AuthenticationPrincipal Jwt jwt) { return ApiResponses.success(store.list(jwt.getSubject())); }
    @PostMapping("/conversations")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<Conversation> create(@AuthenticationPrincipal Jwt jwt) { return ApiResponses.success(store.create(jwt.getSubject())); }
    @GetMapping("/conversations/{id}")
    public ApiResponse<ConversationDetail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return ApiResponses.success(store.detail(jwt.getSubject(), id)); }
    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { store.delete(jwt.getSubject(), id); }
    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<Reply> send(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody SendMessage request) { return ApiResponses.success(service.send(jwt.getSubject(), id, request)); }
}
