package com.rootcauseplatform.incident.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * Read-only view of an error event stored by ingestion-service. Only the columns this
 * service needs are mapped.
 */
@Entity
@Immutable
@Table(schema = "ingestion", name = "error_events")
public class ErrorEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(nullable = false)
    private String message;

    @Column(name = "exception_type")
    private String exceptionType;

    private String host;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(nullable = false)
    private String fingerprint;

    protected ErrorEvent() {
    }

    public UUID getId() {
        return id;
    }

    public Application getApplication() {
        return application;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getHost() {
        return host;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getFingerprint() {
        return fingerprint;
    }
}
