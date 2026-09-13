package vn.nutrimom.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Bảo đảm OpenAPI schema dùng snake_case, khớp với JSON runtime (Jackson SNAKE_CASE). */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSnakeCaseTest {
    @Autowired MockMvc mockMvc;

    @Test
    void requestSchemasUseSnakeCase() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // Field camelCase trong Java hiện ra snake_case trong schema.
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.display_name").exists())
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.device_id").exists())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.date_of_birth").exists())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.avatar_key").exists())
                // Không còn key camelCase.
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.displayName").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.dateOfBirth").doesNotExist());
    }

    @Test
    void newDashboardAndFamilySharingContractsArePublished() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard/mom'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard/partner'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/family-groups'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/family-groups'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/family-invitations'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/family-invitations/accept'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/family-members'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/family-members/{memberId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/family-members/{memberId}'].delete").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/dashboard/partner'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.components.schemas.CreateFamilyInvitationRequest.properties.invited_phone").exists())
                .andExpect(jsonPath("$.components.schemas.CreateFamilyInvitationRequest.properties.expires_in_hours").exists())
                .andExpect(jsonPath("$.components.schemas.CreateFamilyInvitationRequest.properties.invitedPhone").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.PartnerDashboardResponse.properties.membership_role").exists())
                .andExpect(jsonPath("$.components.schemas.PartnerDashboardResponse.properties.pregnancy_overview").exists())
                .andExpect(jsonPath("$.components.schemas.PartnerDashboardResponse.properties.membershipRole").doesNotExist());
    }
}
