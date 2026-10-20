package com.rootcauseplatform.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rootcauseplatform.ingestion.ApiClient;
import com.rootcauseplatform.ingestion.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LogIngestionApiTests {

    private static final String STACK_TRACE = """
            java.net.SocketTimeoutException: Read timed out
            \tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:288)
            \tat com.shop.payment.GatewayClient.authorize(GatewayClient.java:74)
            \tat com.shop.payment.PaymentService.charge(PaymentService.java:41)""";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApiClient api;

    /** Each test uses its own application name so tests do not depend on each other's data. */
    private String applicationName;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        applicationName = "test-app-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void acceptsAValidEventAndReturnsItsIdAndFingerprint() {
        ApiClient.Response response = api.postJson("/api/v1/logs", validEvent());

        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        String id = body.get("id").asString();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(UUID.fromString(body.get("applicationId").asString())).isNotNull();
        assertThat(body.get("fingerprint").asString()).matches("[0-9a-f]{64}");
        assertThat(body.get("receivedAt").asString()).isNotBlank();
        assertThat(response.header("Location")).isEqualTo("/api/v1/logs/" + id);
    }

    @Test
    void persistsTheEventAndItsRawPayload() {
        Map<String, Object> event = validEvent();
        event.put("metadata", Map.of("orderId", "ord-1001", "attempt", 3));
        String id = api.postJson("/api/v1/logs", event).json().get("id").asString();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select e.severity, e.message, e.exception_type, e.trace_id, e.correlation_id, e.host,
                       e.raw_payload ->> 'application' as payload_application,
                       e.raw_payload -> 'metadata' ->> 'orderId' as payload_order_id,
                       a.name, a.service_name, a.environment
                from ingestion.error_events e join ingestion.applications a on a.id = e.application_id
                where e.id = ?::uuid""", id);

        assertThat(row)
                .containsEntry("severity", "ERROR")
                .containsEntry("message", "Payment authorization failed")
                .containsEntry("exception_type", "java.net.SocketTimeoutException")
                .containsEntry("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736")
                .containsEntry("correlation_id", "order-1001")
                .containsEntry("host", "payment-5d8b7-fghij")
                .containsEntry("payload_application", applicationName)
                .containsEntry("payload_order_id", "ord-1001")
                .containsEntry("name", applicationName)
                .containsEntry("service_name", "payment-service")
                .containsEntry("environment", "staging");
    }

    @Test
    void returnsAStoredEventById() {
        Map<String, Object> event = validEvent();
        event.put("metadata", Map.of("region", "eu-west-1"));
        String id = api.postJson("/api/v1/logs", event).json().get("id").asString();

        ApiClient.Response response = api.get("/api/v1/logs/" + id);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.get("id").asString()).isEqualTo(id);
        assertThat(body.at("/application/name").asString()).isEqualTo(applicationName);
        assertThat(body.at("/application/service").asString()).isEqualTo("payment-service");
        assertThat(body.get("severity").asString()).isEqualTo("ERROR");
        assertThat(body.get("timestamp").asString()).isEqualTo("2026-10-12T09:14:05.402Z");
        assertThat(body.get("stackTrace").asString()).isEqualTo(STACK_TRACE);
        assertThat(body.at("/metadata/region").asString()).isEqualTo("eu-west-1");
    }

    @Test
    void reusesAnExistingApplicationAndNormalizesItsName() {
        String firstId = api.postJson("/api/v1/logs", validEvent()).json().get("applicationId").asString();

        Map<String, Object> sameApplication = validEvent();
        sameApplication.put("application", applicationName.toUpperCase());
        sameApplication.put("environment", "Staging");
        String secondId = api.postJson("/api/v1/logs", sameApplication).json().get("applicationId").asString();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(countApplications()).isEqualTo(1);
    }

    @Test
    void createsASeparateApplicationPerEnvironment() {
        String stagingId = api.postJson("/api/v1/logs", validEvent()).json().get("applicationId").asString();

        Map<String, Object> production = validEvent();
        production.put("environment", "production");
        String productionId = api.postJson("/api/v1/logs", production).json().get("applicationId").asString();

        assertThat(productionId).isNotEqualTo(stagingId);
        assertThat(countApplications()).isEqualTo(2);
    }

    @Test
    void registersANewApplicationOnlyOnceUnderConcurrentIngestion() throws Exception {
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(() -> api.postJson("/api/v1/logs", validEvent()).status());
        }

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
            for (Future<Integer> result : executor.invokeAll(requests)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).containsOnly(201);
        assertThat(countApplications()).isEqualTo(1);
    }

    @Test
    void assignsTheSameFingerprintToRepeatedOccurrencesOfAnError() {
        Map<String, Object> first = validEvent();
        Map<String, Object> second = validEvent();
        second.put("timestamp", "2026-10-12T11:02:13.775Z");
        second.put("host", "payment-5d8b7-pqrst");
        second.put("traceId", "0e0e47364bf92f3577b34da6a3ce929d");
        second.put("stackTrace", STACK_TRACE.replace(":74)", ":76)"));

        String firstFingerprint = api.postJson("/api/v1/logs", first).json().get("fingerprint").asString();
        String secondFingerprint = api.postJson("/api/v1/logs", second).json().get("fingerprint").asString();

        assertThat(secondFingerprint).isEqualTo(firstFingerprint);

        Map<String, Object> differentError = validEvent();
        differentError.put("exceptionType", "java.net.ConnectException");
        assertThat(api.postJson("/api/v1/logs", differentError).json().get("fingerprint").asString())
                .isNotEqualTo(firstFingerprint);
    }

    @Test
    void acceptsAMinimalEventWithoutOptionalFields() {
        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("application", applicationName);
        minimal.put("service", "worker");
        minimal.put("environment", "dev");
        minimal.put("timestamp", "2026-10-12T09:14:05+02:00");
        minimal.put("severity", "WARN");
        minimal.put("message", "Queue depth above threshold");

        ApiClient.Response response = api.postJson("/api/v1/logs", minimal);

        assertThat(response.status()).isEqualTo(201);
        String id = response.json().get("id").asString();
        assertThat(api.get("/api/v1/logs/" + id).json().get("timestamp").asString())
                .isEqualTo("2026-10-12T07:14:05Z");
    }

    @Test
    void rejectsEventsWithMissingRequiredFields() {
        ApiClient.Response response = api.postJson("/api/v1/logs", Map.of("message", " "));

        assertThat(response.status()).isEqualTo(400);
        JsonNode body = response.json();
        assertThat(body.get("title").asString()).isEqualTo("Validation failed");
        assertThat(fieldsWithErrors(body))
                .containsExactlyInAnyOrder("application", "service", "environment", "timestamp", "severity", "message");
        assertThat(countEventsForApplication()).isZero();
    }

    @Test
    void rejectsInvalidFieldValues() {
        Map<String, Object> event = validEvent();
        event.put("application", "shop app; drop table");
        event.put("traceId", "x".repeat(129));

        ApiClient.Response response = api.postJson("/api/v1/logs", event);

        assertThat(response.status()).isEqualTo(400);
        assertThat(fieldsWithErrors(response.json())).containsExactlyInAnyOrder("application", "traceId");
    }

    @Test
    void rejectsUnknownSeverityWithTheOffendingField() {
        Map<String, Object> event = validEvent();
        event.put("severity", "CRITICAL");

        ApiClient.Response response = api.postJson("/api/v1/logs", event);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asString()).isEqualTo("The value of field 'severity' is invalid.");
    }

    @Test
    void rejectsUnparseableTimestamps() {
        Map<String, Object> event = validEvent();
        event.put("timestamp", "yesterday");

        ApiClient.Response response = api.postJson("/api/v1/logs", event);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asString()).isEqualTo("The value of field 'timestamp' is invalid.");
    }

    @Test
    void rejectsMalformedJson() {
        ApiClient.Response response = api.postRaw("/api/v1/logs", "{\"application\": ", "application/json");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("title").asString()).isEqualTo("Malformed request");
    }

    @Test
    void rejectsNonJsonContent() {
        ApiClient.Response response = api.postRaw("/api/v1/logs", "severity=ERROR", "text/plain");

        assertThat(response.status()).isEqualTo(415);
    }

    @Test
    void returnsNotFoundForAnUnknownEvent() {
        ApiClient.Response response = api.get("/api/v1/logs/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(response.json().get("title").asString()).isEqualTo("Resource not found");
    }

    @Test
    void returnsBadRequestForAnInvalidEventId() {
        ApiClient.Response response = api.get("/api/v1/logs/not-a-uuid");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asString()).isEqualTo("Parameter 'id' has an invalid value.");
    }

    @Test
    void errorResponsesDoNotExposeInternals() {
        List<ApiClient.Response> errors = List.of(
                api.postRaw("/api/v1/logs", "{not json", "application/json"),
                api.postJson("/api/v1/logs", Map.of("severity", "CRITICAL")),
                api.get("/api/v1/logs/" + UUID.randomUUID()));

        for (ApiClient.Response error : errors) {
            assertThat(error.body())
                    .doesNotContain("Exception")
                    .doesNotContain("\tat ")
                    .doesNotContain("tools.jackson")
                    .doesNotContain("org.springframework");
            assertThat(error.json().get("correlationId").asString()).isNotBlank();
        }
    }

    @Test
    void propagatesAProvidedCorrelationId() {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/logs/" + UUID.randomUUID()))
                .header("X-Correlation-Id", "test-correlation-123")
                .GET()
                .build();

        ApiClient.Response response = api.send(request);

        assertThat(response.header("X-Correlation-Id")).isEqualTo("test-correlation-123");
        assertThat(response.json().get("correlationId").asString()).isEqualTo("test-correlation-123");
    }

    private Map<String, Object> validEvent() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("application", applicationName);
        event.put("service", "payment-service");
        event.put("environment", "staging");
        event.put("timestamp", "2026-10-12T09:14:05.402Z");
        event.put("severity", "ERROR");
        event.put("message", "Payment authorization failed");
        event.put("exceptionType", "java.net.SocketTimeoutException");
        event.put("stackTrace", STACK_TRACE);
        event.put("source", "com.shop.payment.GatewayClient");
        event.put("host", "payment-5d8b7-fghij");
        event.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        event.put("correlationId", "order-1001");
        return event;
    }

    private static List<String> fieldsWithErrors(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        problem.get("errors").forEach(error -> fields.add(error.get("field").asString()));
        return fields;
    }

    private int countApplications() {
        return jdbcTemplate.queryForObject(
                "select count(*) from ingestion.applications where name = ?", Integer.class, applicationName);
    }

    private int countEventsForApplication() {
        return jdbcTemplate.queryForObject("""
                select count(*) from ingestion.error_events e
                join ingestion.applications a on a.id = e.application_id where a.name = ?""",
                Integer.class, applicationName);
    }
}
