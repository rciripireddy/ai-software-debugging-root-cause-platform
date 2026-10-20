package com.rootcauseplatform.incident.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.rootcauseplatform.incident.TestData;
import com.rootcauseplatform.incident.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IncidentApiTests {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApiClient api;
    private TestData data;

    /** Each test uses its own application so list results are not affected by other tests. */
    private String applicationName;
    private UUID applicationId;
    private String fingerprint;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        data = new TestData(jdbcTemplate);
        applicationName = "test-app-" + UUID.randomUUID().toString().substring(0, 8);
        applicationId = data.application(applicationName, "payment-service", "staging");
        fingerprint = TestData.randomFingerprint();
    }

    @Test
    void createsAnIncidentFromStoredErrorEvents() {
        UUID first = data.errorEvent(applicationId, fingerprint, "2026-10-12T09:14:05Z", "WARN");
        UUID second = data.errorEvent(applicationId, fingerprint, "2026-10-12T13:55:01Z", "ERROR");
        UUID third = data.errorEvent(applicationId, fingerprint, "2026-10-12T11:02:13Z", "ERROR");

        ApiClient.Response response = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(first, second, third)));

        assertThat(response.status()).isEqualTo(201);
        JsonNode incident = response.json();
        String id = incident.get("id").asString();
        assertThat(response.header("Location")).isEqualTo("/api/v1/incidents/" + id);
        assertThat(incident.get("title").asString()).isEqualTo("java.net.SocketTimeoutException: Payment authorization failed");
        assertThat(incident.get("status").asString()).isEqualTo("OPEN");
        assertThat(incident.get("severity").asString()).isEqualTo("ERROR");
        assertThat(incident.get("occurrenceCount").asInt()).isEqualTo(3);
        assertThat(incident.get("firstSeenAt").asString()).isEqualTo("2026-10-12T09:14:05Z");
        assertThat(incident.get("lastSeenAt").asString()).isEqualTo("2026-10-12T13:55:01Z");
        assertThat(incident.get("fingerprint").asString()).isEqualTo(fingerprint);
        assertThat(incident.at("/application/name").asString()).isEqualTo(applicationName);
        assertThat(eventIds(incident)).containsExactly(second.toString(), third.toString(), first.toString());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from incident.incident_events where incident_id = ?::uuid", Integer.class, id))
                .isEqualTo(3);
    }

    @Test
    void usesTheProvidedTitleSummaryAndSeverity() {
        UUID event = data.errorEvent(applicationId, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("errorEventIds", List.of(event));
        request.put("title", "Payment gateway timeouts");
        request.put("summary", "Demo incident created for testing.");
        request.put("severity", "FATAL");

        JsonNode incident = api.postJson("/api/v1/incidents", request).json();

        assertThat(incident.get("title").asString()).isEqualTo("Payment gateway timeouts");
        assertThat(incident.get("summary").asString()).isEqualTo("Demo incident created for testing.");
        assertThat(incident.get("severity").asString()).isEqualTo("FATAL");
    }

    @Test
    void retrievesAnIncidentById() {
        UUID event = data.errorEvent(applicationId, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        String id = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(event))).json().get("id").asString();

        ApiClient.Response response = api.get("/api/v1/incidents/" + id);

        assertThat(response.status()).isEqualTo(200);
        JsonNode incident = response.json();
        assertThat(incident.get("id").asString()).isEqualTo(id);
        assertThat(incident.get("occurrenceCount").asInt()).isEqualTo(1);
        assertThat(incident.get("recentEventLimit").asInt()).isEqualTo(50);
        assertThat(incident.at("/application/service").asString()).isEqualTo("payment-service");
        assertThat(incident.at("/application/environment").asString()).isEqualTo("staging");
        assertThat(eventIds(incident)).containsExactly(event.toString());
        assertThat(incident.at("/recentEvents/0/traceId").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(incident.get("createdAt").asString()).isNotBlank();
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
        String middle = createIncident("2026-10-11T08:00:00Z", "WARN");

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
        String openWarning = createIncident("2026-10-12T10:00:00Z", "WARN");
        data.setStatus(resolvedError, "RESOLVED");
        String app = "&application=" + applicationName.toUpperCase();

        assertThat(ids(api.get("/api/v1/incidents?status=OPEN" + app).json()))
                .containsExactlyInAnyOrder(openError, openWarning);
        assertThat(ids(api.get("/api/v1/incidents?status=RESOLVED" + app).json()))
                .containsExactly(resolvedError);
        assertThat(ids(api.get("/api/v1/incidents?severity=WARN" + app).json()))
                .containsExactly(openWarning);
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
    void rejectsCreateRequestsThatFailValidation() {
        ApiClient.Response empty = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of()));
        assertThat(empty.status()).isEqualTo(400);
        assertThat(fieldsWithErrors(empty.json())).containsExactly("errorEventIds");

        UUID event = data.errorEvent(applicationId, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        ApiClient.Response longTitle = api.postJson("/api/v1/incidents",
                Map.of("errorEventIds", List.of(event), "title", "x".repeat(256)));
        assertThat(longTitle.status()).isEqualTo(400);
        assertThat(fieldsWithErrors(longTitle.json())).containsExactly("title");

        ApiClient.Response badId = api.postRaw("/api/v1/incidents", "{\"errorEventIds\": [\"not-a-uuid\"]}",
                "application/json");
        assertThat(badId.status()).isEqualTo(400);

        ApiClient.Response malformed = api.postRaw("/api/v1/incidents", "{", "application/json");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.json().get("title").asString()).isEqualTo("Malformed request");
    }

    @Test
    void rejectsUnknownErrorEvents() {
        UUID unknown = UUID.randomUUID();

        ApiClient.Response response = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(unknown)));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("detail").asString()).contains(unknown.toString());
    }

    @Test
    void rejectsEventsFromDifferentApplicationsOrErrors() {
        UUID otherApplication = data.application(applicationName, "checkout-service", "staging");
        UUID event = data.errorEvent(applicationId, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        UUID otherAppEvent = data.errorEvent(otherApplication, fingerprint, "2026-10-12T09:14:06Z", "ERROR");
        UUID otherErrorEvent = data.errorEvent(applicationId, TestData.randomFingerprint(), "2026-10-12T09:14:07Z", "ERROR");

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

    @Test
    void rejectsEventsThatAlreadyBelongToAnIncident() {
        UUID event = data.errorEvent(applicationId, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        String id = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(event))).json().get("id").asString();
        data.setStatus(id, "RESOLVED");

        ApiClient.Response response = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(event)));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("detail").asString()).contains(event.toString());
    }

    @Test
    void allowsOnlyOneUnresolvedIncidentPerError() {
        UUID first = data.errorEvent(applicationId, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        UUID second = data.errorEvent(applicationId, fingerprint, "2026-10-12T11:02:13Z", "ERROR");
        UUID third = data.errorEvent(applicationId, fingerprint, "2026-10-13T08:00:00Z", "ERROR");
        String existing = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(first))).json().get("id").asString();

        ApiClient.Response duplicate = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(second)));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.json().get("detail").asString()).contains(existing);

        data.setStatus(existing, "RESOLVED");
        assertThat(api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(third))).status()).isEqualTo(201);
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

    /** Creates an incident with one error event of its own fingerprint and returns its ID. */
    private String createIncident(String timestamp, String severity) {
        UUID event = data.errorEvent(applicationId, TestData.randomFingerprint(), timestamp, severity);
        ApiClient.Response response = api.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(event)));
        assertThat(response.status()).isEqualTo(201);
        return response.json().get("id").asString();
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
