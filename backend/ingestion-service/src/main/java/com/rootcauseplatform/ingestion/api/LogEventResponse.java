package com.rootcauseplatform.ingestion.api;

import java.time.Instant;
import java.util.UUID;

import com.rootcauseplatform.ingestion.domain.Severity;

import tools.jackson.databind.JsonNode;

/** A stored log event as returned by {@code GET /api/v1/logs/{id}}. */
public record LogEventResponse(
        UUID id,
        ApplicationSummary application,
        Instant timestamp,
        Severity severity,
        String message,
        String exceptionType,
        String stackTrace,
        String source,
        String host,
        String traceId,
        String correlationId,
        String fingerprint,
        JsonNode metadata,
        Instant receivedAt) {

    public record ApplicationSummary(UUID id, String name, String service, String environment) {
    }
}
