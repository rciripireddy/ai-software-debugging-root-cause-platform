package com.rootcauseplatform.ingestion.api;

import java.time.Instant;
import java.util.Map;

import com.rootcauseplatform.ingestion.domain.Severity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A log or error event reported by a monitored application. */
public record LogEventRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
        String application,

        @NotBlank @Size(max = 100) @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
        String service,

        @NotBlank @Size(max = 50) @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
        String environment,

        @NotNull
        Instant timestamp,

        @NotNull
        Severity severity,

        @NotBlank @Size(max = 10_000)
        String message,

        @Size(max = 255)
        String exceptionType,

        @Size(max = 100_000)
        String stackTrace,

        @Size(max = 255)
        String source,

        @Size(max = 255)
        String host,

        @Size(max = 128)
        String traceId,

        @Size(max = 128)
        String correlationId,

        @Size(max = 50, message = "must not contain more than {max} entries")
        Map<String, Object> metadata) {

    static final String NAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]*$";
    static final String NAME_MESSAGE = "must contain only letters, digits, '.', '_' or '-'";
}
