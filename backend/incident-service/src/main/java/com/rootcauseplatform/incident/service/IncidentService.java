package com.rootcauseplatform.incident.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rootcauseplatform.incident.api.CreateIncidentRequest;
import com.rootcauseplatform.incident.domain.ApplicationInfo;
import com.rootcauseplatform.incident.domain.Incident;
import com.rootcauseplatform.incident.domain.IncidentEvent;
import com.rootcauseplatform.incident.domain.IncidentEventRepository;
import com.rootcauseplatform.incident.domain.IncidentRepository;
import com.rootcauseplatform.incident.domain.Severity;
import com.rootcauseplatform.incident.service.IngestionEventReader.StoredEvent;

/** Incident queries for the REST API, and the deprecated manual incident creation. */
@Service
public class IncidentService {

    /** Maximum number of events returned with an incident; the total is in occurrenceCount. */
    public static final int RECENT_EVENT_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final Sort NEWEST_ACTIVITY_FIRST = Sort.by(Sort.Order.desc("lastSeenAt"), Sort.Order.desc("id"));

    private final IncidentRepository incidents;
    private final IncidentEventRepository incidentEvents;
    private final IngestionEventReader ingestionEvents;

    public IncidentService(IncidentRepository incidents, IncidentEventRepository incidentEvents,
            IngestionEventReader ingestionEvents) {
        this.incidents = incidents;
        this.incidentEvents = incidentEvents;
        this.ingestionEvents = ingestionEvents;
    }

    @Transactional(readOnly = true)
    public Page<Incident> list(IncidentFilter filter, int page, int size) {
        return incidents.findAll(filter.toSpecification(), PageRequest.of(page, size, NEWEST_ACTIVITY_FIRST));
    }

    @Transactional(readOnly = true)
    public IncidentWithEvents get(UUID id) {
        Incident incident = incidents.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Incident " + id + " was not found"));
        return new IncidentWithEvents(incident, latestEvents(id));
    }

    /**
     * Creates an incident from stored error events (Phase 2 behavior). Deprecated: incidents are
     * now created automatically from Kafka messages, so events of {@code ERROR} severity or above
     * usually already belong to an incident and this returns 409.
     */
    @Transactional
    public IncidentWithEvents createFromStoredEvents(CreateIncidentRequest request) {
        Set<UUID> eventIds = new LinkedHashSet<>(request.errorEventIds());
        List<StoredEvent> events = ingestionEvents.findByIds(eventIds);
        verifyAllEventsExist(eventIds, events);

        requireSingle(events.stream().map(event -> event.application().id()).collect(Collectors.toSet()),
                "All error events must belong to the same application.");
        String fingerprint = requireSingle(events.stream().map(StoredEvent::fingerprint).collect(Collectors.toSet()),
                "All error events must have the same fingerprint.");
        ApplicationInfo application = events.getFirst().application();

        List<UUID> assigned = incidentEvents.findAllById(eventIds).stream().map(IncidentEvent::getId).toList();
        if (!assigned.isEmpty()) {
            throw new ConflictException("Error events already belong to an incident: " + assigned + ".");
        }
        incidents.findActiveForUpdate(application.id(), fingerprint).ifPresent(existing -> {
            throw new ConflictException("An unresolved incident already exists for this error: "
                    + existing.getId() + ".");
        });

        StoredEvent latest = events.stream().max(Comparator.comparing(StoredEvent::eventTimestamp)).orElseThrow();
        Incident incident = Incident.open(application, fingerprint,
                request.title() != null && !request.title().isBlank()
                        ? request.title().strip()
                        : IncidentTitles.of(latest.exceptionType(), latest.message()),
                request.severity() != null ? request.severity() : highestSeverity(events),
                blankToNull(request.summary()),
                events.stream().map(StoredEvent::eventTimestamp).min(Instant::compareTo).orElseThrow(),
                latest.eventTimestamp(),
                events.size());
        try {
            incidents.saveAndFlush(incident);
            incidentEvents.saveAllAndFlush(events.stream()
                    .map(event -> new IncidentEvent(event.id(), incident.getId(), event.eventTimestamp(),
                            event.severity(), event.message(), event.exceptionType(), event.host(),
                            event.traceId(), event.correlationId()))
                    .toList());
        } catch (DataIntegrityViolationException e) {
            // The consumer or another request assigned these events or opened the incident first.
            throw new ConflictException("The incident conflicts with one that was created concurrently.");
        }

        log.atWarn()
                .setMessage("Incident created through deprecated endpoint")
                .addKeyValue("action", "incident.create_manual")
                .addKeyValue("incidentId", incident.getId())
                .addKeyValue("applicationId", application.id())
                .addKeyValue("serviceName", application.serviceName())
                .addKeyValue("fingerprint", fingerprint)
                .addKeyValue("eventCount", incident.getOccurrenceCount())
                .log();
        return new IncidentWithEvents(incident, latestEvents(incident.getId()));
    }

    private List<IncidentEvent> latestEvents(UUID incidentId) {
        return incidentEvents.findByIncidentIdOrderByEventTimestampDescErrorEventIdDesc(incidentId,
                Limit.of(RECENT_EVENT_LIMIT));
    }

    private static void verifyAllEventsExist(Set<UUID> requested, List<StoredEvent> found) {
        if (found.size() != requested.size()) {
            Set<UUID> foundIds = found.stream().map(StoredEvent::id).collect(Collectors.toSet());
            List<UUID> missing = requested.stream().filter(id -> !foundIds.contains(id)).toList();
            throw new InvalidIncidentException("Unknown error event IDs: " + missing + ".");
        }
    }

    private static <T> T requireSingle(Set<T> values, String errorMessage) {
        if (values.size() != 1) {
            throw new InvalidIncidentException(errorMessage);
        }
        return values.iterator().next();
    }

    private static Severity highestSeverity(List<StoredEvent> events) {
        return events.stream().map(StoredEvent::severity).max(Comparator.naturalOrder()).orElseThrow();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record IncidentWithEvents(Incident incident, List<IncidentEvent> latestEvents) {
    }
}
