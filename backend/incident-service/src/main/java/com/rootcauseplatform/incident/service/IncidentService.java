package com.rootcauseplatform.incident.service;

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
import com.rootcauseplatform.incident.domain.Application;
import com.rootcauseplatform.incident.domain.ErrorEvent;
import com.rootcauseplatform.incident.domain.ErrorEventRepository;
import com.rootcauseplatform.incident.domain.Incident;
import com.rootcauseplatform.incident.domain.IncidentRepository;
import com.rootcauseplatform.incident.domain.Severity;

@Service
public class IncidentService {

    /** Maximum number of events returned with an incident; the total is in occurrenceCount. */
    public static final int RECENT_EVENT_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final int MAX_TITLE_LENGTH = 255;
    private static final Sort NEWEST_ACTIVITY_FIRST = Sort.by(Sort.Order.desc("lastSeenAt"), Sort.Order.desc("id"));

    private final IncidentRepository incidents;
    private final ErrorEventRepository events;

    public IncidentService(IncidentRepository incidents, ErrorEventRepository events) {
        this.incidents = incidents;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<Incident> list(IncidentFilter filter, int page, int size) {
        return incidents.findAll(filter.toSpecification(), PageRequest.of(page, size, NEWEST_ACTIVITY_FIRST));
    }

    @Transactional(readOnly = true)
    public IncidentWithEvents get(UUID id) {
        Incident incident = incidents.findWithApplicationById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Incident " + id + " was not found"));
        return new IncidentWithEvents(incident, events.findLatestByIncidentId(id, Limit.of(RECENT_EVENT_LIMIT)));
    }

    @Transactional
    public IncidentWithEvents create(CreateIncidentRequest request) {
        Set<UUID> eventIds = new LinkedHashSet<>(request.errorEventIds());
        Set<ErrorEvent> errorEvents = new LinkedHashSet<>(events.findAllByIdIn(eventIds));
        verifyAllEventsExist(eventIds, errorEvents);

        requireSingle(errorEvents.stream().map(event -> event.getApplication().getId()).collect(Collectors.toSet()),
                "All error events must belong to the same application.");
        String fingerprint = requireSingle(
                errorEvents.stream().map(ErrorEvent::getFingerprint).collect(Collectors.toSet()),
                "All error events must have the same fingerprint.");
        Application application = errorEvents.iterator().next().getApplication();

        verifyEventsAreUnassigned(eventIds);
        incidents.findActiveIncidentId(application.getId(), fingerprint).ifPresent(existingId -> {
            throw new ConflictException("An unresolved incident already exists for this error: " + existingId + ".");
        });

        Incident incident = Incident.open(application, fingerprint,
                titleFor(request.title(), errorEvents),
                request.severity() != null ? request.severity() : highestSeverity(errorEvents),
                blankToNull(request.summary()),
                errorEvents);
        try {
            incidents.saveAndFlush(incident);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request created an incident for the same error or events first.
            throw new ConflictException("The incident conflicts with one that was created concurrently.");
        }

        log.atInfo()
                .setMessage("Incident created")
                .addKeyValue("action", "incident.create")
                .addKeyValue("incidentId", incident.getId())
                .addKeyValue("applicationId", application.getId())
                .addKeyValue("applicationName", application.getName())
                .addKeyValue("serviceName", application.getServiceName())
                .addKeyValue("environment", application.getEnvironment())
                .addKeyValue("severity", incident.getSeverity())
                .addKeyValue("fingerprint", fingerprint)
                .addKeyValue("eventCount", incident.getOccurrenceCount())
                .log();

        List<ErrorEvent> latest = errorEvents.stream()
                .sorted(Comparator.comparing(ErrorEvent::getEventTimestamp).reversed())
                .limit(RECENT_EVENT_LIMIT)
                .toList();
        return new IncidentWithEvents(incident, latest);
    }

    private static void verifyAllEventsExist(Set<UUID> requested, Set<ErrorEvent> found) {
        if (found.size() != requested.size()) {
            Set<UUID> foundIds = found.stream().map(ErrorEvent::getId).collect(Collectors.toSet());
            List<UUID> missing = requested.stream().filter(id -> !foundIds.contains(id)).toList();
            throw new InvalidIncidentException("Unknown error event IDs: " + missing + ".");
        }
    }

    private void verifyEventsAreUnassigned(Set<UUID> eventIds) {
        List<UUID> assigned = incidents.findAssignedEventIds(eventIds);
        if (!assigned.isEmpty()) {
            throw new ConflictException("Error events already belong to an incident: " + assigned + ".");
        }
    }

    private static <T> T requireSingle(Set<T> values, String errorMessage) {
        if (values.size() != 1) {
            throw new InvalidIncidentException(errorMessage);
        }
        return values.iterator().next();
    }

    private static Severity highestSeverity(Set<ErrorEvent> errorEvents) {
        return errorEvents.stream().map(ErrorEvent::getSeverity).max(Comparator.naturalOrder()).orElseThrow();
    }

    /** Uses the provided title, or "ExceptionType: message" of the most recent event. */
    private static String titleFor(String requestedTitle, Set<ErrorEvent> errorEvents) {
        if (requestedTitle != null && !requestedTitle.isBlank()) {
            return requestedTitle.strip();
        }
        ErrorEvent latest = errorEvents.stream().max(Comparator.comparing(ErrorEvent::getEventTimestamp)).orElseThrow();
        String title = latest.getExceptionType() == null
                ? latest.getMessage()
                : latest.getExceptionType() + ": " + latest.getMessage();
        title = title.strip().replaceAll("\\s+", " ");
        return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH - 1) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record IncidentWithEvents(Incident incident, List<ErrorEvent> latestEvents) {
    }
}
