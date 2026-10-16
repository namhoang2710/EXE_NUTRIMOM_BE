package vn.nutrimom.admin.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;

@SpringBootTest(properties={"app.admin.realtime.enabled=true","app.admin.realtime.max-connections-per-user=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminRealtimeIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-15T05:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AdminRealtimeHub hub;
    @MockitoBean Clock clock;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(call->Clock.fixed(NOW,call.getArgument(0)));
    }
    @Test void streamGuardsRoleCurrentPermissionExpiryAndQueryBeforeOpening() throws Exception {
        String user=account(UserRole.USER),admin=account(UserRole.ADMIN);
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(get(path()).with(token(user,"USER"))).andExpect(status().isForbidden());
        mvc.perform(get(path()).with(token(user,"ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get(path()).with(token(admin,"ADMIN")).queryParam("access_token","forbidden")
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(get(path()).with(jwt().jwt(j->j.subject(admin).expiresAt(NOW.minusSeconds(1)))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());assertEquals(0,hub.connectionCount());
    }
    @Test void wireFormatIsUnwrappedSnakeCaseCapacityIsJsonAndAsyncCompletionRemainsAuthorized() throws Exception {
        String admin=account(UserRole.ADMIN);
        var first=mvc.perform(get(path()).with(token(admin,"ADMIN")).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering","no"))
                .andExpect(content().string(containsString("event:ready")))
                .andExpect(content().string(containsString("\"protocol_version\":1")))
                .andExpect(content().string(containsString("\"admin_user_id\":\""+admin+"\"")))
                .andExpect(content().string(containsString("\"dashboard_revision\":\"0\""))).andReturn();
        mvc.perform(get(path()).with(token(admin,"ADMIN"))).andExpect(status().isOk());
        mvc.perform(get(path()).with(token(admin,"ADMIN")).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","30"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        hub.shutdown();mvc.perform(asyncDispatch(first)).andExpect(status().isOk());
    }
    @Test void revisionIsAdditiveOnlyForAdminNotificationViewsAndDashboard() throws Exception {
        String admin=account(UserRole.ADMIN),user=account(UserRole.USER);
        mvc.perform(get("/api/v1/admin/dashboard").with(token(admin,"ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.realtime_revision").value("0"));
        for(String endpoint:List.of("/api/v1/notifications","/api/v1/notifications/unread-count")) {
            mvc.perform(get(endpoint).param("audience","ADMIN").with(token(admin,"ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.realtime_revision").value("0"));
            mvc.perform(get(endpoint).with(token(user,"USER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.realtime_revision").doesNotExist());
        }
    }
    String account(UserRole role) {
        var user=new UserEntity();user.setEmail(UUID.randomUUID()+"@qa.test");user.setDisplayName("SSE QA");
        user.setStatus(UserStatus.ACTIVE);user.setRoles(Set.of(role));return users.saveAndFlush(user).getId();
    }
    RequestPostProcessor token(String id,String role) {
        return jwt().jwt(j->j.subject(id).expiresAt(NOW.plusSeconds(900))).authorities(new SimpleGrantedAuthority("ROLE_"+role));
    }
    String path() { return "/api/v1/admin/realtime/stream"; }
}
