package com.rootcauseplatform.incident.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private Application application;

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

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(schema = "incident", name = "incident_events",
            joinColumns = @JoinColumn(name = "incident_id"),
            inverseJoinColumns = @JoinColumn(name = "error_event_id"))
    private Set<ErrorEvent> events = new LinkedHashSet<>();

    protected Incident() {
    }

    /** Opens a new incident for error events that share an application and fingerprint. */
    public static Incident open(Application application, String fingerprint, String title, Severity severity,
            String summary, Set<ErrorEvent> events) {
        Incident incident = new Incident();
        incident.application = application;
        incident.fingerprint = fingerprint;
        incident.title = title;
        incident.severity = severity;
        incident.summary = summary;
        incident.status = IncidentStatus.OPEN;
        incident.events.addAll(events);
        incident.occurrenceCount = events.size();
        incident.firstSeenAt = events.stream().map(ErrorEvent::getEventTimestamp).min(Instant::compareTo).orElseThrow();
        incident.lastSeenAt = events.stream().map(ErrorEvent::getEventTimestamp).max(Instant::compareTo).orElseThrow();
        return incident;
    }

    public UUID getId() {
        return id;
    }

    public Application getApplication() {
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
