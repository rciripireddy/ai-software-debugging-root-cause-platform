package com.rootcauseplatform.incident.api;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rootcauseplatform.incident.api.IncidentResponses.IncidentDetail;
import com.rootcauseplatform.incident.api.IncidentResponses.IncidentSummary;
import com.rootcauseplatform.incident.api.IncidentResponses.PageResponse;
import com.rootcauseplatform.incident.domain.IncidentStatus;
import com.rootcauseplatform.incident.domain.Severity;
import com.rootcauseplatform.incident.service.IncidentFilter;
import com.rootcauseplatform.incident.service.IncidentService;
import com.rootcauseplatform.incident.service.IncidentService.IncidentWithEvents;
import com.rootcauseplatform.incident.service.IncidentStatsService;
import com.rootcauseplatform.incident.service.IncidentStatsService.IncidentStats;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(path = "/api/v1/incidents", produces = MediaType.APPLICATION_JSON_VALUE)
public class IncidentController {

    static final String DEPRECATION_HEADER = "Deprecation";
    /** 2026-10-20T00:00:00Z as an RFC 9745 structured-field date. */
    static final String DEPRECATED_SINCE = "@1792454400";

    private final IncidentService incidentService;
    private final IncidentStatsService statsService;

    public IncidentController(IncidentService incidentService, IncidentStatsService statsService) {
        this.incidentService = incidentService;
        this.statsService = statsService;
    }

    @GetMapping
    public PageResponse<IncidentSummary> list(
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) @Size(max = 100) String application,
            @RequestParam(required = false) @Size(max = 100) String service,
            @RequestParam(required = false) @Size(max = 50) String environment,
            @RequestParam(name = "q", required = false) @Size(max = 200) String query,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        IncidentFilter filter = new IncidentFilter(status, severity, application, service, environment, query);
        return PageResponse.from(incidentService.list(filter, page, size).map(IncidentSummary::from));
    }

    /** Counts by status, severity and service, and new incidents per UTC day for the last {@code days}. */
    @GetMapping("/stats")
    public IncidentStats stats(@RequestParam(defaultValue = "14") @Min(1) @Max(90) int days) {
        return statsService.stats(days, Instant.now());
    }

    @GetMapping("/{id}")
    public IncidentDetail get(@PathVariable UUID id) {
        return toDetail(incidentService.get(id));
    }

    /**
     * Deprecated since Phase 3: incidents are created automatically from Kafka messages. Kept so
     * existing clients keep working; the response carries an RFC 9745 {@code Deprecation} header.
     */
    @Deprecated
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IncidentDetail> create(@Valid @RequestBody CreateIncidentRequest request) {
        IncidentDetail incident = toDetail(incidentService.createFromStoredEvents(request));
        return ResponseEntity.created(URI.create("/api/v1/incidents/" + incident.id()))
                .header(DEPRECATION_HEADER, DEPRECATED_SINCE)
                .body(incident);
    }

    private static IncidentDetail toDetail(IncidentWithEvents result) {
        return IncidentDetail.from(result.incident(), result.latestEvents(), IncidentService.RECENT_EVENT_LIMIT);
    }
}
