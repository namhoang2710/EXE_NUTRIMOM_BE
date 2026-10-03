package vn.nutrimom.assistant.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import vn.nutrimom.assistant.config.AssistantProperties;
import vn.nutrimom.assistant.dto.AssistantDtos.*;

class GroqAssistantClientTest {
    private HttpServer server;
    private AssistantProperties properties;
    private ObjectMapper json = JsonMapper.builder().build();
    private AtomicReference<String> requestBody;
    private AtomicReference<String> result;
    private AtomicReference<Integer> status;
    private AtomicReference<Integer> delay;
    private GroqAssistantClient client;

    @BeforeEach
    void setup() throws Exception {
        properties = new AssistantProperties(); properties.setApiKey("fixture-key"); properties.setZeroDataRetentionConfirmed(true);
        requestBody = new AtomicReference<>(); result = new AtomicReference<>(); status = new AtomicReference<>(200); delay = new AtomicReference<>(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delay.get() > 0) { try { Thread.sleep(delay.get()); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
            byte[] response = result.get().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status.get(), response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        }); server.start();
        client = new GroqAssistantClient(properties, json, HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"));
    }
    @AfterEach void close() { server.stop(0); }
    @Test
    void sendsStrictSchemaAndAcceptsOnlyProvidedSourcesAndActions() {
        result.set(response("guide:records"));
        var completion = client.complete(context(), "Tôi lưu kết quả khám ở đâu? Email me@example.test", List.of());
        assertEquals(List.of("guide:records"), completion.sourceIds());
        JsonNode body = json.readTree(requestBody.get());
        assertTrue(body.at("/response_format/json_schema/strict").booleanValue());
        assertFalse(body.at("/response_format/json_schema/schema/additionalProperties").booleanValue());
        assertFalse(requestBody.get().contains("me@example.test"));
        assertEquals("openai/gpt-oss-20b", body.at("/model").stringValue());
    }
    @Test
    void rejectsUnknownCitationInsteadOfRenderingIt() {
        result.set(response("record:someone-else"));
        assertEquals("INVALID_RESPONSE", assertThrows(AiProviderClient.ProviderFailure.class, () -> client.complete(context(), "Hello", List.of())).reason());
    }
    @Test
    void upstream429ReturnsSafeReasonWithoutEchoingProviderBody() {
        status.set(429); result.set("upstream-sensitive-error");
        var error = assertThrows(AiProviderClient.ProviderFailure.class, () -> client.complete(context(), "Hello", List.of()));
        assertEquals("PROVIDER_LIMIT", error.reason()); assertFalse(error.getMessage().contains("sensitive"));
    }
    @Test
    void malformedPayloadFailsSafely() {
        result.set("not-json");
        assertEquals("INVALID_RESPONSE", assertThrows(AiProviderClient.ProviderFailure.class, () -> client.complete(context(), "Hello", List.of())).reason());
    }
    @Test
    void absentKeyOrUnconfirmedPrivacySetupNeverSendsRequest() {
        properties.setZeroDataRetentionConfirmed(false); assertFalse(client.ready());
        assertThrows(AiProviderClient.ProviderFailure.class, () -> client.complete(context(), "Hello", List.of())); assertNull(requestBody.get());
        properties.setZeroDataRetentionConfirmed(true); properties.setApiKey(""); assertFalse(client.ready());
    }
    @Test
    void timeoutProducesFallbackReason() {
        properties.setTimeout(Duration.ofMillis(50)); delay.set(250); result.set(response("guide:records"));
        assertEquals("TIMEOUT", assertThrows(AiProviderClient.ProviderFailure.class, () -> client.complete(context(), "Hello", List.of())).reason());
    }
    private String response(String source) {
        String content = json.writeValueAsString(Map.of("content", "Mở Hồ sơ y tế để lưu.", "source_ids", List.of(source), "action_ids", List.of("guide:records"), "escalation_recommended", false));
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
    }
    private AssistantContextService.Context context() {
        var source = new Source("guide:records", "GUIDE", "Hồ sơ y tế", "/app/profile/records", "Mở Hồ sơ y tế.");
        return new AssistantContextService.Context(List.of(new AssistantContextService.Evidence(source, source.excerpt())), List.of(new Action("guide:records", "Mở hồ sơ", source.href())), List.of("WEBSITE_GUIDE"), Map.of(), List.of());
    }
}
