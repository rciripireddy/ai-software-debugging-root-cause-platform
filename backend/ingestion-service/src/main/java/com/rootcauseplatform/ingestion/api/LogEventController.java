package com.rootcauseplatform.ingestion.api;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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
import com.rootcauseplatform.ingestion.monitoring.IngestionMetrics;
import com.rootcauseplatform.ingestion.security.FixedWindowRateLimiter;
import com.rootcauseplatform.ingestion.service.EventFilter;
import com.rootcauseplatform.ingestion.service.EventQueryService;
import com.rootcauseplatform.ingestion.service.LogIngestionService;
import com.rootcauseplatform.ingestion.service.RateLimitExceededException;

import jakarta.servlet.http.HttpServletRequest;
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
    private final FixedWindowRateLimiter ingestRateLimiter;
    private final IngestionMetrics metrics;

    public LogEventController(LogIngestionService ingestionService, EventQueryService queryService,
            JsonMapper jsonMapper, @Value("${app.ingestion.rate-limit-per-minute}") int ingestLimitPerMinute,
            Clock clock, IngestionMetrics metrics) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
        this.ingestRateLimiter = new FixedWindowRateLimiter(ingestLimitPerMinute, Duration.ofMinutes(1), clock);
    }

    /** Stored events, most recent first, without stack traces and payloads. */
    @GetMapping
    public PageResponse<EventSummary> list(
            @RequestParam(required = false) @Size(max = 100) String application,
            @RequestParam(required = false) @Size(max = 100) String service,
            @RequestParam(required = false) @Size(max = 50) String environment,
            @RequestParam(required = false) Severity severity,
            @RequestParam(name = "q", required = false) @Size(max = 200) String query,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
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
    public ResponseEntity<LogEventCreatedResponse> ingest(@Valid @RequestBody LogEventRequest request,
            HttpServletRequest httpRequest) {
        Duration wait = ingestRateLimiter.tryAcquire(httpRequest.getRemoteAddr());
        if (!wait.isZero()) {
            throw new RateLimitExceededException("Too many events from this client. Retry later.", wait);
        }
        ErrorEvent event = ingestionService.ingest(request);
        // Counted after the transaction has committed: the event and its outbox message are stored.
        metrics.eventAccepted(event.getSeverity());
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
