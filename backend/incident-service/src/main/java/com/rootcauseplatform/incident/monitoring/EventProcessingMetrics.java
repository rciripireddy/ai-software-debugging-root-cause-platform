package com.rootcauseplatform.incident.monitoring;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.rootcauseplatform.incident.domain.ProcessingOutcome;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counters of the Kafka consumer that turns error events into incidents, exported at
 * {@code /actuator/prometheus}. The only tag is the processing outcome, a fixed set of values.
 */
@Component
public class EventProcessingMetrics {

    private final Map<ProcessingOutcome, Counter> consumed = new EnumMap<>(ProcessingOutcome.class);
    private final Counter retries;
    private final Counter deadLettered;

    public EventProcessingMetrics(MeterRegistry registry) {
        // Registered up front, so every series exists (at 0) before the first message.
        for (ProcessingOutcome outcome : ProcessingOutcome.values()) {
            consumed.put(outcome, Counter.builder("rcp.events.consumed")
                    .description("Error-event messages processed, by outcome (incident_created, duplicate, ...)")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        retries = Counter.builder("rcp.events.consumer.retries")
                .description("Failed processing attempts that are retried")
                .register(registry);
        deadLettered = Counter.builder("rcp.events.dead.lettered")
                .description("Messages sent to the dead-letter topic after the last retry or as invalid")
                .register(registry);
    }

    public void consumed(ProcessingOutcome outcome) {
        consumed.get(outcome).increment();
    }

    public void retried() {
        retries.increment();
    }

    public void deadLettered() {
        deadLettered.increment();
    }
}
