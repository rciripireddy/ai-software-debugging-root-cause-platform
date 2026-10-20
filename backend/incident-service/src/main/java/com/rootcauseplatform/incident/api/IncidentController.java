package com.rootcauseplatform.incident.api;

import java.net.URI;
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

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(path = "/api/v1/incidents", produces = MediaType.APPLICATION_JSON_VALUE)
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping
    public PageResponse<IncidentSummary> list(
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) @Size(max = 100) String application,
            @RequestParam(required = false) @Size(max = 100) String service,
            @RequestParam(required = false) @Size(max = 50) String environment,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        IncidentFilter filter = new IncidentFilter(status, severity, application, service, environment);
        return PageResponse.from(incidentService.list(filter, page, size).map(IncidentSummary::from));
    }

    @GetMapping("/{id}")
    public IncidentDetail get(@PathVariable UUID id) {
        return toDetail(incidentService.get(id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IncidentDetail> create(@Valid @RequestBody CreateIncidentRequest request) {
        IncidentDetail incident = toDetail(incidentService.create(request));
        return ResponseEntity.created(URI.create("/api/v1/incidents/" + incident.id())).body(incident);
    }

    private static IncidentDetail toDetail(IncidentWithEvents result) {
        return IncidentDetail.from(result.incident(), result.latestEvents(), IncidentService.RECENT_EVENT_LIMIT);
    }
}
