package com.rootcauseplatform.ingestion.service;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rootcauseplatform.ingestion.api.LogEventRequest;
import com.rootcauseplatform.ingestion.domain.Application;
import com.rootcauseplatform.ingestion.domain.ApplicationRepository;
import com.rootcauseplatform.ingestion.domain.ErrorEvent;
import com.rootcauseplatform.ingestion.domain.ErrorEventRepository;

import tools.jackson.databind.json.JsonMapper;

@Service
public class LogIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionService.class);

    private final ApplicationRepository applications;
    private final ErrorEventRepository events;
    private final ErrorFingerprinter fingerprinter;
    private final JsonMapper jsonMapper;

    public LogIngestionService(ApplicationRepository applications, ErrorEventRepository events,
            ErrorFingerprinter fingerprinter, JsonMapper jsonMapper) {
        this.applications = applications;
        this.events = events;
        this.fingerprinter = fingerprinter;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public ErrorEvent ingest(LogEventRequest request) {
        Application application = resolveApplication(
                normalizeName(request.application()),
                normalizeName(request.service()),
                normalizeName(request.environment()));

        String fingerprint = fingerprinter.fingerprint(application.getServiceName(), request.exceptionType(),
                request.message(), request.stackTrace());

        ErrorEvent event = events.save(new ErrorEvent(
                application,
                request.timestamp(),
                request.severity(),
                request.message(),
                blankToNull(request.exceptionType()),
                blankToNull(request.stackTrace()),
                blankToNull(request.source()),
                blankToNull(request.host()),
                blankToNull(request.traceId()),
                blankToNull(request.correlationId()),
                fingerprint,
                jsonMapper.writeValueAsString(request)));

        // Identifiers only: messages and stack traces can contain user data and are not logged.
        log.atInfo()
                .setMessage("Log event ingested")
                .addKeyValue("action", "log.ingest")
                .addKeyValue("eventId", event.getId())
                .addKeyValue("applicationId", application.getId())
                .addKeyValue("applicationName", application.getName())
                .addKeyValue("serviceName", application.getServiceName())
                .addKeyValue("environment", application.getEnvironment())
                .addKeyValue("severity", event.getSeverity())
                .addKeyValue("fingerprint", fingerprint)
                .addKeyValue("eventTraceId", event.getTraceId())
                .addKeyValue("eventCorrelationId", event.getCorrelationId())
                .log();
        return event;
    }

    @Transactional(readOnly = true)
    public ErrorEvent getEvent(UUID id) {
        return events.findWithApplicationById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Log event " + id + " was not found"));
    }

    private Application resolveApplication(String name, String service, String environment) {
        return applications.findByNameAndServiceNameAndEnvironment(name, service, environment)
                .orElseGet(() -> registerApplication(name, service, environment));
    }

    private Application registerApplication(String name, String service, String environment) {
        boolean inserted = applications.insertIfAbsent(name, service, environment) == 1;
        Application application = applications.findByNameAndServiceNameAndEnvironment(name, service, environment)
                .orElseThrow(() -> new IllegalStateException("Application was not found after registration"));
        if (inserted) {
            log.atInfo()
                    .setMessage("Application registered")
                    .addKeyValue("action", "application.register")
                    .addKeyValue("applicationId", application.getId())
                    .addKeyValue("applicationName", name)
                    .addKeyValue("serviceName", service)
                    .addKeyValue("environment", environment)
                    .log();
        }
        return application;
    }

    private static String normalizeName(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
