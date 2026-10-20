package com.rootcauseplatform.ingestion.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rootcauseplatform.ingestion.domain.Application;
import com.rootcauseplatform.ingestion.domain.ErrorEvent;
import com.rootcauseplatform.ingestion.service.LogIngestionService;

import jakarta.validation.Valid;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping(path = "/api/v1/logs", produces = MediaType.APPLICATION_JSON_VALUE)
public class LogEventController {

    private final LogIngestionService ingestionService;
    private final JsonMapper jsonMapper;

    public LogEventController(LogIngestionService ingestionService, JsonMapper jsonMapper) {
        this.ingestionService = ingestionService;
        this.jsonMapper = jsonMapper;
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
