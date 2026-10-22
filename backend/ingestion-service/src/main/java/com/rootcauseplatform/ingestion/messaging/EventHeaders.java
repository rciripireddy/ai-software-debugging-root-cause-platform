package com.rootcauseplatform.ingestion.messaging;

/** Kafka header names used on platform messages. Values are UTF-8 strings. */
public final class EventHeaders {

    public static final String EVENT_TYPE = "event-type";
    public static final String EVENT_VERSION = "event-version";
    /** Correlation ID of the HTTP request that caused the message. */
    public static final String CORRELATION_ID = "correlation-id";
    /** Time the message was handed to the Kafka producer. */
    public static final String PUBLISHED_AT = "published-at";

    private EventHeaders() {
    }
}
