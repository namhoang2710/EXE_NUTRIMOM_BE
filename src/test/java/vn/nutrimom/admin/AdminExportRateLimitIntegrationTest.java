package vn.nutrimom.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;

@SpringBootTest(properties="app.security.rate-limit.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminExportRateLimitIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    @Test void sixthRapidExportReturns429WithRetryAfterAndNoCsv() throws Exception {
        var account=new UserEntity();account.setDisplayName("Export Admin");account.setPhone("+84999666666");
        account.setRoles(Set.of(UserRole.ADMIN));account.setStatus(UserStatus.ACTIVE);
        String id=users.saveAndFlush(account).getId();
        for(int i=0;i<5;i++) {
            mvc.perform(get("/api/v1/admin/reports/REVENUE/export")
                    .with(jwt().jwt(t->t.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/admin/reports/REVENUE/export")
                .with(jwt().jwt(t->t.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
