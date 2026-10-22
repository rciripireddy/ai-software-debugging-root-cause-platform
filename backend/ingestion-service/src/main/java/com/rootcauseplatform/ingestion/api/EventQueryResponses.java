package com.rootcauseplatform.ingestion.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.rootcauseplatform.ingestion.domain.Application;
import com.rootcauseplatform.ingestion.domain.ErrorEvent;
import com.rootcauseplatform.ingestion.domain.Severity;
import com.rootcauseplatform.ingestion.service.EventQueryService.EventActivity;
import com.rootcauseplatform.ingestion.service.EventQueryService.ServiceActivity;

/** Response bodies of the event list, activity and application endpoints. */
public final class EventQueryResponses {

    private EventQueryResponses() {
    }

    /**
     * An event in a list. Stack trace, metadata and the raw payload are left out; they are
     * available per event from {@code GET /api/v1/logs/{id}}.
     */
    public record EventSummary(
            UUID id,
            LogEventResponse.ApplicationSummary application,
            Instant timestamp,
            Severity severity,
            String message,
            String exceptionType,
            String source,
            String host,
            String traceId,
            String correlationId,
            String fingerprint,
            boolean hasStackTrace,
            Instant receivedAt) {

        static EventSummary from(ErrorEvent event) {
            Application application = event.getApplication();
            return new EventSummary(event.getId(), new LogEventResponse.ApplicationSummary(application.getId(),
                    application.getName(), application.getServiceName(), application.getEnvironment()),
                    event.getEventTimestamp(), event.getSeverity(), event.getMessage(), event.getExceptionType(),
                    event.getSource(), event.getHost(), event.getTraceId(), event.getCorrelationId(),
                    event.getFingerprint(), event.getStackTrace() != null, event.getCreatedAt());
        }
    }

    public record ActivityResponse(Instant since, Instant until, long totalEvents, Map<Severity, Long> bySeverity,
            List<ServiceActivity> byService) {

        static ActivityResponse from(EventActivity activity) {
            return new ActivityResponse(activity.since(), activity.until(), activity.totalEvents(),
                    activity.bySeverity(), activity.byService());
        }
    }

    public record ApplicationResponse(UUID id, String name, String service, String environment,
            Instant registeredAt) {

        static ApplicationResponse from(Application application) {
            return new ApplicationResponse(application.getId(), application.getName(),
                    application.getServiceName(), application.getEnvironment(), application.getCreatedAt());
        }
    }

    /** Same shape as the incident API's page response. */
    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

        static <T> PageResponse<T> from(Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                    page.getTotalPages());
        }
    }

    public record ItemsResponse<T>(List<T> items) {
    }
}
