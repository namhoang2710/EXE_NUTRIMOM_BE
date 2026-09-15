package vn.nutrimom.knowledge.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.nutrimom.common.api.*;
import vn.nutrimom.knowledge.domain.ArticleStatus;
import vn.nutrimom.knowledge.dto.ArticleRequest;
import vn.nutrimom.knowledge.dto.ArticleDtos.*;
import vn.nutrimom.knowledge.service.KnowledgeArticleService;

@RestController
@RequestMapping("/api/v1/admin/knowledge/articles")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class AdminKnowledgeArticleController {
    private final KnowledgeArticleService service;
    public AdminKnowledgeArticleController(KnowledgeArticleService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Detail> create(@Valid @RequestBody ArticleRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.create(request, jwt.getSubject()));
    }
    @PutMapping("/{id}")
    public ApiResponse<Detail> update(@PathVariable String id, @Valid @RequestBody ArticleRequest request,
            @AuthenticationPrincipal Jwt jwt) { return ApiResponses.success(service.update(id, request, jwt.getSubject())); }
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable String id) { service.delete(id); return ApiResponses.success(true); }
    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@PathVariable String id) { return ApiResponses.success(service.adminDetail(id)); }
    @GetMapping
    public ApiResponse<Pagination<?>> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="12") int pageSize, @RequestParam(required=false) String category,
            @RequestParam(required=false) String stage, @RequestParam(required=false) String topic,
            @RequestParam(required=false) ArticleStatus status, @RequestParam(defaultValue="updatedAt:desc") String sort) {
        return ApiResponses.success(service.list(page, pageSize, category, stage, topic, false, sort, null, true, status));
    }
}
