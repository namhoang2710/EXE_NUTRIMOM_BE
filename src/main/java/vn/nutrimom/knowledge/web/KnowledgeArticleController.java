package vn.nutrimom.knowledge.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.nutrimom.common.api.*;
import vn.nutrimom.knowledge.dto.ArticleDtos.*;
import vn.nutrimom.knowledge.service.KnowledgeArticleService;

@RestController
@RequestMapping("/api/v1/knowledge/articles")
public class KnowledgeArticleController {
    private final KnowledgeArticleService service;
    public KnowledgeArticleController(KnowledgeArticleService service) { this.service = service; }
    @GetMapping
    public ApiResponse<Pagination<?>> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="12") int pageSize, @RequestParam(required=false) String category,
            @RequestParam(required=false) String stage, @RequestParam(required=false) String topic,
            @RequestParam(defaultValue="false") boolean savedOnly,
            @RequestParam(defaultValue="publishedAt:desc") String sort, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.list(page, pageSize, category, stage, topic, savedOnly, sort,
                jwt == null ? null : jwt.getSubject(), false, null));
    }
    @GetMapping("/{slug}")
    public ApiResponse<Detail> detail(@PathVariable String slug) { return ApiResponses.success(service.publicDetail(slug)); }
    @PutMapping("/{slug}/bookmark")
    public ApiResponse<Boolean> bookmark(@PathVariable String slug, @AuthenticationPrincipal Jwt jwt) {
        service.bookmark(slug, jwt.getSubject(), true); return ApiResponses.success(true);
    }
    @DeleteMapping("/{slug}/bookmark")
    public ApiResponse<Boolean> unbookmark(@PathVariable String slug, @AuthenticationPrincipal Jwt jwt) {
        service.bookmark(slug, jwt.getSubject(), false); return ApiResponses.success(true);
    }
}
