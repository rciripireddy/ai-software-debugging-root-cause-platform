package com.rootcauseplatform.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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

import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class EventQueryApiTests {

    @Value("${local.server.port}")
    private int port;

    private ApiClient api;

    /** Each test uses its own application name so tests do not depend on each other's data. */
    private String applicationName;

    @BeforeEach
    void setUp() {
        api = TestAuth.client(port);
        applicationName = "query-app-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void listsEventsNewestFirstWithoutStackTracesOrPayloads() {
        send("payment-service", "ERROR", "2026-10-12T09:00:00Z", "Payment authorization failed");
        send("payment-service", "WARN", "2026-10-12T11:00:00Z", "Gateway responded slowly");
        send("checkout-service", "ERROR", "2026-10-12T10:00:00Z", "Order could not be completed");

        JsonNode body = list("application=" + applicationName);

        assertThat(body.get("totalItems").asLong()).isEqualTo(3);
        assertThat(messages(body)).containsExactly("Gateway responded slowly", "Order could not be completed",
                "Payment authorization failed");
        JsonNode payment = body.get("items").get(2);
        assertThat(payment.get("application").get("service").asString()).isEqualTo("payment-service");
        assertThat(payment.get("exceptionType").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(payment.get("fingerprint").asString()).matches("[0-9a-f]{64}");
        assertThat(payment.get("traceId").asString()).isEqualTo("trace-1");
        assertThat(payment.get("hasStackTrace").asBoolean()).isTrue();
        assertThat(payment.has("stackTrace")).isFalse();
        assertThat(payment.has("metadata")).isFalse();
        assertThat(payment.has("rawPayload")).isFalse();
    }

    @Test
    void filtersByServiceSeverityEnvironmentAndText() {
        send("payment-service", "ERROR", "2026-10-12T09:00:00Z", "Payment authorization failed");
        send("payment-service", "WARN", "2026-10-12T11:00:00Z", "Gateway responded slowly");
        send("checkout-service", "ERROR", "2026-10-12T10:00:00Z", "Order could not be completed");

        assertThat(messages(list("application=" + applicationName + "&service=PAYMENT-service")))
                .containsExactly("Gateway responded slowly", "Payment authorization failed");
        assertThat(messages(list("application=" + applicationName + "&severity=ERROR")))
                .containsExactly("Order could not be completed", "Payment authorization failed");
        assertThat(messages(list("application=" + applicationName + "&q=AUTHORIZATION")))
                .containsExactly("Payment authorization failed");
        assertThat(messages(list("application=" + applicationName + "&q=illegalstate&service=checkout-service")))
                .containsExactly("Order could not be completed");
        assertThat(list("application=" + applicationName + "&environment=production").get("totalItems")
                .asLong()).isZero();
    }

    @Test
    void searchTreatsWildcardsLiterally() {
        send("payment-service", "ERROR", "2026-10-12T09:00:00Z", "Quota at 100% for tenant_a");
        send("payment-service", "ERROR", "2026-10-12T09:01:00Z", "Quota at 1000 for tenantXa");

        assertThat(messages(list("application=" + applicationName + "&q=100%25"))).containsExactly(
                "Quota at 100% for tenant_a");
        assertThat(messages(list("application=" + applicationName + "&q=tenant_a"))).containsExactly(
                "Quota at 100% for tenant_a");
    }

    @Test
    void paginatesResults() {
        for (int i = 0; i < 5; i++) {
            send("payment-service", "ERROR", "2026-10-12T09:0" + i + ":00Z", "Failure " + i);
        }

        JsonNode secondPage = list("application=" + applicationName + "&page=1&size=2");

        assertThat(secondPage.get("page").asInt()).isEqualTo(1);
        assertThat(secondPage.get("size").asInt()).isEqualTo(2);
        assertThat(secondPage.get("totalItems").asLong()).isEqualTo(5);
        assertThat(secondPage.get("totalPages").asInt()).isEqualTo(3);
        assertThat(messages(secondPage)).containsExactly("Failure 2", "Failure 1");
    }

    @Test
    void rejectsInvalidListParameters() {
        assertThat(api.get("/api/v1/logs?size=101").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/logs?page=-1").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/logs?severity=LOUD").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/logs?q=" + "x".repeat(201)).status()).isEqualTo(400);
        assertThat(api.get("/api/v1/logs/activity?hours=0").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/logs/activity?hours=169").status()).isEqualTo(400);
    }

    @Test
    void countsRecentlyReceivedEventsBySeverityAndService() {
        String service = "activity-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode before = api.get("/api/v1/logs/activity").json();
        send(service, "ERROR", "2026-10-01T09:00:00Z", "Old event timestamp, received now");
        send(service, "FATAL", "2026-10-12T09:00:00Z", "Out of memory");
        send(service, "INFO", "2026-10-12T09:01:00Z", "Started");

        JsonNode after = api.get("/api/v1/logs/activity?hours=24").json();

        // Counted by time of receipt, so the old event timestamp does not matter.
        assertThat(after.get("totalEvents").asLong() - before.get("totalEvents").asLong()).isEqualTo(3);
        assertThat(after.get("bySeverity").get("FATAL").asLong()
                - before.get("bySeverity").get("FATAL").asLong()).isEqualTo(1);
        assertThat(after.get("bySeverity").has("DEBUG")).isTrue();
        JsonNode serviceRow = null;
        for (JsonNode row : after.get("byService")) {
            if (row.get("service").asString().equals(service)) {
                serviceRow = row;
            }
        }
        assertThat(serviceRow).isNotNull();
        assertThat(serviceRow.get("events").asLong()).isEqualTo(3);
        assertThat(serviceRow.get("errors").asLong()).isEqualTo(2);
        assertThat(after.get("since").asString()).isNotBlank();
    }

    @Test
    void listsRegisteredApplications() {
        send("payment-service", "ERROR", "2026-10-12T09:00:00Z", "Payment authorization failed");
        send("checkout-service", "ERROR", "2026-10-12T10:00:00Z", "Order could not be completed");

        List<String> services = new ArrayList<>();
        for (JsonNode application : api.get("/api/v1/applications").json().get("items")) {
            if (application.get("name").asString().equals(applicationName)) {
                services.add(application.get("service").asString() + "/" + application.get("environment").asString());
                assertThat(application.get("registeredAt").asString()).isNotBlank();
            }
        }
        assertThat(services).containsExactly("checkout-service/staging", "payment-service/staging");
    }

    private void send(String service, String severity, String timestamp, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("application", applicationName);
        event.put("service", service);
        event.put("environment", "staging");
        event.put("timestamp", timestamp);
        event.put("severity", severity);
        event.put("message", message);
        if (!severity.equals("WARN") && !severity.equals("INFO")) {
            event.put("exceptionType", "java.lang.IllegalStateException");
            event.put("stackTrace", "java.lang.IllegalStateException: boom\n\tat com.shop.Step.run(Step.java:1)");
            event.put("traceId", "trace-1");
        }
        event.put("metadata", Map.of("dataset", "test"));
        assertThat(api.postJson("/api/v1/logs", event).status()).isEqualTo(201);
    }

    private JsonNode list(String query) {
        ApiClient.Response response = api.get("/api/v1/logs?" + query);
        assertThat(response.status()).isEqualTo(200);
        return response.json();
    }

    private static List<String> messages(JsonNode page) {
        List<String> messages = new ArrayList<>();
        page.get("items").forEach(item -> messages.add(item.get("message").asString()));
        return messages;
    }
}
