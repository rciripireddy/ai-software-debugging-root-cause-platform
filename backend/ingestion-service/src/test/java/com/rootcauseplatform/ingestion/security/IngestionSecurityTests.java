package com.rootcauseplatform.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

import com.rootcauseplatform.ingestion.ApiClient;
import com.rootcauseplatform.ingestion.TestAuth;
import com.rootcauseplatform.ingestion.TestcontainersConfiguration;

/**
 * Who may submit and read events: log producers with the ingestion API key submit, signed-in
 * users and internal services read. Also covers input hardening of the read endpoints.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IngestionSecurityTests {

    @Value("${local.server.port}")
    private int port;

    private ApiClient anonymous;
    private String applicationName;

    @BeforeEach
    void setUp() {
        anonymous = new ApiClient(port);
        applicationName = "security-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void submittingEventsRequiresTheIngestionApiKey() {
        assertThat(anonymous.postJson("/api/v1/logs", event()).status()).isEqualTo(401);
        assertThat(anonymous.withHeaders(Map.of("X-Api-Key", "wrong-key")).postJson("/api/v1/logs", event()).status())
                .isEqualTo(401);
        assertThat(anonymous.withHeaders(TestAuth.bearer(TestAuth.token("ADMIN"))).postJson("/api/v1/logs", event())
                .status()).as("users read events, applications submit them").isEqualTo(403);
        assertThat(anonymous.withHeaders(TestAuth.serviceToken()).postJson("/api/v1/logs", event()).status())
                .isEqualTo(403);

        assertThat(anonymous.withHeaders(TestAuth.apiKey()).postJson("/api/v1/logs", event()).status())
                .isEqualTo(201);
    }

    @Test
    void readingEventsRequiresAUserOrTheServiceToken() {
        String id = anonymous.withHeaders(TestAuth.apiKey()).postJson("/api/v1/logs", event()).json().get("id")
                .asString();

        for (String path : List.of("/api/v1/logs", "/api/v1/logs/" + id, "/api/v1/logs/activity",
                "/api/v1/applications")) {
            ApiClient.Response unauthenticated = anonymous.get(path);
            assertThat(unauthenticated.status()).as(path).isEqualTo(401);
            assertThat(unauthenticated.json().get("title").asString()).isEqualTo("Authentication required");

            assertThat(anonymous.withHeaders(TestAuth.apiKey()).get(path).status()).as(path).isEqualTo(403);
            assertThat(anonymous.withHeaders(TestAuth.bearer(TestAuth.token("VIEWER"))).get(path).status())
                    .as(path).isEqualTo(200);
            assertThat(anonymous.withHeaders(TestAuth.bearer(TestAuth.token("ADMIN"))).get(path).status())
                    .as(path).isEqualTo(200);
            assertThat(anonymous.withHeaders(TestAuth.serviceToken()).get(path).status()).as(path).isEqualTo(200);
        }
    }

    @Test
    void theServiceTokenIsNotInterchangeableWithUserTokens() {
        assertThat(anonymous.withHeaders(Map.of("X-Service-Token", "wrong")).get("/api/v1/logs").status())
                .isEqualTo(401);
        assertThat(anonymous.withHeaders(Map.of("X-Service-Token", TestAuth.token("ADMIN"))).get("/api/v1/logs")
                .status()).isEqualTo(401);
        assertThat(anonymous.withHeaders(TestAuth.bearer(TestAuth.SERVICE_TOKEN)).get("/api/v1/logs").status())
                .isEqualTo(401);
        assertThat(anonymous.withHeaders(TestAuth.bearer(TestAuth.API_KEY)).get("/api/v1/logs").status())
                .isEqualTo(401);
    }

    @Test
    void expiredAndForgedTokensAreRejected() {
        Instant past = Instant.now().minus(3, ChronoUnit.HOURS);
        ApiClient.Response expired = anonymous.withHeaders(TestAuth.bearer(TestAuth.token(
                TestAuth.claims("VIEWER", past, past.plus(1, ChronoUnit.HOURS)), TestAuth.JWT_SECRET)))
                .get("/api/v1/logs");
        assertThat(expired.status()).isEqualTo(401);
        assertThat(expired.json().get("title").asString()).isEqualTo("Session expired");

        Instant now = Instant.now();
        String forged = TestAuth.token(TestAuth.claims("ADMIN", now, now.plusSeconds(300)),
                "a-different-secret-that-is-long-enough-000");
        assertThat(anonymous.withHeaders(TestAuth.bearer(forged)).get("/api/v1/logs").status()).isEqualTo(401);
        String futureIssued = TestAuth.token(TestAuth.claims("ADMIN", now.plusSeconds(3600), now.plusSeconds(7200)),
                TestAuth.JWT_SECRET);
        assertThat(anonymous.withHeaders(TestAuth.bearer(futureIssued)).get("/api/v1/logs").status()).isEqualTo(401);
    }

    @Test
    void theSessionCookieIsAcceptedForReading() {
        ApiClient cookie = anonymous.withHeaders(Map.of("Cookie", "rcp_session=" + TestAuth.token("VIEWER")));

        assertThat(cookie.get("/api/v1/logs").status()).isEqualTo(200);
        assertThat(cookie.postJson("/api/v1/logs", event()).status()).as("CSRF check before the role check")
                .isEqualTo(403);
    }

    @Test
    void healthStaysPublic() {
        assertThat(anonymous.get("/actuator/health").status()).isEqualTo(200);
        assertThat(anonymous.get("/actuator/health/readiness").status()).isEqualTo(200);
        assertThat(anonymous.get("/actuator/info").status()).isEqualTo(200);
        assertThat(anonymous.withHeaders(Map.of("Authorization", "Bearer broken")).get("/actuator/health").status())
                .isEqualTo(200);
    }

    @Test
    void sqlLikeAndScriptSearchTextIsTreatedAsPlainText() {
        ApiClient viewer = TestAuth.client(port);
        Map<String, Object> event = event();
        event.put("message", "<script>alert('x')</script> Robert'); DROP TABLE ingestion.error_events;--");
        assertThat(viewer.postJson("/api/v1/logs", event).status()).isEqualTo(201);

        for (String q : List.of("' OR '1'='1", "%' OR 1=1 --", "'; DROP TABLE ingestion.error_events; --", "\\")) {
            ApiClient.Response response = viewer.get("/api/v1/logs?application=" + applicationName + "&q="
                    + URLEncoder.encode(q, StandardCharsets.UTF_8));
            assertThat(response.status()).as(q).isEqualTo(200);
        }
        ApiClient.Response stored = viewer.get("/api/v1/logs?application=" + applicationName + "&q=DROP+TABLE");
        assertThat(stored.json().get("totalItems").asInt()).isEqualTo(1);
        assertThat(stored.json().at("/items/0/message").asString()).startsWith("<script>alert('x')</script>");
        assertThat(viewer.get("/api/v1/logs?application=" + applicationName).json().get("totalItems").asInt())
                .isEqualTo(1);
    }

    @Test
    void unreasonableQueryParametersAreRejected() {
        ApiClient viewer = TestAuth.client(port);

        assertThat(viewer.get("/api/v1/logs?q=" + "x".repeat(201)).status()).isEqualTo(400);
        assertThat(viewer.get("/api/v1/logs?page=-1").status()).isEqualTo(400);
        assertThat(viewer.get("/api/v1/logs?page=10001").status()).isEqualTo(400);
        assertThat(viewer.get("/api/v1/logs?size=101").status()).isEqualTo(400);
        assertThat(viewer.get("/api/v1/logs?severity=CRITICAL").status()).isEqualTo(400);
        assertThat(viewer.get("/api/v1/logs/not-a-uuid").status()).isEqualTo(400);
        assertThat(viewer.get("/api/v1/logs/activity?hours=1000").status()).isEqualTo(400);
    }

    @Test
    void oversizedBodiesAreRejected() {
        Map<String, Object> event = event();
        event.put("stackTrace", "x".repeat(1_100_000));

        ApiClient.Response response = anonymous.withHeaders(TestAuth.apiKey()).postJson("/api/v1/logs", event);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.json().get("title").asString()).isEqualTo("Request too large");
    }

    @Test
    void responsesCarrySecurityHeaders() {
        ApiClient.Response response = TestAuth.client(port).get("/api/v1/logs");

        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.header("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(response.header("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.header("Cache-Control")).contains("no-store");
    }

    private Map<String, Object> event() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("application", applicationName);
        event.put("service", "checkout-service");
        event.put("environment", "test");
        event.put("timestamp", "2026-10-22T10:00:00Z");
        event.put("severity", "ERROR");
        event.put("message", "Checkout failed");
        return event;
    }
}
