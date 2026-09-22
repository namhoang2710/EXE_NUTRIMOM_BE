package vn.nutrimom.reference.service;

import java.util.List;
import org.springframework.stereotype.Service;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.reference.dto.ReferenceRoleResponse;
import vn.nutrimom.reference.dto.ReferenceSpecialtyResponse;

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

    /** Chuyên khoa để FE render lựa chọn khi tìm/đặt chuyên gia. */
    public List<ReferenceSpecialtyResponse> getSpecialties() {
        return List.of(
                new ReferenceSpecialtyResponse(Specialty.PSYCHOLOGY.name(), "Tâm lý", 1),
                new ReferenceSpecialtyResponse(Specialty.OBSTETRICS.name(), "Sản khoa", 2),
                new ReferenceSpecialtyResponse(Specialty.HEALTH.name(), "Sức khỏe", 3));
    }
}
