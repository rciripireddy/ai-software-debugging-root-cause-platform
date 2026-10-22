package com.rootcauseplatform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaOperations;
import org.testcontainers.kafka.KafkaContainer;

import com.rootcauseplatform.incident.messaging.KafkaTopicProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs with the JSON (ECS) log format used in Docker. Guards against key-value names that
 * clash with ECS fields such as {@code service}, which make Logback drop the log event.
 */
@SpringBootTest(properties = "logging.structured.format.console=ecs")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private KafkaOperations<String, String> kafkaOperations;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private KafkaTopicProperties topics;

    @Test
    void writesConsumerLogsAsValidEcsJsonWithKafkaContext(CapturedOutput output) {
        UUID eventId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        String fingerprint = TestData.randomFingerprint();
        String message = jsonMapper.writeValueAsString(TestMessages.errorEventCreated(eventId, applicationId,
                "logging-test", fingerprint, "2026-10-12T09:14:05Z", "ERROR"));

        new KafkaTestSupport(kafkaOperations, kafkaContainer).send(topics.errorEvents(),
                TestMessages.key(applicationId, fingerprint), message, "logging-test-correlation");

        JsonNode processed = await().atMost(Duration.ofSeconds(30))
                .until(() -> findRecord(output, eventId), Optional::isPresent)
                .orElseThrow();
        assertThat(output.getOut()).doesNotContain("failed to append");
        assertThat(processed.get("action").asString()).isEqualTo("event.correlate");
        assertThat(processed.get("outcome").asString()).isEqualTo("INCIDENT_CREATED");
        assertThat(processed.get("incidentId").asString()).isNotBlank();
        assertThat(processed.get("serviceName").asString()).isEqualTo("payment-service");
        assertThat(processed.get("topic").asString()).isEqualTo("error-events.v1");
        assertThat(processed.has("partition")).isTrue();
        assertThat(processed.has("offset")).isTrue();
        assertThat(processed.get("correlationId").asString()).isEqualTo("logging-test-correlation");
        assertThat(processed.at("/service/name").asString()).isEqualTo("incident-service");
        assertThat(processed.toString()).doesNotContain("Payment authorization failed");
    }

    private Optional<JsonNode> findRecord(CapturedOutput output, UUID eventId) {
        return output.getOut().lines()
                .filter(line -> line.startsWith("{") && line.contains(eventId.toString()))
                .map(jsonMapper::readTree)
                .filter(record -> "Error event processed".equals(record.path("message").asString()))
                .findFirst();
    }
}
