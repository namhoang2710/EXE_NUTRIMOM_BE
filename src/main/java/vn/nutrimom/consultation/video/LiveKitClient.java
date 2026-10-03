package vn.nutrimom.consultation.video;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/** LiveKit's documented JWT and Twirp API, using the application's Nimbus/HTTP libraries. */
@Component
public class LiveKitClient implements LiveKitGateway {
    private final VideoProperties properties;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final RestClient http;
    @Autowired
    public LiveKitClient(VideoProperties properties, Clock clock, ObjectMapper mapper) {
        this(properties, clock, mapper, createHttp());
    }
    LiveKitClient(VideoProperties properties, Clock clock, ObjectMapper mapper, RestClient http) {
        this.properties = properties; this.clock = clock; this.mapper = mapper;
        this.http = http;
    }
    private static RestClient createHttp() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory).build();
    }
    @Override public void ensureRoom(String room) {
        call("CreateRoom", Map.of("name", room, "max_participants", 2,
                "empty_timeout", 120, "departure_timeout", 30), Map.of("roomCreate", true), false);
    }
    @Override public String participantToken(String room, String identity, Instant expiresAt) {
        return sign(identity, Map.of("room", room, "roomJoin", true, "canPublish", true,
                "canSubscribe", true, "canPublishData", false,
                "canPublishSources", new String[]{"camera", "microphone", "screen_share", "screen_share_audio"}), expiresAt);
    }
    @Override public String encryptionKey(String room) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(
                    ("nutrimom-video-e2ee-v1:" + room).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot derive room encryption key", e); }
    }
    @Override public void closeRoom(String room, String user, String expert, Instant cutoff) {
        // Explicit cutoff closes LiveKit Cloud's default revocation clock-skew window.
        long revokeTs = cutoff.getEpochSecond();
        for (String identity : new String[]{user, expert}) {
            call("RemoveParticipant", Map.of("room", room, "identity", identity,
                    "revoke_token_ts", revokeTs), Map.of("room", room, "roomAdmin", true), true);
        }
        call("DeleteRoom", Map.of("room", room), Map.of("roomCreate", true), true);
    }
    private void call(String method, Map<String, Object> body, Map<String, Object> grants, boolean ignoreMissing) {
        try {
            http.post().uri(properties.getUrl().replaceFirst("^wss:", "https:").replaceAll("/$", "")
                            + "/twirp/livekit.RoomService/" + method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(sign(null, grants, clock.instant().plusSeconds(60))))
                    .body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (ignoreMissing && (status == 404 || status == 400)) {
                String responseBody = e.getResponseBodyAsString();
                if (status == 404 || responseBody.contains("not found") || responseBody.contains("not_found")) {
                    return;
                }
            }
            throw new BusinessException(ErrorCode.VIDEO_PROVIDER_UNAVAILABLE);
        } catch (RestClientException e) { throw new BusinessException(ErrorCode.VIDEO_PROVIDER_UNAVAILABLE); }
    }
    private byte[] secret() { return properties.getApiSecret().getBytes(StandardCharsets.UTF_8); }
    private String sign(String identity, Map<String, Object> video, Instant expiry) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(properties.getApiKey()).subject(identity)
                    .notBeforeTime(Date.from(clock.instant())).expirationTime(Date.from(expiry)).claim("video", video).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret()));
            return jwt.serialize();
        } catch (JOSEException e) { throw new IllegalStateException("Cannot sign LiveKit token", e); }
    }
    @Override public RoomEvent verifyWebhook(String body, String auth) {
        if (!properties.ready()) throw new BusinessException(ErrorCode.VIDEO_NOT_CONFIGURED);
        try {
            if (auth == null) throw new IllegalArgumentException();
            SignedJWT jwt = SignedJWT.parse(auth.startsWith("Bearer ") ? auth.substring(7) : auth);
            var claims = jwt.getJWTClaimsSet();
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret()))
                    || !properties.getApiKey().equals(claims.getIssuer()) || claims.getExpirationTime() == null
                    || !claims.getExpirationTime().toInstant().isAfter(clock.instant())
                    || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(clock.instant().plusSeconds(30))))
                throw new IllegalArgumentException();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(hash, Base64.getDecoder().decode(claims.getStringClaim("sha256"))))
                throw new IllegalArgumentException();
            var tree = mapper.readTree(body);
            return new RoomEvent(tree.path("event").asString(), tree.path("room").path("name").asString(),
                    tree.path("participant").path("identity").asString(), Instant.ofEpochSecond(tree.path("createdAt").asLong()));
        } catch (Exception e) { throw new BusinessException(ErrorCode.UNAUTHORIZED, "Webhook video không hợp lệ."); }
    }
}
