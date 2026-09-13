package vn.nutrimom.reference.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.reference.dto.ReferenceRoleResponse;
import vn.nutrimom.reference.service.ReferenceDataService;

@RestController
@RequestMapping("/api/v1/reference-data")
@Tag(name = "Reference data", description = "Safe client-facing reference values")
@SecurityRequirement(name = "bearerAuth")
public class ReferenceDataController {
    private final ReferenceDataService service;

    public ReferenceDataController(ReferenceDataService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    @Operation(summary = "List self-selectable roles; privileged roles are excluded")
    public ApiResponse<List<ReferenceRoleResponse>> roles() {
        return ApiResponses.success(service.getSelfSelectableRoles());
    }
}
