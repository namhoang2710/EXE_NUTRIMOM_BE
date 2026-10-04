package vn.nutrimom.consultation.video;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.common.exception.BusinessException;

class LiveKitClientTest {
    private static final Instant NOW = Instant.parse("2026-10-03T02:58:00Z");
    private static final String SECRET = "test-livekit-secret-at-least-32-bytes";
    private LiveKitClient client() {
        var props = new VideoProperties(); props.setUrl("wss://test.livekit.cloud");
        props.setApiKey("test-key"); props.setApiSecret(SECRET);
        return new LiveKitClient(props, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }
    @Test void scopedTokenHasNoAdminRecordingOrCrossRoomRights() throws Exception {
        var token = SignedJWT.parse(client().participantToken("opaque-room", "opaque-user", NOW.plusSeconds(300)));
        assertThat(token.verify(new MACVerifier(SECRET))).isTrue();
        var claims = token.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("test-key"); assertThat(claims.getSubject()).isEqualTo("opaque-user");
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plusSeconds(300));
        var grants = claims.getJSONObjectClaim("video");
        assertThat(grants).containsEntry("room", "opaque-room").containsEntry("roomJoin", true).containsEntry("canPublishData", false);
        assertThat(grants).doesNotContainKeys("roomAdmin", "roomCreate", "roomList", "roomRecord");
    }
    @Test void e2eeKeysAreStableWithinRoomAndDifferentAcrossBookings() {
        assertThat(client().encryptionKey("one")).isEqualTo(client().encryptionKey("one"));
        assertThat(client().encryptionKey("one")).isNotEqualTo(client().encryptionKey("two"));
        assertThat(Base64.getDecoder().decode(client().encryptionKey("one"))).hasSize(32);
    }
    @Test void webhookRequiresCorrectSignatureIssuerExpiryAndRawBodyChecksum() throws Exception {
        String body = "{\"event\":\"participant_joined\",\"room\":{\"name\":\"room\"},\"participant\":{\"identity\":\"user\"},\"createdAt\":1790996280}";
        var valid = webhookToken(body, SECRET, "test-key", NOW.plusSeconds(30));
        assertThat(client().verifyWebhook(body, valid).identity()).isEqualTo("user");
        assertThatThrownBy(() -> client().verifyWebhook(body + " ", valid)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client().verifyWebhook(body, null)).isInstanceOf(BusinessException.class);
        var wrongKey = webhookToken(body, SECRET + "different", "test-key", NOW.plusSeconds(30));
        var wrongIssuer = webhookToken(body, SECRET, "wrong-key", NOW.plusSeconds(30));
        var expired = webhookToken(body, SECRET, "test-key", NOW.minusSeconds(1));
        for (String invalid : new String[]{wrongKey, wrongIssuer, expired})
            assertThatThrownBy(() -> client().verifyWebhook(body, invalid)).isInstanceOf(BusinessException.class);
    }
    private String webhookToken(String body, String secret, String issuer, Instant expiry) throws Exception {
        String checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().issuer(issuer)
                .expirationTime(Date.from(expiry)).claim("sha256", checksum).build());
        jwt.sign(new MACSigner(secret)); return jwt.serialize();
    }
    @Test void roomApiUsesTwoSeatsAndExplicitCloudRevocationCutoff() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var props = new VideoProperties(); props.setUrl("wss://test.livekit.cloud");
        props.setApiKey("test-key"); props.setApiSecret(SECRET);
        var client = new LiveKitClient(props, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper(), builder.build());
        String base = "https://test.livekit.cloud/twirp/livekit.RoomService/";
        server.expect(requestTo(base + "CreateRoom")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"name\":\"room\",\"max_participants\":2,\"empty_timeout\":120,\"departure_timeout\":30}"))
                .andExpect(request -> {
                    try {
                        var token = SignedJWT.parse(request.getHeaders().getFirst("Authorization").substring(7));
                        assertThat(token.verify(new MACVerifier(SECRET))).isTrue();
                        assertThat(token.getJWTClaimsSet().getJSONObjectClaim("video")).containsEntry("roomCreate", true);
                    } catch (Exception e) { throw new AssertionError(e); }
                }).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        for (String identity : new String[]{"patient", "expert"}) {
            server.expect(requestTo(base + "RemoveParticipant"))
                    .andExpect(content().json("{\"room\":\"room\",\"identity\":\"" + identity + "\",\"revoke_token_ts\":" + NOW.getEpochSecond() + "}"))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo(base + "DeleteRoom")).andExpect(content().json("{\"room\":\"room\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.ensureRoom("room"); client.closeRoom("room", "patient", "expert", NOW);
        server.verify();
    }
}
