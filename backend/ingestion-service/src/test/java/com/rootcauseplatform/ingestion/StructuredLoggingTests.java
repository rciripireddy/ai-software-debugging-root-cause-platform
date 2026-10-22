package com.rootcauseplatform.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

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

    @Test
    void writesIngestionLogsAsValidEcsJson(CapturedOutput output) {
        ApiClient.Response response = TestAuth.client(port).postJson("/api/v1/logs", Map.of(
                "application", "logging-test",
                "service", "payment-service",
                "environment", "test",
                "timestamp", "2026-10-12T09:14:05Z",
                "severity", "ERROR",
                "message", "Payment authorization failed",
                "traceId", "trace-123"));
        assertThat(response.status()).isEqualTo(201);
        String eventId = response.json().get("id").asString();

        List<String> lines = output.getOut().lines().filter(line -> line.startsWith("{")).toList();
        assertThat(output.getOut()).doesNotContain("failed to append");

        JsonNode ingested = lines.stream()
                .map(jsonMapper::readTree)
                .filter(record -> "Log event ingested".equals(record.path("message").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No 'Log event ingested' record in:\n" + output.getOut()));
        assertThat(ingested.get("action").asString()).isEqualTo("log.ingest");
        assertThat(ingested.get("eventId").asString()).isEqualTo(eventId);
        assertThat(ingested.get("serviceName").asString()).isEqualTo("payment-service");
        assertThat(ingested.get("eventTraceId").asString()).isEqualTo("trace-123");
        assertThat(ingested.get("correlationId").asString()).isNotBlank();
        assertThat(ingested.at("/service/name").asString()).isEqualTo("ingestion-service");
        assertThat(ingested.toString()).doesNotContain("Payment authorization failed");
    }
}
