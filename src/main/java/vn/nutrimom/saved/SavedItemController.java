package vn.nutrimom.saved;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.saved.SavedItemService.SavedItem;
import vn.nutrimom.saved.SavedItemService.SavedType;

@RestController
@RequestMapping("/api/v1/me/saved-items")
@SecurityRequirement(name = "bearerAuth")
public class SavedItemController {
    private final SavedItemService service;

    public SavedItemController(SavedItemService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CursorPage<SavedItem>> list(@RequestParam(defaultValue = "ALL") SavedType type,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.list(jwt.getSubject(), type, cursor, limit));
    }
}
