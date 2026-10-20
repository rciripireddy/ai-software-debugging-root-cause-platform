package com.rootcauseplatform.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "ingestion", name = "error_events")
public class ErrorEvent {

    /** Time-ordered (version 7) UUIDs keep inserts into the primary key index sequential. */
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
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

    @Column(name = "stack_trace")
    private String stackTrace;

    private String source;

    private String host;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(nullable = false)
    private String fingerprint;

    /** The accepted request payload, including optional metadata, stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false)
    private String rawPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ErrorEvent() {
    }

    public ErrorEvent(Application application, Instant eventTimestamp, Severity severity, String message,
            String exceptionType, String stackTrace, String source, String host, String traceId,
            String correlationId, String fingerprint, String rawPayload) {
        this.application = application;
        this.eventTimestamp = eventTimestamp;
        this.severity = severity;
        this.message = message;
        this.exceptionType = exceptionType;
        this.stackTrace = stackTrace;
        this.source = source;
        this.host = host;
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.fingerprint = fingerprint;
        this.rawPayload = rawPayload;
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

    public String getStackTrace() {
        return stackTrace;
    }

    public String getSource() {
        return source;
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

    public String getRawPayload() {
        return rawPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
