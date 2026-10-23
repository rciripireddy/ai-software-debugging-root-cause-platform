package com.rootcauseplatform.incident.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import com.rootcauseplatform.incident.TestMessages;
import com.rootcauseplatform.incident.domain.ProcessingOutcome;
import com.rootcauseplatform.incident.messaging.ErrorEventListener;
import com.rootcauseplatform.incident.messaging.ErrorEventMessage;
import com.rootcauseplatform.incident.messaging.ErrorEventMessageReader;
import com.rootcauseplatform.incident.service.IncidentCorrelationService;
import com.rootcauseplatform.incident.service.IncidentCorrelationService.CorrelationResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real listener against an in-memory registry. (The Kafka integration tests cannot
 * assert counter values: cached test contexts share the consumer group, so a message may be
 * counted by another context's listener.)
 */
class EventProcessingMetricsTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EventProcessingMetrics metrics = new EventProcessingMetrics(registry);
    private final IncidentCorrelationService correlation = mock(IncidentCorrelationService.class);
    private final ErrorEventListener listener = new ErrorEventListener(
            new ErrorEventMessageReader(jsonMapper, Validation.buildDefaultValidatorFactory().getValidator()),
            correlation, metrics);

    @Test
    void everyOutcomeHasASeriesFromTheStart() {
        for (ProcessingOutcome outcome : ProcessingOutcome.values()) {
            assertThat(consumed(outcome.name().toLowerCase())).isZero();
        }
        assertThat(registry.get("rcp.events.consumer.retries").counter().count()).isZero();
        assertThat(registry.get("rcp.events.dead.lettered").counter().count()).isZero();
    }

    @Test
    void countsEachProcessedMessageByOutcome() {
        when(correlation.correlate(any(ErrorEventMessage.class)))
                .thenReturn(new CorrelationResult(ProcessingOutcome.INCIDENT_CREATED, UUID.randomUUID()))
                .thenReturn(new CorrelationResult(ProcessingOutcome.DUPLICATE, null))
                .thenReturn(new CorrelationResult(ProcessingOutcome.DUPLICATE, null));

        for (int i = 0; i < 3; i++) {
            listener.onMessage(record());
        }

        assertThat(consumed("incident_created")).isEqualTo(1);
        assertThat(consumed("duplicate")).isEqualTo(2);
        assertThat(consumed("incident_updated")).isZero();
    }

    @Test
    void retriesAndDeadLettersAreCountedSeparately() {
        metrics.retried();
        metrics.retried();
        metrics.deadLettered();

        assertThat(registry.get("rcp.events.consumer.retries").counter().count()).isEqualTo(2);
        assertThat(registry.get("rcp.events.dead.lettered").counter().count()).isEqualTo(1);
    }

    private double consumed(String outcome) {
        return registry.get("rcp.events.consumed").tag("outcome", outcome).counter().count();
    }

    private ConsumerRecord<String, String> record() {
        String value = jsonMapper.writeValueAsString(TestMessages.errorEventCreated(UUID.randomUUID(),
                UUID.randomUUID(), "metrics-app", "f".repeat(64), "2026-10-12T09:14:05Z", "ERROR"));
        return new ConsumerRecord<>("error-events.v1", 0, 0, "key", value);
    }
}
