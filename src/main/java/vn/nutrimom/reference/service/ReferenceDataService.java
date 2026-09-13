package vn.nutrimom.reference.service;

import java.util.List;
import org.springframework.stereotype.Service;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.reference.dto.ReferenceRoleResponse;

@Service
public class ReferenceDataService {
    public List<ReferenceRoleResponse> getSelfSelectableRoles() {
        return List.of(new ReferenceRoleResponse(
                UserRole.USER.name(),
                "User",
                "Standard NutriMom application user",
                List.of(),
                1));
    }
}
