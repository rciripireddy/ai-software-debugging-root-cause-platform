package com.rootcauseplatform.incident.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rootcauseplatform.incident.ApiClient;
import com.rootcauseplatform.incident.TestAuth;
import com.rootcauseplatform.incident.TestData;
import com.rootcauseplatform.incident.TestMessages;
import com.rootcauseplatform.incident.TestcontainersConfiguration;
import com.rootcauseplatform.incident.messaging.ErrorEventMessageReader;
import com.rootcauseplatform.incident.service.IncidentCorrelationService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The dashboard additions to the incident API: title search and aggregate statistics. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IncidentStatsApiTests {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ErrorEventMessageReader reader;

    @Autowired
    private IncidentCorrelationService correlationService;

    private ApiClient api;
    private TestData data;
    private String applicationName;
    private UUID applicationId;

    @BeforeEach
    void setUp() {
        api = TestAuth.client(port);
        data = new TestData(jdbcTemplate);
        applicationName = "stats-app-" + UUID.randomUUID().toString().substring(0, 8);
        applicationId = UUID.randomUUID();
    }

    @Test
    void searchesIncidentTitlesCaseInsensitively() {
        String payment = incident("payment-service", "Payment authorization failed", "ERROR", "2026-10-12T08:00:00Z");
        incident("payment-service", "Refund request failed", "ERROR", "2026-10-12T09:00:00Z");
        String percent = incident("payment-service", "Quota at 100% reached", "ERROR", "2026-10-12T10:00:00Z");
        String app = "&application=" + applicationName;

        assertThat(ids(api.get("/api/v1/incidents?q=AUTHORIZATION" + app).json())).containsExactly(payment);
        assertThat(ids(api.get("/api/v1/incidents?q=failed" + app).json())).hasSize(2);
        assertThat(ids(api.get("/api/v1/incidents?q=100%25" + app).json())).containsExactly(percent);
        assertThat(ids(api.get("/api/v1/incidents?q=nothing-like-this" + app).json())).isEmpty();
        assertThat(api.get("/api/v1/incidents?q=" + "x".repeat(201)).status()).isEqualTo(400);
    }

    @Test
    void countsIncidentsByStatusSeverityServiceAndDay() {
        String service = "stats-service-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode before = api.get("/api/v1/incidents/stats").json();
        String today = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        incident(service, "First failure", "ERROR", today);
        incident(service, "Second failure", "FATAL", today);
        String resolved = incident(service, "Third failure", "ERROR", today);
        data.setStatus(resolved, "RESOLVED");

        ApiClient.Response response = api.get("/api/v1/incidents/stats");
        assertThat(response.status()).isEqualTo(200);
        JsonNode after = response.json();

        assertThat(delta(before, after, "totalIncidents")).isEqualTo(3);
        assertThat(after.get("byStatus").get("OPEN").asLong() - before.get("byStatus").get("OPEN").asLong())
                .isEqualTo(2);
        assertThat(after.get("byStatus").get("RESOLVED").asLong()
                - before.get("byStatus").get("RESOLVED").asLong()).isEqualTo(1);
        assertThat(after.get("unresolvedBySeverity").get("FATAL").asLong()
                - before.get("unresolvedBySeverity").get("FATAL").asLong()).isEqualTo(1);
        assertThat(after.get("unresolvedBySeverity").get("ERROR").asLong()
                - before.get("unresolvedBySeverity").get("ERROR").asLong()).isEqualTo(1);
        assertThat(delta(before, after, "servicesAffected")).isEqualTo(1);

        JsonNode days = after.get("newIncidentsPerDay");
        assertThat(days).hasSize(14);
        JsonNode lastDay = days.get(13);
        assertThat(lastDay.get("date").asString()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(lastDay.get("count").asLong() - before.get("newIncidentsPerDay").get(13).get("count").asLong())
                .isEqualTo(3);

        JsonNode top = after.get("topServices");
        assertThat(top.size()).isLessThanOrEqualTo(8);
        for (int i = 1; i < top.size(); i++) {
            assertThat(top.get(i - 1).get("unresolvedIncidents").asLong())
                    .isGreaterThanOrEqualTo(top.get(i).get("unresolvedIncidents").asLong());
        }
        assertThat(after.get("generatedAt").asString()).isNotBlank();
    }

    @Test
    void limitsTheNumberOfDays() {
        assertThat(api.get("/api/v1/incidents/stats?days=3").json().get("newIncidentsPerDay")).hasSize(3);
        assertThat(api.get("/api/v1/incidents/stats?days=0").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/incidents/stats?days=91").status()).isEqualTo(400);
    }

    private String incident(String service, String message, String severity, String timestamp) {
        Map<String, Object> event = TestMessages.errorEventCreated(UUID.randomUUID(), applicationId, applicationName,
                TestData.randomFingerprint(), timestamp, severity);
        event.put("message", message);
        @SuppressWarnings("unchecked")
        Map<String, Object> application = (Map<String, Object>) event.get("application");
        application.put("service", service);
        return correlationService.correlate(reader.read(jsonMapper.writeValueAsString(event))).incidentId()
                .toString();
    }

    private static long delta(JsonNode before, JsonNode after, String field) {
        return after.get(field).asLong() - before.get(field).asLong();
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }
}
