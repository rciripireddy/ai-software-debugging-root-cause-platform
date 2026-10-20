package com.rootcauseplatform.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs with the JSON (ECS) log format used in Docker. Guards against key-value names that
 * clash with ECS fields such as {@code service}, which make Logback drop the log event.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "logging.structured.format.console=ecs")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void writesIncidentLogsAsValidEcsJson(CapturedOutput output) {
        TestData data = new TestData(jdbcTemplate);
        UUID applicationId = data.application("logging-test-" + UUID.randomUUID(), "payment-service", "test");
        UUID event = data.errorEvent(applicationId, TestData.randomFingerprint(), "2026-10-12T09:14:05Z", "ERROR");

        ApiClient.Response response = new ApiClient(port).postJson("/api/v1/incidents",
                Map.of("errorEventIds", List.of(event)));
        assertThat(response.status()).isEqualTo(201);
        String incidentId = response.json().get("id").asString();

        List<String> lines = output.getOut().lines().filter(line -> line.startsWith("{")).toList();
        assertThat(output.getOut()).doesNotContain("failed to append");

        JsonNode created = lines.stream()
                .map(jsonMapper::readTree)
                .filter(record -> "Incident created".equals(record.path("message").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No 'Incident created' record in:\n" + output.getOut()));
        assertThat(created.get("action").asString()).isEqualTo("incident.create");
        assertThat(created.get("incidentId").asString()).isEqualTo(incidentId);
        assertThat(created.get("serviceName").asString()).isEqualTo("payment-service");
        assertThat(created.get("eventCount").asInt()).isEqualTo(1);
        assertThat(created.at("/service/name").asString()).isEqualTo("incident-service");
    }
}
