package com.rootcauseplatform.incident.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rootcauseplatform.incident.domain.ApplicationInfo;
import com.rootcauseplatform.incident.domain.Incident;
import com.rootcauseplatform.incident.domain.IncidentEvent;
import com.rootcauseplatform.incident.domain.IncidentEventRepository;
import com.rootcauseplatform.incident.domain.IncidentRepository;
import com.rootcauseplatform.incident.domain.ProcessedEvent;
import com.rootcauseplatform.incident.domain.ProcessedEventRepository;
import com.rootcauseplatform.incident.domain.ProcessingOutcome;
import com.rootcauseplatform.incident.domain.Severity;
import com.rootcauseplatform.incident.messaging.ErrorEventMessage;

/**
 * Correlates error events into incidents. The rule is deterministic: events with the same
 * application and fingerprint belong to the same unresolved incident.
 *
 * <ul>
 * <li>No unresolved incident for the application and fingerprint: open a new one.</li>
 * <li>An unresolved incident exists: add the occurrence to it.</li>
 * <li>Events below the minimum severity are recorded as processed but create no incident.</li>
 * </ul>
 *
 * <p>Everything happens in one transaction together with the record in {@code processed_events},
 * so a redelivered message is recognized and skipped, and a failure leaves no partial changes.
 */
@Service
public class IncidentCorrelationService {

    private final IncidentRepository incidents;
    private final IncidentEventRepository incidentEvents;
    private final ProcessedEventRepository processedEvents;
    private final Severity minimumSeverity;

    public IncidentCorrelationService(IncidentRepository incidents, IncidentEventRepository incidentEvents,
            ProcessedEventRepository processedEvents,
            @Value("${app.correlation.minimum-severity:ERROR}") Severity minimumSeverity) {
        this.incidents = incidents;
        this.incidentEvents = incidentEvents;
        this.processedEvents = processedEvents;
        this.minimumSeverity = minimumSeverity;
    }

    @Transactional
    public CorrelationResult correlate(ErrorEventMessage message) {
        UUID eventId = message.errorEventId();
        if (processedEvents.existsById(eventId)) {
            return new CorrelationResult(ProcessingOutcome.DUPLICATE, null);
        }

        CorrelationResult result;
        if (message.severity().compareTo(minimumSeverity) < 0) {
            result = new CorrelationResult(ProcessingOutcome.IGNORED_SEVERITY, null);
        } else {
            Optional<IncidentEvent> assigned = incidentEvents.findById(eventId);
            result = assigned
                    .map(event -> new CorrelationResult(ProcessingOutcome.ALREADY_ASSIGNED, event.getIncidentId()))
                    .orElseGet(() -> addToIncident(message));
        }
        // Inserted, never merged: if a concurrent delivery got here first, the primary key
        // rejects this insert and the whole transaction rolls back.
        processedEvents.save(new ProcessedEvent(eventId, result.outcome(), result.incidentId()));
        return result;
    }

    private CorrelationResult addToIncident(ErrorEventMessage message) {
        ApplicationInfo application = message.applicationInfo();
        Optional<Incident> existing = incidents.findActiveForUpdate(application.id(), message.fingerprint());

        Incident incident;
        ProcessingOutcome outcome;
        if (existing.isPresent()) {
            incident = existing.get();
            incident.recordOccurrence(message.eventTimestamp(), message.severity());
            outcome = ProcessingOutcome.INCIDENT_UPDATED;
        } else {
            incident = incidents.save(Incident.open(application, message.fingerprint(),
                    IncidentTitles.of(message.exceptionType(), message.message()), message.severity(), null,
                    message.eventTimestamp(), message.eventTimestamp(), 1));
            outcome = ProcessingOutcome.INCIDENT_CREATED;
        }

        incidentEvents.save(new IncidentEvent(message.errorEventId(), incident.getId(), message.eventTimestamp(),
                message.severity(), message.message(), message.exceptionType(), message.host(), message.traceId(),
                message.correlationId()));
        return new CorrelationResult(outcome, incident.getId());
    }

    public record CorrelationResult(ProcessingOutcome outcome, UUID incidentId) {
    }
}
