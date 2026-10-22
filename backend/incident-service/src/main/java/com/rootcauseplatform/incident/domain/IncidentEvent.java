package com.rootcauseplatform.incident.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

/**
 * An error event that belongs to an incident, with the details needed to display it. The ID is
 * the error event's ID from ingestion-service, so an event can belong to only one incident.
 */
@Entity
@Table(schema = "incident", name = "incident_events")
public class IncidentEvent implements Persistable<UUID> {

    @Id
    @Column(name = "error_event_id")
    private UUID errorEventId;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Severity severity;

    @Column(nullable = false, updatable = false)
    private String message;

    @Column(name = "exception_type", updatable = false)
    private String exceptionType;

    @Column(updatable = false)
    private String host;

    @Column(name = "trace_id", updatable = false)
    private String traceId;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** IDs are assigned, not generated, so saving must always insert rather than merge. */
    @Transient
    private boolean isNew = true;

    protected IncidentEvent() {
    }

    public IncidentEvent(UUID errorEventId, UUID incidentId, Instant eventTimestamp, Severity severity,
            String message, String exceptionType, String host, String traceId, String correlationId) {
        this.errorEventId = errorEventId;
        this.incidentId = incidentId;
        this.eventTimestamp = eventTimestamp;
        this.severity = severity;
        this.message = message;
        this.exceptionType = exceptionType;
        this.host = host;
        this.traceId = traceId;
        this.correlationId = correlationId;
    }

    @Override
    public UUID getId() {
        return errorEventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    public UUID getIncidentId() {
        return incidentId;
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
}
