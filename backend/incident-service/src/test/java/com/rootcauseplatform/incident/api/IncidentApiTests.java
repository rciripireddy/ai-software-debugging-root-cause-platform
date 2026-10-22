package com.rootcauseplatform.incident.api;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.rootcauseplatform.incident.domain.ProcessingOutcome;
import com.rootcauseplatform.incident.messaging.ErrorEventMessageReader;
import com.rootcauseplatform.incident.service.IncidentCorrelationService;
import com.rootcauseplatform.incident.service.IncidentCorrelationService.CorrelationResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST API tests. Incidents are created the way the Kafka consumer creates them, by passing
 * messages to the correlation service directly, which keeps these tests independent of
 * consumer timing. The deprecated manual endpoint is tested at the end.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IncidentApiTests {

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

    /** Each test uses its own application so list results are not affected by other tests. */
    private String applicationName;
    private UUID applicationId;

    @BeforeEach
    void setUp() {
        api = TestAuth.client(port);
        data = new TestData(jdbcTemplate);
        applicationName = "test-app-" + UUID.randomUUID().toString().substring(0, 8);
        applicationId = UUID.randomUUID();
    }

    @Test
    void retrievesAnIncidentWithItsRecentEvents() {
        String fingerprint = TestData.randomFingerprint();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String id = correlate(first, fingerprint, "2026-10-12T09:14:05Z", "ERROR").incidentId().toString();
        correlate(second, fingerprint, "2026-10-12T11:02:13Z", "FATAL");

        ApiClient.Response response = api.get("/api/v1/incidents/" + id);

        assertThat(response.status()).isEqualTo(200);
        JsonNode incident = response.json();
        assertThat(incident.get("id").asString()).isEqualTo(id);
        assertThat(incident.get("title").asString()).isEqualTo("java.net.SocketTimeoutException: Payment authorization failed");
        assertThat(incident.get("status").asString()).isEqualTo("OPEN");
        assertThat(incident.get("severity").asString()).isEqualTo("FATAL");
        assertThat(incident.get("occurrenceCount").asInt()).isEqualTo(2);
        assertThat(incident.get("firstSeenAt").asString()).isEqualTo("2026-10-12T09:14:05Z");
        assertThat(incident.get("lastSeenAt").asString()).isEqualTo("2026-10-12T11:02:13Z");
        assertThat(incident.get("recentEventLimit").asInt()).isEqualTo(50);
        assertThat(incident.at("/application/id").asString()).isEqualTo(applicationId.toString());
        assertThat(incident.at("/application/name").asString()).isEqualTo(applicationName);
        assertThat(incident.at("/application/service").asString()).isEqualTo("payment-service");
        assertThat(incident.at("/application/environment").asString()).isEqualTo("staging");
        assertThat(eventIds(incident)).containsExactly(second.toString(), first.toString());
        assertThat(incident.at("/recentEvents/0/traceId").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(incident.at("/recentEvents/0/host").asString()).isEqualTo("payment-5d8b7-fghij");
    }

    @Test
    void returnsNotFoundForAnUnknownIncident() {
        ApiClient.Response response = api.get("/api/v1/incidents/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(response.json().get("title").asString()).isEqualTo("Resource not found");
    }

    @Test
    void returnsBadRequestForAnInvalidIncidentId() {
        assertThat(api.get("/api/v1/incidents/12345").status()).isEqualTo(400);
    }

    @Test
    void listsIncidentsNewestActivityFirstWithPagination() {
        String older = createIncident("2026-10-10T08:00:00Z", "ERROR");
        String newest = createIncident("2026-10-12T08:00:00Z", "ERROR");
        String middle = createIncident("2026-10-11T08:00:00Z", "FATAL");

        JsonNode firstPage = api.get("/api/v1/incidents?application=" + applicationName + "&size=2").json();
        JsonNode secondPage = api.get("/api/v1/incidents?application=" + applicationName + "&size=2&page=1").json();

        assertThat(ids(firstPage)).containsExactly(newest, middle);
        assertThat(ids(secondPage)).containsExactly(older);
        assertThat(firstPage.get("totalItems").asLong()).isEqualTo(3);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.get("page").asInt()).isZero();
        assertThat(firstPage.get("size").asInt()).isEqualTo(2);
    }

    @Test
    void filtersIncidentsByStatusSeverityAndApplication() {
        String openError = createIncident("2026-10-12T08:00:00Z", "ERROR");
        String resolvedError = createIncident("2026-10-12T09:00:00Z", "ERROR");
        String openFatal = createIncident("2026-10-12T10:00:00Z", "FATAL");
        data.setStatus(resolvedError, "RESOLVED");
        String app = "&application=" + applicationName.toUpperCase();

        assertThat(ids(api.get("/api/v1/incidents?status=OPEN" + app).json()))
                .containsExactlyInAnyOrder(openError, openFatal);
        assertThat(ids(api.get("/api/v1/incidents?status=RESOLVED" + app).json()))
                .containsExactly(resolvedError);
        assertThat(ids(api.get("/api/v1/incidents?severity=FATAL" + app).json()))
                .containsExactly(openFatal);
        assertThat(ids(api.get("/api/v1/incidents?status=OPEN&severity=ERROR" + app).json()))
                .containsExactly(openError);
        assertThat(ids(api.get("/api/v1/incidents?environment=production" + app).json())).isEmpty();
        assertThat(ids(api.get("/api/v1/incidents?service=payment-service&environment=staging" + app).json()))
                .hasSize(3);
    }

    @Test
    void rejectsInvalidListParameters() {
        ApiClient.Response badStatus = api.get("/api/v1/incidents?status=CLOSED");
        assertThat(badStatus.status()).isEqualTo(400);
        assertThat(badStatus.json().get("detail").asString()).isEqualTo("Parameter 'status' has an invalid value.");

        ApiClient.Response badSize = api.get("/api/v1/incidents?size=500");
        assertThat(badSize.status()).isEqualTo(400);
        assertThat(fieldsWithErrors(badSize.json())).containsExactly("size");

        assertThat(api.get("/api/v1/incidents?page=-1").status()).isEqualTo(400);
    }

    @Test
    void errorResponsesDoNotExposeInternals() {
        List<ApiClient.Response> errors = List.of(
                api.postRaw("/api/v1/incidents", "{not json", "application/json"),
                api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(UUID.randomUUID()))),
                api.get("/api/v1/incidents/" + UUID.randomUUID()),
                api.get("/api/v1/incidents?status=CLOSED"));

        for (ApiClient.Response error : errors) {
            assertThat(error.body())
                    .doesNotContain("Exception")
                    .doesNotContain("\tat ")
                    .doesNotContain("org.springframework")
                    .doesNotContain("org.hibernate");
            assertThat(error.json().get("correlationId").asString()).isNotBlank();
        }
    }

    // Deprecated manual creation (Phase 2 behavior, kept for compatibility).

    @Test
    void deprecatedEndpointStillCreatesAnIncidentFromStoredEvents() {
        UUID ingestionApplication = data.application(applicationName, "payment-service", "staging");
        String fingerprint = TestData.randomFingerprint();
        UUID first = data.errorEvent(ingestionApplication, fingerprint, "2026-10-12T09:14:05Z", "WARN");
        UUID second = data.errorEvent(ingestionApplication, fingerprint, "2026-10-12T13:55:01Z", "WARN");

        ApiClient.Response response = api.postJson("/api/v1/incidents", Map.of(
                "errorEventIds", List.of(first, second), "summary", "Created manually in a test."));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("Deprecation")).isEqualTo("@1792454400");
        JsonNode incident = response.json();
        assertThat(response.header("Location")).isEqualTo("/api/v1/incidents/" + incident.get("id").asString());
        assertThat(incident.get("occurrenceCount").asInt()).isEqualTo(2);
        assertThat(incident.get("severity").asString()).isEqualTo("WARN");
        assertThat(incident.get("summary").asString()).isEqualTo("Created manually in a test.");
        assertThat(incident.at("/application/name").asString()).isEqualTo(applicationName);
        assertThat(eventIds(incident)).containsExactly(second.toString(), first.toString());
    }

    @Test
    void theConsumerSkipsEventsAlreadyAssignedThroughTheDeprecatedEndpoint() {
        UUID ingestionApplication = data.application(applicationName, "payment-service", "staging");
        String fingerprint = TestData.randomFingerprint();
        UUID event = data.errorEvent(ingestionApplication, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        String id = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(event))).json()
                .get("id").asString();

        CorrelationResult result = correlationService.correlate(reader.read(jsonMapper.writeValueAsString(
                TestMessages.errorEventCreated(event, ingestionApplication, applicationName, fingerprint,
                        "2026-10-12T09:14:05Z", "ERROR"))));

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.ALREADY_ASSIGNED);
        assertThat(result.incidentId().toString()).isEqualTo(id);
        assertThat(api.get("/api/v1/incidents/" + id).json().get("occurrenceCount").asInt()).isEqualTo(1);
    }

    @Test
    void deprecatedEndpointRejectsEventsTheConsumerAlreadyCorrelated() {
        UUID ingestionApplication = data.application(applicationName, "payment-service", "staging");
        String fingerprint = TestData.randomFingerprint();
        UUID event = data.errorEvent(ingestionApplication, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        correlationService.correlate(reader.read(jsonMapper.writeValueAsString(TestMessages.errorEventCreated(event,
                ingestionApplication, applicationName, fingerprint, "2026-10-12T09:14:05Z", "ERROR"))));

        ApiClient.Response response = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(event)));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("detail").asString()).contains(event.toString());
    }

    @Test
    void deprecatedEndpointValidatesItsInput() {
        ApiClient.Response empty = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of()));
        assertThat(empty.status()).isEqualTo(400);
        assertThat(fieldsWithErrors(empty.json())).containsExactly("errorEventIds");

        ApiClient.Response longTitle = api.postJson("/api/v1/incidents",
                Map.of("errorEventIds", List.of(UUID.randomUUID()), "title", "x".repeat(256)));
        assertThat(longTitle.status()).isEqualTo(400);
        assertThat(fieldsWithErrors(longTitle.json())).containsExactly("title");

        assertThat(api.postRaw("/api/v1/incidents", "{\"errorEventIds\": [\"not-a-uuid\"]}", "application/json")
                .status()).isEqualTo(400);

        UUID unknown = UUID.randomUUID();
        ApiClient.Response unknownEvent = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(unknown)));
        assertThat(unknownEvent.status()).isEqualTo(422);
        assertThat(unknownEvent.json().get("detail").asString()).contains(unknown.toString());
    }

    @Test
    void deprecatedEndpointRejectsEventsFromDifferentApplicationsOrErrors() {
        UUID ingestionApplication = data.application(applicationName, "payment-service", "staging");
        UUID otherApplication = data.application(applicationName, "checkout-service", "staging");
        String fingerprint = TestData.randomFingerprint();
        UUID event = data.errorEvent(ingestionApplication, fingerprint, "2026-10-12T09:14:05Z", "WARN");
        UUID otherAppEvent = data.errorEvent(otherApplication, fingerprint, "2026-10-12T09:14:06Z", "WARN");
        UUID otherErrorEvent = data.errorEvent(ingestionApplication, TestData.randomFingerprint(),
                "2026-10-12T09:14:07Z", "WARN");

        ApiClient.Response mixedApplications = api.postJson("/api/v1/incidents",
                Map.of("errorEventIds", List.of(event, otherAppEvent)));
        ApiClient.Response mixedErrors = api.postJson("/api/v1/incidents",
                Map.of("errorEventIds", List.of(event, otherErrorEvent)));

        assertThat(mixedApplications.status()).isEqualTo(422);
        assertThat(mixedApplications.json().get("detail").asString())
                .isEqualTo("All error events must belong to the same application.");
        assertThat(mixedErrors.status()).isEqualTo(422);
        assertThat(mixedErrors.json().get("detail").asString())
                .isEqualTo("All error events must have the same fingerprint.");
    }

    /** Opens an incident with one occurrence of a new error and returns its ID. */
    private String createIncident(String timestamp, String severity) {
        CorrelationResult result = correlate(UUID.randomUUID(), TestData.randomFingerprint(), timestamp, severity);
        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.INCIDENT_CREATED);
        return result.incidentId().toString();
    }

    private CorrelationResult correlate(UUID eventId, String fingerprint, String timestamp, String severity) {
        Map<String, Object> message = TestMessages.errorEventCreated(eventId, applicationId, applicationName,
                fingerprint, timestamp, severity);
        return correlationService.correlate(reader.read(jsonMapper.writeValueAsString(message)));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private static List<String> eventIds(JsonNode incident) {
        List<String> ids = new ArrayList<>();
        incident.get("recentEvents").forEach(event -> ids.add(event.get("id").asString()));
        return ids;
    }

    private static List<String> fieldsWithErrors(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        problem.get("errors").forEach(error -> fields.add(error.get("field").asString()));
        return fields;
    }
}
