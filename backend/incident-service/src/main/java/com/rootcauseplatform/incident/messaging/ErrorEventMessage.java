package com.rootcauseplatform.incident.messaging;

import java.time.Instant;
import java.util.UUID;

import com.rootcauseplatform.incident.domain.ApplicationInfo;
import com.rootcauseplatform.incident.domain.Severity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Version 1 of the {@code ErrorEventCreated} message as consumed by incident-service.
 * See docs/events.md. Unknown fields are ignored, so producers can add optional fields
 * without breaking this consumer. Size limits match the incident-service columns.
 */
public record ErrorEventMessage(
        String eventType,
        Integer eventVersion,
        @NotNull UUID errorEventId,
        Instant occurredAt,
        @NotNull @Valid Application application,
        @NotNull Instant eventTimestamp,
        @NotNull Severity severity,
        @Size(max = 255) String exceptionType,
        @NotNull String message,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String fingerprint,
        @Size(max = 128) String traceId,
        @Size(max = 128) String correlationId,
        String source,
        @Size(max = 255) String host) {

    public static final String TYPE = "ErrorEventCreated";
    public static final int VERSION = 1;

    public record Application(
            @NotNull UUID id,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 100) String service,
            @NotBlank @Size(max = 50) String environment) {
    }

    public ApplicationInfo applicationInfo() {
        return new ApplicationInfo(application.id(), application.name(), application.service(),
                application.environment());
    }
}
