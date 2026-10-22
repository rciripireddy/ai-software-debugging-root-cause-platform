package com.rootcauseplatform.incident.messaging;

/** Kafka header names used on platform messages. Values are UTF-8 strings. */
public final class EventHeaders {

    /** Correlation ID of the HTTP request in ingestion-service that caused the message. */
    public static final String CORRELATION_ID = "correlation-id";

    private EventHeaders() {
    }
}
