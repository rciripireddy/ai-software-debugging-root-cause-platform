package com.rootcauseplatform.ingestion.messaging;

import java.time.Instant;
import java.util.UUID;

import com.rootcauseplatform.ingestion.domain.Application;
import com.rootcauseplatform.ingestion.domain.ErrorEvent;
import com.rootcauseplatform.ingestion.domain.Severity;

/**
 * Version 1 of the {@code ErrorEventCreated} message, published for every stored log event.
 * See docs/events.md for the contract. Stack traces, metadata and the raw request payload are
 * deliberately left out: consumers only receive what they need to correlate and display events.
 * Optional fields are always present, with {@code null} when the event has no value.
 */
public record ErrorEventCreated(
        String eventType,
        int eventVersion,
        UUID errorEventId,
        Instant occurredAt,
        ApplicationRef application,
        Instant eventTimestamp,
        Severity severity,
        String exceptionType,
        String message,
        String fingerprint,
        String traceId,
        String correlationId,
        String source,
        String host) {

    public static final String TYPE = "ErrorEventCreated";
    public static final int VERSION = 1;

    public record ApplicationRef(UUID id, String name, String service, String environment) {
    }

    public static ErrorEventCreated from(ErrorEvent event) {
        Application application = event.getApplication();
        return new ErrorEventCreated(TYPE, VERSION, event.getId(), event.getCreatedAt(),
                new ApplicationRef(application.getId(), application.getName(), application.getServiceName(),
                        application.getEnvironment()),
                event.getEventTimestamp(), event.getSeverity(), event.getExceptionType(), event.getMessage(),
                event.getFingerprint(), event.getTraceId(), event.getCorrelationId(), event.getSource(),
                event.getHost());
    }

    /**
     * Kafka message key. Occurrences of the same error in the same application share a key, so
     * they land on the same partition and are processed in order by a single consumer.
     */
    public String messageKey() {
        return application.id() + ":" + fingerprint;
    }
}
