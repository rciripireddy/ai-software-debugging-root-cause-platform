package com.rootcauseplatform.incident.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "incident", name = "incidents")
public class Incident {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Embedded
    private ApplicationInfo application;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(nullable = false, updatable = false)
    private String fingerprint;

    private String summary;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Incident() {
    }

    /** Opens a new incident for one or more occurrences of the same error. */
    public static Incident open(ApplicationInfo application, String fingerprint, String title, Severity severity,
            String summary, Instant firstSeenAt, Instant lastSeenAt, int occurrenceCount) {
        Incident incident = new Incident();
        incident.application = application;
        incident.fingerprint = fingerprint;
        incident.title = title;
        incident.severity = severity;
        incident.summary = summary;
        incident.status = IncidentStatus.OPEN;
        incident.firstSeenAt = firstSeenAt;
        incident.lastSeenAt = lastSeenAt;
        incident.occurrenceCount = occurrenceCount;
        return incident;
    }

    /**
     * Adds one occurrence. Events can arrive out of order, so first/last seen are the minimum and
     * maximum event timestamps, and the severity never decreases.
     */
    public void recordOccurrence(Instant eventTimestamp, Severity eventSeverity) {
        occurrenceCount++;
        if (eventTimestamp.isBefore(firstSeenAt)) {
            firstSeenAt = eventTimestamp;
        }
        if (eventTimestamp.isAfter(lastSeenAt)) {
            lastSeenAt = eventTimestamp;
        }
        if (eventSeverity.compareTo(severity) > 0) {
            severity = eventSeverity;
        }
    }

    public UUID getId() {
        return id;
    }

    public ApplicationInfo getApplication() {
        return application;
    }

    public String getTitle() {
        return title;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
