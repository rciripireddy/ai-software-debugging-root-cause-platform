package com.rootcauseplatform.ingestion.api;

import java.net.URI;
import java.time.Duration;
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

import com.rootcauseplatform.ingestion.domain.Application;
import com.rootcauseplatform.ingestion.api.EventQueryResponses.ActivityResponse;
import com.rootcauseplatform.ingestion.api.EventQueryResponses.EventSummary;
import com.rootcauseplatform.ingestion.api.EventQueryResponses.PageResponse;
import com.rootcauseplatform.ingestion.domain.ErrorEvent;
import com.rootcauseplatform.ingestion.domain.Severity;
import com.rootcauseplatform.ingestion.service.EventFilter;
import com.rootcauseplatform.ingestion.service.EventQueryService;
import com.rootcauseplatform.ingestion.service.LogIngestionService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping(path = "/api/v1/logs", produces = MediaType.APPLICATION_JSON_VALUE)
public class LogEventController {

    private final LogIngestionService ingestionService;
    private final EventQueryService queryService;
    private final JsonMapper jsonMapper;

    public LogEventController(LogIngestionService ingestionService, EventQueryService queryService,
            JsonMapper jsonMapper) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.jsonMapper = jsonMapper;
    }

    /** Stored events, most recent first, without stack traces and payloads. */
    @GetMapping
    public PageResponse<EventSummary> list(
            @RequestParam(required = false) @Size(max = 100) String application,
            @RequestParam(required = false) @Size(max = 100) String service,
            @RequestParam(required = false) @Size(max = 50) String environment,
            @RequestParam(required = false) Severity severity,
            @RequestParam(name = "q", required = false) @Size(max = 200) String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        EventFilter filter = new EventFilter(application, service, environment, severity, query);
        return PageResponse.from(queryService.list(filter, page, size).map(EventSummary::from));
    }

    /** Events received in the last {@code hours} hours, by severity and service. */
    @GetMapping("/activity")
    public ActivityResponse activity(@RequestParam(defaultValue = "24") @Min(1) @Max(168) int hours) {
        return ActivityResponse.from(queryService.activity(Duration.ofHours(hours), Instant.now()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LogEventCreatedResponse> ingest(@Valid @RequestBody LogEventRequest request) {
        ErrorEvent event = ingestionService.ingest(request);
        URI location = URI.create("/api/v1/logs/" + event.getId());
        return ResponseEntity.created(location).body(new LogEventCreatedResponse(
                event.getId(), event.getApplication().getId(), event.getFingerprint(), event.getCreatedAt()));
    }

    @GetMapping("/{id}")
    public LogEventResponse get(@PathVariable UUID id) {
        return toResponse(ingestionService.getEvent(id));
    }

    private LogEventResponse toResponse(ErrorEvent event) {
        Application application = event.getApplication();
        return new LogEventResponse(
                event.getId(),
                new LogEventResponse.ApplicationSummary(application.getId(), application.getName(),
                        application.getServiceName(), application.getEnvironment()),
                event.getEventTimestamp(),
                event.getSeverity(),
                event.getMessage(),
                event.getExceptionType(),
                event.getStackTrace(),
                event.getSource(),
                event.getHost(),
                event.getTraceId(),
                event.getCorrelationId(),
                event.getFingerprint(),
                jsonMapper.readTree(event.getRawPayload()).get("metadata"),
                event.getCreatedAt());
    }
}
