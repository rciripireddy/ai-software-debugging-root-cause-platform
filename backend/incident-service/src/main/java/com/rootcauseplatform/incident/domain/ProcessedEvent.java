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

/** Records that the Kafka message for an error event has been processed. */
@Entity
@Table(schema = "incident", name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ProcessingOutcome outcome;

    @Column(name = "incident_id", updatable = false)
    private UUID incidentId;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    /**
     * IDs are assigned, so saving must insert: a second insert for the same event fails on the
     * primary key instead of silently updating the first record.
     */
    @Transient
    private boolean isNew = true;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, ProcessingOutcome outcome, UUID incidentId) {
        this.eventId = eventId;
        this.outcome = outcome;
        this.incidentId = incidentId;
    }

    @Override
    public UUID getId() {
        return eventId;
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
}
