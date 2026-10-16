package vn.nutrimom.admin.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;

/** Real signed JWT, PG commit and background jobs through the Servlet stack, without a listening socket. */
@SpringBootTest(properties={"app.admin.realtime.enabled=true","app.notification.admin-job-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named="admin.audit.postgres.test",matches="true")
class AdminRealtimeScheduledPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    final List<String> owned=new ArrayList<>();
    @BeforeEach void guard() {assertEquals(AdminRealtimePostgresTest.URL,environment.getProperty("spring.datasource.url"));}
    @AfterEach void cleanup() {
        new TransactionTemplate(manager).executeWithoutResult(tx->{for(String id:owned) {
            jdbc.update("delete from app.admin_notification_outbox where deep_link=?","nutrimom://admin/users/"+id);
            jdbc.update("delete from app.notifications where user_id=?",id);
            jdbc.update("delete from app.user_subscriptions where user_id=?",id);
            jdbc.update("delete from app.payment_orders where user_id=?",id);
            jdbc.update("delete from app.user_roles where user_id=?",id);
            jdbc.update("delete from app.users where id=?",id);
        }});
    }
    @Test void committedGrantTraversesOutboxAndSseWithinBudgetAndRevocationTerminates() throws Exception {
        String admin=account(UserRole.ADMIN),customer=account(UserRole.USER),token=token(admin);
        var result=mvc.perform(get("/api/v1/admin/realtime/stream").header("Authorization","Bearer "+token).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        String ready=result.getResponse().getContentAsString();assertTrue(ready.contains("event:ready"));
        long start=System.nanoTime();
        jdbc.update("""
                insert into app.user_subscriptions(id,user_id,plan_tier,status,start_date,end_date,created_at,updated_at,version)
                values (?,?,'PLAN_399K','ACTIVE',now()-interval '1 hour',now()+interval '30 days',now(),now(),0)
                """,UUID.randomUUID().toString(),customer);
        long until=System.nanoTime()+8_000_000_000L;
        String wire;
        do {Thread.sleep(50);wire=result.getResponse().getContentAsString();}
        while(!wire.contains("\"unread_count\":1") && System.nanoTime()<until);
        assertTrue(wire.contains("event:update"));assertTrue(wire.contains("\"unread_count\":1"));
        long elapsed=(System.nanoTime()-start)/1_000_000;assertTrue(elapsed<5000,"Pipeline took "+elapsed+"ms");
        var count=mvc.perform(get("/api/v1/notifications/unread-count").param("audience","ADMIN")
                .header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
        var payload=mapper.readTree(count.getResponse().getContentAsString()).path("data");
        assertEquals(1,payload.path("count").asLong());assertTrue(wire.contains("\"notifications_revision\":\""+payload.path("realtime_revision").asString()+"\""));
        jdbc.update("delete from app.user_roles where user_id=? and role='ADMIN'",admin);
        until=System.nanoTime()+5_000_000_000L;
        do {Thread.sleep(50);wire=result.getResponse().getContentAsString();}
        while(!wire.contains("event:access_revoked") && System.nanoTime()<until);
        assertTrue(wire.contains("event:access_revoked"));mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        System.out.println("ADMIN_REALTIME_SERVLET sqlToSseMs="+elapsed+" JWT/outbox/revocation=PASS");
    }
    String account(UserRole role) {
        var user=new UserEntity();user.setEmail(UUID.randomUUID()+"@qa.test");user.setDisplayName("SSE pipeline QA");
        user.setStatus(UserStatus.ACTIVE);user.setRoles(Set.of(role));String id=users.saveAndFlush(user).getId();owned.add(id);return id;
    }
    String token(String id) {
        var claims=JwtClaimsSet.builder().issuer(environment.getRequiredProperty("app.security.issuer"))
                .subject(id).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(90)).claim("roles",List.of("ADMIN")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
}
