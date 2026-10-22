package com.rootcauseplatform.ingestion.service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rootcauseplatform.ingestion.domain.Application;
import com.rootcauseplatform.ingestion.domain.ApplicationRepository;
import com.rootcauseplatform.ingestion.domain.ErrorEvent;
import com.rootcauseplatform.ingestion.domain.ErrorEventRepository;
import com.rootcauseplatform.ingestion.domain.Severity;

/** Read-only queries over stored events for the dashboard: lists, activity counts, applications. */
@Service
public class EventQueryService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("eventTimestamp"), Sort.Order.desc("id"));
    private static final Sort APPLICATION_ORDER = Sort.by("name", "serviceName", "environment");

    private final ErrorEventRepository events;
    private final ApplicationRepository applications;

    public EventQueryService(ErrorEventRepository events, ApplicationRepository applications) {
        this.events = events;
        this.applications = applications;
    }

    /** Events matching the filter, most recent event timestamp first. */
    @Transactional(readOnly = true)
    public Page<ErrorEvent> list(EventFilter filter, int page, int size) {
        return events.findAll(filter.toSpecification(), PageRequest.of(page, size, NEWEST_FIRST));
    }

    /** Events received (stored) within the window before {@code now}, by severity and service. */
    @Transactional(readOnly = true)
    public EventActivity activity(Duration window, Instant now) {
        Instant since = now.minus(window);
        Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity, 0L);
        }
        long total = 0;
        for (Object[] row : events.countBySeveritySince(since)) {
            long count = ((Number) row[1]).longValue();
            bySeverity.put((Severity) row[0], count);
            total += count;
        }
        List<ServiceActivity> byService = events.countByServiceSince(since).stream()
                .map(row -> new ServiceActivity((String) row[0], ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue()))
                .toList();
        return new EventActivity(since, now, total, bySeverity, byService);
    }

    @Transactional(readOnly = true)
    public List<Application> applications() {
        return applications.findAll(APPLICATION_ORDER);
    }

    public record EventActivity(Instant since, Instant until, long totalEvents, Map<Severity, Long> bySeverity,
            List<ServiceActivity> byService) {
    }

    /** {@code errors} counts events of {@code ERROR} and {@code FATAL} severity. */
    public record ServiceActivity(String service, long events, long errors) {
    }
}
