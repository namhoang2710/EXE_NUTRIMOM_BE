package vn.nutrimom.knowledge.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.nutrimom.common.api.*;
import vn.nutrimom.knowledge.dto.ArticleDtos.MediaUpload;
import vn.nutrimom.knowledge.service.ArticleMediaService;

@RestController
@RequestMapping("/api/v1/admin/knowledge/media")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class ArticleMediaController {
    private final ArticleMediaService service;
    public ArticleMediaController(ArticleMediaService service) { this.service = service; }
    @PostMapping(value="/upload", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MediaUpload> upload(@RequestPart("file") MultipartFile file,
            @RequestParam(required=false) String alt, @RequestParam(required=false) String caption,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.upload(file, alt, caption, jwt.getSubject()));
    }
}
