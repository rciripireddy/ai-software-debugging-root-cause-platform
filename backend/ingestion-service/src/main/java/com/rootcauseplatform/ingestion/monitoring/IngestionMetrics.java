package com.rootcauseplatform.ingestion.monitoring;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.rootcauseplatform.ingestion.domain.Severity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counters of the ingestion pipeline, exported at {@code /actuator/prometheus}. Tags have a
 * small fixed set of values; identifiers such as event or application IDs are never tags.
 */
@Component
public class IngestionMetrics {

    private final Map<Severity, Counter> eventsAccepted = new EnumMap<>(Severity.class);
    private final Counter outboxPublished;
    private final Counter outboxPublishFailures;

    public IngestionMetrics(MeterRegistry registry) {
        // Registered up front, so every series exists (at 0) before the first event.
        for (Severity severity : Severity.values()) {
            eventsAccepted.put(severity, Counter.builder("rcp.ingestion.events.accepted")
                    .description("Log events stored together with their outbox message")
                    .tag("severity", severity.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        outboxPublished = Counter.builder("rcp.outbox.published")
                .description("Outbox messages acknowledged by Kafka")
                .register(registry);
        outboxPublishFailures = Counter.builder("rcp.outbox.publish.failures")
                .description("Failed attempts to publish an outbox message; the message is retried later")
                .register(registry);
    }

    public void eventAccepted(Severity severity) {
        eventsAccepted.get(severity).increment();
    }

    public void outboxMessagePublished() {
        outboxPublished.increment();
    }

    public void outboxPublishFailed() {
        outboxPublishFailures.increment();
    }
}
