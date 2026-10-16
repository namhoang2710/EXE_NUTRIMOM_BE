package vn.nutrimom.admin.realtime;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;

/** Actual HTTP, real signed JWT, committed SQL and the scheduled outbox/SSE workers on isolated PG. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "app.admin.realtime.enabled=true","app.notification.admin-job-enabled=true"})
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named="admin.realtime.http.test",matches="true")
class AdminRealtimeHttpPostgresTest {
    @Value("${local.server.port}") int port;
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
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            for(String id:owned) {
                jdbc.update("delete from app.admin_notification_outbox where deep_link=? or source_id in (select id from app.payment_orders where user_id=?)","nutrimom://admin/users/"+id,id);
                jdbc.update("delete from app.notifications where user_id=?",id);
                jdbc.update("delete from app.user_subscriptions where user_id=?",id);
                jdbc.update("delete from app.payment_orders where user_id=?",id);
                jdbc.update("delete from app.user_roles where user_id=?",id);
                jdbc.update("delete from app.users where id=?",id);
            }
        });
    }
    @Test void sqlGrantReachesRealSseAndCountThenRoleRevocationClosesStream() throws Exception {
        String admin=account(UserRole.ADMIN),customer=account(UserRole.USER),token=token(admin);
        var connection=connect("/api/v1/admin/realtime/stream",token);
        connection.setRequestProperty("Accept","text/event-stream");
        try {
            assertEquals(200,connection.getResponseCode());assertTrue(connection.getContentType().startsWith("text/event-stream"));
            assertEquals("no",connection.getHeaderField("X-Accel-Buffering"));
            try(var reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8))) {
                var ready=frame(reader);assertEquals("ready",ready.event);assertEquals(admin,ready.data.path("admin_user_id").asString());
                long count=ready.data.path("unread_count").asLong();
                String revision=ready.data.path("dashboard_revision").asString();
                long start=System.nanoTime();
                jdbc.update("""
                        insert into app.user_subscriptions(id,user_id,plan_tier,status,start_date,end_date,created_at,updated_at,version)
                        values (?,?,'PLAN_399K','ACTIVE',now()-interval '1 hour',now()+interval '30 days',now(),now(),0)
                        """,UUID.randomUUID().toString(),customer);
                Frame update;
                do { update=frame(reader); } while(!update.event.equals("update") || update.data.path("unread_count").asLong()!=count+1);
                long elapsed=(System.nanoTime()-start)/1_000_000;
                assertTrue(elapsed<5000,"Committed SQL to SSE took "+elapsed+"ms");
                assertTrue(new java.math.BigInteger(update.data.path("dashboard_revision").asString()).compareTo(new java.math.BigInteger(revision))>0);
                var countConnection=connect("/api/v1/notifications/unread-count?audience=ADMIN",token);
                try(var body=countConnection.getInputStream()) {
                    var response=mapper.readTree(body).path("data");assertEquals(count+1,response.path("count").asLong());
                    assertEquals(update.data.path("notifications_revision").asString(),response.path("realtime_revision").asString());
                } finally {countConnection.disconnect();}
                jdbc.update("delete from app.user_roles where user_id=? and role='ADMIN'",admin);
                Frame revoked;do {revoked=frame(reader);} while(!revoked.event.equals("access_revoked"));
                assertEquals("ADMIN_ACCESS_REVOKED",revoked.data.path("reason").asString());
                System.out.println("ADMIN_REALTIME_HTTP sqlToSseMs="+elapsed+" ready/update/revocation=PASS");
            }
        } finally {connection.disconnect();}
    }
    Frame frame(BufferedReader reader) throws Exception {
        String event="message",line;var data=new StringBuilder();
        while((line=reader.readLine())!=null) {
            if(line.startsWith("event:"))event=line.substring(6).strip();
            else if(line.startsWith("data:"))data.append(line.substring(5).stripLeading());
            else if(line.isEmpty() && !data.isEmpty())return new Frame(event,mapper.readTree(data.toString()));
        }throw new EOFException("SSE ended before expected event");
    }
    HttpURLConnection connect(String path,String token) throws Exception {
        var c=(HttpURLConnection)URI.create("http://127.0.0.1:"+port+path).toURL().openConnection();
        c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestProperty("Authorization","Bearer "+token);return c;
    }
    String account(UserRole role) {
        var user=new UserEntity();user.setEmail(UUID.randomUUID()+"@qa.test");user.setDisplayName("HTTP SSE QA");
        user.setStatus(UserStatus.ACTIVE);user.setRoles(Set.of(role));String id=users.saveAndFlush(user).getId();owned.add(id);return id;
    }
    String token(String id) {
        var claims=JwtClaimsSet.builder().issuer(environment.getRequiredProperty("app.security.issuer"))
                .subject(id).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(90)).claim("roles",List.of("ADMIN")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    record Frame(String event,JsonNode data) { }
}
