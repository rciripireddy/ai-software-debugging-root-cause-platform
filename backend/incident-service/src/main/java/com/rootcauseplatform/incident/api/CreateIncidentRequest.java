package com.rootcauseplatform.incident.api;

import java.util.List;
import java.util.UUID;

import com.rootcauseplatform.incident.domain.Severity;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Creates an incident from stored error events. The events must belong to the same
 * application and share a fingerprint. Title and severity are derived from the events
 * when not provided.
 */
public record CreateIncidentRequest(
        @NotEmpty @Size(max = 100)
        List<@NotNull UUID> errorEventIds,

        @Size(max = 255)
        String title,

        @Size(max = 10_000)
        String summary,

        Severity severity) {
}
