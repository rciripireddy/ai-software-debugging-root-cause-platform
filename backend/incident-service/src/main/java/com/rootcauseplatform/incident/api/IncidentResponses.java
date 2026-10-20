package com.rootcauseplatform.incident.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.rootcauseplatform.incident.domain.Application;
import com.rootcauseplatform.incident.domain.ErrorEvent;
import com.rootcauseplatform.incident.domain.Incident;
import com.rootcauseplatform.incident.domain.IncidentStatus;
import com.rootcauseplatform.incident.domain.Severity;

/** Response bodies of the incident API. Entities are never serialized directly. */
public final class IncidentResponses {

    private IncidentResponses() {
    }

    public record ApplicationSummary(UUID id, String name, String service, String environment) {

        static ApplicationSummary from(Application application) {
            return new ApplicationSummary(application.getId(), application.getName(),
                    application.getServiceName(), application.getEnvironment());
        }
    }

    public record IncidentSummary(
            UUID id,
            String title,
            IncidentStatus status,
            Severity severity,
            ApplicationSummary application,
            int occurrenceCount,
            Instant firstSeenAt,
            Instant lastSeenAt,
            String fingerprint,
            Instant createdAt,
            Instant updatedAt) {

        static IncidentSummary from(Incident incident) {
            return new IncidentSummary(incident.getId(), incident.getTitle(), incident.getStatus(),
                    incident.getSeverity(), ApplicationSummary.from(incident.getApplication()),
                    incident.getOccurrenceCount(), incident.getFirstSeenAt(), incident.getLastSeenAt(),
                    incident.getFingerprint(), incident.getCreatedAt(), incident.getUpdatedAt());
        }
    }

    public record EventSummary(
            UUID id,
            Instant timestamp,
            Severity severity,
            String message,
            String exceptionType,
            String host,
            String traceId,
            String correlationId) {

        static EventSummary from(ErrorEvent event) {
            return new EventSummary(event.getId(), event.getEventTimestamp(), event.getSeverity(),
                    event.getMessage(), event.getExceptionType(), event.getHost(), event.getTraceId(),
                    event.getCorrelationId());
        }
    }

    /**
     * Incident details with its most recent events. {@code occurrenceCount} is the total;
     * {@code recentEvents} contains at most {@code recentEventLimit} of them, newest first.
     */
    public record IncidentDetail(
            UUID id,
            String title,
            IncidentStatus status,
            Severity severity,
            ApplicationSummary application,
            int occurrenceCount,
            Instant firstSeenAt,
            Instant lastSeenAt,
            String fingerprint,
            String summary,
            Instant createdAt,
            Instant updatedAt,
            int recentEventLimit,
            List<EventSummary> recentEvents) {

        static IncidentDetail from(Incident incident, List<ErrorEvent> recentEvents, int recentEventLimit) {
            return new IncidentDetail(incident.getId(), incident.getTitle(), incident.getStatus(),
                    incident.getSeverity(), ApplicationSummary.from(incident.getApplication()),
                    incident.getOccurrenceCount(), incident.getFirstSeenAt(), incident.getLastSeenAt(),
                    incident.getFingerprint(), incident.getSummary(), incident.getCreatedAt(),
                    incident.getUpdatedAt(), recentEventLimit, recentEvents.stream().map(EventSummary::from).toList());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

        static <T> PageResponse<T> from(Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                    page.getTotalPages());
        }
    }
}
